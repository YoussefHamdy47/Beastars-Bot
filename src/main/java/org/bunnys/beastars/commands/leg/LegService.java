package org.bunnys.beastars.commands.leg;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.entities.User;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.beastars.database.LegData;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single owner of all persistence and caching for the Leg economy.
 *
 * <p>Nothing outside this class talks to the {@code Beastars Legs} collection.
 * Commands, buttons and modals call these methods, get back plain result records,
 * and hand those to {@link LegEmbeds} for rendering. That split is what keeps the
 * feature cheap to extend: a new leg command is a new embed, not a new query.
 *
 * <h2>Caching</h2>
 * Two Caffeine caches sit in front of Mongo:
 * <ul>
 *   <li><b>User profiles</b> - keyed {@code guildId:userId}. Every read path
 *       (stats, rule checks, admin pre-checks) goes through it. Writes push the
 *       post-image straight back in, so a mutation costs zero extra reads.</li>
 *   <li><b>Leaderboards</b> - keyed {@code guildId}. The leaderboard is the only
 *       multi-document query in the feature and previously ran on <em>every</em>
 *       page-turn click. It is now built once per {@link #LEADERBOARD_TTL_SECONDS}
 *       and paginated in memory.</li>
 * </ul>
 * DB failures are deliberately <em>not</em> cached - a transient Mongo blip must
 * not pin a wrong value in front of every user for the next hour.
 *
 * <p><b>Consistency note:</b> the leaderboard is eventually consistent within
 * {@link #LEADERBOARD_TTL_SECONDS}. Admin mutations invalidate it immediately;
 * ordinary offers do not, because invalidating on every offer would mean the
 * cache never serves a hit under load. Tune the constant, not the call sites.
 *
 * <p><b>Threading:</b> cached {@link LegData} instances are shared between
 * threads and must be treated as read-only. All mutation happens server-side via
 * update operators; nothing here ever writes a whole document back.
 */
public final class LegService {

    public static final String COLLECTION = "Beastars Legs";

    /** Every member is born with two legs. The economy's one hard invariant. */
    public static final int MAX_LEGS = 2;

    /** Placeholder stored in the history arrays when an admin edits a count directly. */
    public static final String ADMIN_MARKER = "ADMIN";

    static final int LEADERBOARD_PAGE_SIZE = 20;
    static final int HISTORY_PAGE_SIZE = 8;

    /** Caps memory per guild: 500 entries = 25 leaderboard pages. */
    static final int LEADERBOARD_MAX_ENTRIES = 500;
    private static final long LEADERBOARD_TTL_SECONDS = 60L;

    /**
     * Hard ceiling on a profile's interaction history.
     *
     * <p>{@code receivedFrom} was an unbounded {@code $push}. Receiving is not naturally
     * limited the way giving is - a member may only ever give {@value #MAX_LEGS} legs, but
     * can be fed by everyone, repeatedly, and an admin reset returns those legs to the
     * pool so the total is not even capped by the member count. A document that only ever
     * grows meets MongoDB's 16 MB ceiling eventually, and on that day every write to that
     * member fails permanently.
     *
     * <p>{@code $slice} keeps the most recent entries and drops the rest inside the same
     * atomic update, so the document is bounded at roughly 30 KB forever. The headline
     * totals live in {@code legsGiven} / {@code legsReceived}, which are counters and stay
     * exact - only the who-fed-whom log is trimmed, and only past a thousand entries.
     */
    private static final int HISTORY_LIMIT = 1_000;

    private static final PushOptions HISTORY_CAP = new PushOptions().slice(-HISTORY_LIMIT);

    private static final int DUPLICATE_KEY_ERROR = 11000;

    private static final Cache<String, LegData> USERS = CacheRegistry.register("leg.users", Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            // Second bound so any drift from an out-of-band write self-heals.
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build());

    private static final Cache<String, Leaderboard> LEADERBOARDS = CacheRegistry.register("leg.leaderboards", Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(LEADERBOARD_TTL_SECONDS, TimeUnit.SECONDS)
            .recordStats()
            .build());

    private static final AtomicBoolean INDEXES_READY = new AtomicBoolean(false);

    private LegService() {}

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    public enum OfferStatus {
        SUCCESS,
        SELF_TARGET,
        BOT_TARGET,
        BANNED,
        EXHAUSTED,
        DB_ERROR
    }

    /** @param legsRemaining legs the sender has left, or {@code -1} when not applicable. */
    public record OfferOutcome(OfferStatus status, int legsRemaining) {}

    /**
     * Outcome of an admin write.
     *
     * <p>Three states, not a boolean: "already in that state" and "the write blew
     * up" need different messages, and collapsing them is how an admin ends up
     * told a ban succeeded when Mongo was down.
     */
    public enum WriteResult { APPLIED, UNCHANGED, FAILED }

    public record RankedEntry(String userId, int legsReceived, int rank) {}

    /** Package-private: {@link LegLeaderboardSnapshots} pins these. Immutable, so sharing is free. */
    record Leaderboard(List<RankedEntry> entries) {}

    public record LeaderboardPage(
            List<RankedEntry> entries,
            int page,
            int maxPages,
            String highlightUserId,
            boolean highlightPresent,
            boolean failed) {}

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public static String key(String guildId, String userId) {
        return guildId + ":" + userId;
    }

    private static MongoCollection<LegData> legs() {
        return DB.getCollection(LegData.class, COLLECTION);
    }

    /**
     * Returns a member's profile, never null. A member with no document yet is a
     * member with zeroed stats, so the default is cached like any other value -
     * that is the common case in a large guild and must not hit Mongo twice.
     */
    public static LegData getUser(String guildId, String userId) {
        String id = key(guildId, userId);

        LegData cached = USERS.get(id, k -> {
            try {
                LegData stored = legs().find(Filters.eq("_id", k)).first();
                return stored != null ? stored : new LegData(guildId, userId);
            } catch (Exception e) {
                BunnyLog.error("[LegService] Read failed for profile " + k, e);
                // Returning null keeps the failure out of the cache.
                return null;
            }
        });

        return cached != null ? cached : new LegData(guildId, userId);
    }

    // ------------------------------------------------------------------
    // Offering
    // ------------------------------------------------------------------

    /**
     * Transfers one leg from sender to receiver.
     *
     * <p>Both writes are single atomic {@code findOneAndUpdate} calls using
     * {@code $inc}/{@code $push}, replacing the previous read-modify-replace pair.
     * That removes the lost-update race two simultaneous offers used to have, and
     * halves the round trips: the post-image comes back from the same call that
     * performs the write, so the cache is refreshed for free.
     *
     * <p>The {@code legsGiven < MAX_LEGS} guard lives in the update <em>filter</em>,
     * so the database - not a cached snapshot - is the authority on whether a
     * member still has flesh to give.
     */
    public static OfferOutcome offerLeg(String guildId, User sender, User receiver) {
        if (sender.getId().equals(receiver.getId()))
            return new OfferOutcome(OfferStatus.SELF_TARGET, -1);
        if (receiver.isBot())
            return new OfferOutcome(OfferStatus.BOT_TARGET, -1);

        LegData senderSnapshot = getUser(guildId, sender.getId());
        if (senderSnapshot.isBanned() || getUser(guildId, receiver.getId()).isBanned())
            return new OfferOutcome(OfferStatus.BANNED, -1);
        if (senderSnapshot.getLegsGiven() >= MAX_LEGS)
            return new OfferOutcome(OfferStatus.EXHAUSTED, 0);

        LegData updatedSender;
        try {
            updatedSender = legs().findOneAndUpdate(
                    Filters.and(
                            Filters.eq("_id", key(guildId, sender.getId())),
                            Filters.lt("legsGiven", MAX_LEGS),
                            Filters.ne("banned", true)),
                    Updates.combine(
                            Updates.inc("legsGiven", 1),
                            Updates.pushEach("givenTo", List.of(receiver.getId()), HISTORY_CAP),
                            Updates.setOnInsert("guildId", guildId),
                            Updates.setOnInsert("userId", sender.getId()),
                            Updates.setOnInsert("legsReceived", 0),
                            Updates.setOnInsert("banned", false),
                            Updates.setOnInsert("receivedFrom", new ArrayList<String>())),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        } catch (MongoWriteException | MongoCommandException e) {
            // Upsert + guarded filter: a duplicate-key error means the document
            // exists but no longer satisfies the guard, i.e. our snapshot was
            // stale and the sender is out of legs (or was just banned).
            if (isDuplicateKey(e)) {
                USERS.invalidate(key(guildId, sender.getId()));
                LegData fresh = getUser(guildId, sender.getId());
                return new OfferOutcome(
                        fresh.isBanned() ? OfferStatus.BANNED : OfferStatus.EXHAUSTED,
                        Math.max(0, MAX_LEGS - fresh.getLegsGiven()));
            }
            BunnyLog.error("[LegService] Sender write failed during offer in guild " + guildId, e);
            return new OfferOutcome(OfferStatus.DB_ERROR, -1);
        } catch (Exception e) {
            BunnyLog.error("[LegService] Sender write failed during offer in guild " + guildId, e);
            return new OfferOutcome(OfferStatus.DB_ERROR, -1);
        }

        cache(updatedSender);

        try {
            cache(legs().findOneAndUpdate(
                    Filters.eq("_id", key(guildId, receiver.getId())),
                    Updates.combine(
                            Updates.inc("legsReceived", 1),
                            Updates.pushEach("receivedFrom", List.of(sender.getId()), HISTORY_CAP),
                            Updates.setOnInsert("guildId", guildId),
                            Updates.setOnInsert("userId", receiver.getId()),
                            Updates.setOnInsert("legsGiven", 0),
                            Updates.setOnInsert("banned", false),
                            Updates.setOnInsert("givenTo", new ArrayList<String>())),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)));
        } catch (Exception e) {
            // The sender's leg is already gone. Loud log rather than a guessed
            // compensating write, which could corrupt a concurrently-updated array.
            BunnyLog.error("[LegService] ORPHANED LEG: sender " + sender.getId()
                    + " was debited but receiver " + receiver.getId()
                    + " was not credited in guild " + guildId, e);
            return new OfferOutcome(OfferStatus.DB_ERROR, -1);
        }

        return new OfferOutcome(OfferStatus.SUCCESS, Math.max(0, MAX_LEGS - updatedSender.getLegsGiven()));
    }

    // ------------------------------------------------------------------
    // Leaderboard
    // ------------------------------------------------------------------

    /**
     * A page of the leaderboard, optionally narrowed to a subset of members.
     *
     * <p>When {@code highlightUserId} is set and that member is on the board, the
     * page containing them is returned instead of {@code requestedPage} - this is
     * what the "Find Me" button rides on.
     *
     * <p>The filter is a plain predicate over user ids rather than anything Discord-shaped,
     * so this layer stays free of roles and guilds - the caller resolves who qualifies and
     * hands down the answer. Ranks are the ones from the <em>whole</em> board and are not
     * recomputed: a filtered view is a lens on one economy, not a separate league, so
     * somebody who is fourth overall still reads as fourth here.
     *
     * @param userFilter null to include everyone
     */
    public static LeaderboardPage leaderboardPage(String guildId, int requestedPage,
                                                  String highlightUserId,
                                                  java.util.function.Predicate<String> userFilter) {
        return pageOf(currentBoard(guildId), requestedPage, highlightUserId, userFilter);
    }

    /**
     * The guild's ranked board as it stands now, or null when the query failed.
     *
     * <p>Package-private and handed out as a whole because a frozen leaderboard needs to
     * <em>keep</em> one. The record is immutable, so pinning it costs a reference rather
     * than a copy, and every message opened inside the same cache window shares one
     * object. See {@link LegLeaderboardSnapshots}.
     */
    static Leaderboard currentBoard(String guildId) {
        return LEADERBOARDS.get(guildId, LegService::buildLeaderboard);
    }

    /**
     * Slices one page out of a board that has already been obtained.
     *
     * <p>Split from the fetch so the same paging, filtering and highlight logic serves a
     * live read and a pinned snapshot alike. Two copies of this would be two places for
     * the rank arithmetic to drift.
     */
    static LeaderboardPage pageOf(Leaderboard board, int requestedPage,
                                  String highlightUserId,
                                  java.util.function.Predicate<String> userFilter) {
        if (board == null)
            return new LeaderboardPage(List.of(), 1, 1, highlightUserId, false, true);
        if (board.entries().isEmpty())
            return new LeaderboardPage(List.of(), 1, 1, highlightUserId, false, false);

        List<RankedEntry> all = board.entries();

        if (userFilter != null) {
            List<RankedEntry> kept = new ArrayList<>();
            for (RankedEntry entry : all)
                if (userFilter.test(entry.userId()))
                    kept.add(entry);
            all = kept;
        }

        if (all.isEmpty())
            return new LeaderboardPage(List.of(), 1, 1, highlightUserId, false, false);
        int maxPages = Math.max(1, (int) Math.ceil((double) all.size() / LEADERBOARD_PAGE_SIZE));

        int highlightIndex = -1;
        if (highlightUserId != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).userId().equals(highlightUserId)) {
                    highlightIndex = i;
                    break;
                }
            }
        }

        boolean present = highlightIndex != -1;
        int page = present ? (highlightIndex / LEADERBOARD_PAGE_SIZE) + 1 : requestedPage;
        page = Math.max(1, Math.min(page, maxPages));

        int from = (page - 1) * LEADERBOARD_PAGE_SIZE;
        int to = Math.min(from + LEADERBOARD_PAGE_SIZE, all.size());

        return new LeaderboardPage(List.copyOf(all.subList(from, to)), page, maxPages,
                highlightUserId, present, false);
    }

    /**
     * Builds the ranked snapshot for a guild.
     *
     * <p>Projects away everything but {@code userId}/{@code legsReceived} and caps
     * the result, so a guild with a million profiles still costs a bounded,
     * index-backed query rather than decoding every POJO into memory.
     */
    /** Drops the cached board so the next read rebuilds under changed settings. */
    public static void invalidateLeaderboard(String guildId) {
        LEADERBOARDS.invalidate(guildId);
    }

    private static Leaderboard buildLeaderboard(String guildId) {
        ensureIndexes();

        // Banned members are excluded in the query rather than after it: it is a flag we
        // already own, so there is no reason to carry the rows across the wire and drop
        // them in Java. Everything else the leaderboard hides depends on Discord state
        // and has to happen at render time.
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq("guildId", guildId));
        conditions.add(Filters.gt("legsReceived", 0));

        if (LegConfigService.getConfig(guildId).isHideBannedOnLeaderboard())
            conditions.add(Filters.ne("banned", true));

        List<Document> raw;
        try {
            raw = DB.getCollection(Document.class, COLLECTION)
                    .find(Filters.and(conditions))
                    .projection(Projections.fields(
                            Projections.include("userId", "legsReceived"),
                            Projections.excludeId()))
                    .sort(Sorts.descending("legsReceived"))
                    .limit(LEADERBOARD_MAX_ENTRIES)
                    .into(new ArrayList<>());
        } catch (Exception e) {
            BunnyLog.error("[LegService] Leaderboard query failed for guild " + guildId, e);
            return null; // Not cached; the next click retries.
        }

        List<RankedEntry> ranked = new ArrayList<>(raw.size());
        int rank = 1;
        int position = 1;
        int previousCount = -1;

        for (Document doc : raw) {
            String userId = doc.getString("userId");
            if (userId == null)
                continue;

            int count = asInt(doc.get("legsReceived"));
            // Competition ranking: equal counts share a rank, the next distinct
            // count jumps to its absolute position (1, 2, 2, 4...).
            if (count != previousCount && previousCount != -1)
                rank = position;

            ranked.add(new RankedEntry(userId, count, rank));
            previousCount = count;
            position++;
        }

        return new Leaderboard(List.copyOf(ranked));
    }

    // ------------------------------------------------------------------
    // Admin mutations
    // ------------------------------------------------------------------

    public static WriteResult setBanned(String guildId, String userId, boolean banned) {
        if (getUser(guildId, userId).isBanned() == banned)
            return WriteResult.UNCHANGED;

        return write(guildId, userId, Updates.combine(
                Updates.set("banned", banned),
                Updates.setOnInsert("guildId", guildId),
                Updates.setOnInsert("userId", userId)), true)
                ? WriteResult.APPLIED : WriteResult.FAILED;
    }

    /** Zeroes every statistic while keeping the profile (and its ban state) alive. */
    public static boolean resetUser(String guildId, String userId) {
        return write(guildId, userId, Updates.combine(
                Updates.set("legsGiven", 0),
                Updates.set("legsReceived", 0),
                Updates.set("givenTo", new ArrayList<String>()),
                Updates.set("receivedFrom", new ArrayList<String>()),
                Updates.setOnInsert("guildId", guildId),
                Updates.setOnInsert("userId", userId)), true);
    }

    /**
     * Forces exact counts, rebuilding the history arrays to match.
     *
     * <p>Real interactions are preserved up to the new limit and any shortfall is
     * padded with {@link #ADMIN_MARKER}, so the history view can still separate
     * "who actually fed whom" from "an admin typed a number".
     */
    public static boolean setStats(String guildId, String userId, int legsGiven, int legsReceived) {
        LegData current = getUser(guildId, userId);

        return write(guildId, userId, Updates.combine(
                Updates.set("legsGiven", legsGiven),
                Updates.set("legsReceived", legsReceived),
                Updates.set("givenTo", rebuildHistory(current.getGivenTo(), legsGiven)),
                Updates.set("receivedFrom", rebuildHistory(current.getReceivedFrom(), legsReceived)),
                Updates.setOnInsert("guildId", guildId),
                Updates.setOnInsert("userId", userId)), true);
    }

    private static List<String> rebuildHistory(List<String> existing, int target) {
        List<String> rebuilt = new ArrayList<>(Math.max(0, target));
        for (String entry : existing) {
            if (rebuilt.size() >= target)
                break;
            if (!ADMIN_MARKER.equals(entry))
                rebuilt.add(entry);
        }
        while (rebuilt.size() < target)
            rebuilt.add(ADMIN_MARKER);
        return rebuilt;
    }

    public static WriteResult deleteUser(String guildId, String userId) {
        try {
            long removed = legs().deleteOne(Filters.eq("_id", key(guildId, userId))).getDeletedCount();
            invalidate(guildId, userId);
            return removed > 0 ? WriteResult.APPLIED : WriteResult.UNCHANGED;
        } catch (Exception e) {
            BunnyLog.error("[LegService] Delete failed for " + key(guildId, userId), e);
            return WriteResult.FAILED;
        }
    }

    /** Wipes every profile in a guild. Throws so the caller can report the failure. */
    public static long nukeGuild(String guildId) {
        long removed = legs().deleteMany(Filters.eq("guildId", guildId)).getDeletedCount();
        String prefix = guildId + ":";
        USERS.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        LEADERBOARDS.invalidate(guildId);
        return removed;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Applies an update and refreshes the cache from the returned post-image. */
    private static boolean write(String guildId, String userId, org.bson.conversions.Bson update, boolean upsert) {
        try {
            LegData updated = legs().findOneAndUpdate(
                    Filters.eq("_id", key(guildId, userId)),
                    update,
                    new FindOneAndUpdateOptions().upsert(upsert).returnDocument(ReturnDocument.AFTER));

            if (updated != null)
                cache(updated);
            else
                USERS.invalidate(key(guildId, userId));

            // Admin edits must be visible on the leaderboard immediately; the
            // TTL alone would leave a moderator staring at stale numbers.
            LEADERBOARDS.invalidate(guildId);
            return true;
        } catch (Exception e) {
            BunnyLog.error("[LegService] Write failed for " + key(guildId, userId), e);
            invalidate(guildId, userId);
            return false;
        }
    }

    /** Refreshes a profile in the cache. Deliberately leaves the leaderboard alone. */
    private static void cache(LegData data) {
        if (data != null && data.getId() != null)
            USERS.put(data.getId(), data);
    }

    public static void invalidate(String guildId, String userId) {
        USERS.invalidate(key(guildId, userId));
        LEADERBOARDS.invalidate(guildId);
    }

    private static boolean isDuplicateKey(Exception e) {
        if (e instanceof MongoWriteException mwe)
            return mwe.getError().getCode() == DUPLICATE_KEY_ERROR;
        if (e instanceof MongoCommandException mce)
            return mce.getErrorCode() == DUPLICATE_KEY_ERROR;
        return false;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Creates the compound index the leaderboard sorts on. Idempotent and run at
     * most once per process - without it the sort is a full in-memory collection
     * scan, which is exactly what falls over at scale.
     */
    private static void ensureIndexes() {
        if (!INDEXES_READY.compareAndSet(false, true))
            return;
        try {
            legs().createIndex(
                    Indexes.compoundIndex(Indexes.ascending("guildId"), Indexes.descending("legsReceived")),
                    new IndexOptions().background(true).name("guild_legsReceived_idx"));
            BunnyLog.info("[LegService] Leaderboard index verified.");
        } catch (Exception e) {
            INDEXES_READY.set(false); // Let a later call retry.
            BunnyLog.error("[LegService] Could not create the leaderboard index", e);
        }
    }
}
