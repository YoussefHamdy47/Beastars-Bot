package org.bunnys.beastars.commands.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.BunnyLog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The audit trail: who changed what, when.
 *
 * <p>Every administrative mutation lands in two places - a MongoDB document, and an
 * embed in the guild's configured log channel. Mongo is the authority; Discord is the
 * convenience copy.
 *
 * <h2>Storage is bounded on purpose</h2>
 * The cluster is a 512 MB free tier, and an audit trail is the classic collection that
 * grows without limit until it takes the database down with it. {@code audit_logs} is
 * therefore created as a <b>capped collection</b>: a fixed {@value #CAP_MEGABYTES} MB
 * ring buffer holding at most {@value #MAX_DOCUMENTS} documents, evicting oldest-first
 * once full. Capped is chosen over a TTL index because it is a <em>hard</em> ceiling -
 * a TTL bounds age, not size, so a burst of activity inside the retention window can
 * still blow the quota. Storage can never exceed the cap, whatever happens.
 *
 * <p>A capped collection cannot be created over an existing uncapped one, so if
 * {@code audit_logs} already exists uncapped (an older deploy, a manual insert) the
 * service falls back to a {@value #RETENTION_DAYS}-day TTL index and logs how to
 * convert. That keeps growth bounded either way rather than silently doing nothing.
 *
 * <p>The cap is <em>grown in place</em> when the constants below are raised - see
 * {@link #growCapIfNeeded}. Without that, changing them would only ever affect servers
 * that had never run the bot before.
 *
 * <p>Deliberately no local log file: the host runs on flash storage, and a
 * continuously-appended file is exactly the write pattern that wears it out.
 */
public final class AuditService {

    public static final String COLLECTION = "audit_logs";

    /**
     * Hard ceiling on the collection's on-disk size.
     *
     * <p>Raised from 5 MB / 10k documents once the trail stopped being admin-only. Leg
     * transfers are written by ordinary members and vastly outnumber moderation, so at
     * the old ceiling a busy server's history was measured in days. At roughly 350 bytes
     * per entry, {@value #MAX_DOCUMENTS} documents is the binding limit and the size cap
     * is headroom above it - deliberately, so an unusually long details string cannot
     * quietly evict history early.
     *
     * <p>Still a small fraction of the 512 MB free tier the cluster shares with other
     * bots, and still a <em>hard</em> ceiling: the collection cannot exceed it whatever
     * the traffic.
     */
    private static final int CAP_MEGABYTES = 32;
    private static final long CAP_BYTES = CAP_MEGABYTES * 1024L * 1024L;
    private static final long MAX_DOCUMENTS = 50_000L;

    /** Only used on the uncapped fallback path. */
    private static final int RETENTION_DAYS = 30;

    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);

    /** How long between "you have not configured logging" notices, per guild. */
    private static final int HINT_INTERVAL_HOURS = 24;

    private static final Cache<String, Boolean> HINTED = CacheRegistry.register("audit.hinted", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(HINT_INTERVAL_HOURS, TimeUnit.HOURS)
            .recordStats()
            .build());

    /** Shorter than the hint: a broken channel is an active fault worth repeating sooner. */
    private static final Cache<String, Boolean> WARNED = CacheRegistry.register("audit.warned", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()
            .build());

    private AuditService() {}

    /**
     * Creates or verifies the bounded {@code audit_logs} collection.
     *
     * <p>Idempotent and safe to call on every boot: it inspects what exists before
     * touching anything, and never throws - a bot that cannot set up its audit storage
     * should still start and serve commands.
     */
    public static void initialise() {
        if (!INITIALISED.compareAndSet(false, true))
            return;

        try {
            // The cluster is shared with other bots. This filters server-side on the
            // exact name, so the only collection metadata that ever comes back is our
            // own, and the only collection ever created below is {@value #COLLECTION}.
            // Nothing here enumerates, reads or modifies anything this bot does not own.
            Document existing = DB.getDatabase()
                    .listCollections()
                    .filter(Filters.eq("name", COLLECTION))
                    .first();

            if (existing == null) {
                DB.getDatabase().createCollection(COLLECTION, new CreateCollectionOptions()
                        .capped(true)
                        .sizeInBytes(CAP_BYTES)
                        .maxDocuments(MAX_DOCUMENTS));

                BunnyLog.success("[AuditService] Created capped audit_logs ("
                        + CAP_MEGABYTES + " MB / " + MAX_DOCUMENTS + " docs max).");
                ensureExportIndex();
                return;
            }

            Document options = existing.get("options", Document.class);
            boolean capped = options != null && Boolean.TRUE.equals(options.getBoolean("capped"));

            if (capped) {
                growCapIfNeeded(options);
                ensureExportIndex();
                return;
            }

            // Cannot convert in place. A TTL index at least bounds growth by age.
            DB.getCollection(Document.class, COLLECTION).createIndex(
                    Indexes.ascending("createdAt"),
                    new IndexOptions()
                            .name("audit_ttl_idx")
                            .expireAfter((long) RETENTION_DAYS, TimeUnit.DAYS));

            BunnyLog.warning("[AuditService] audit_logs exists but is NOT capped.");
            BunnyLog.warning("[AuditService] Applied a " + RETENTION_DAYS + "-day TTL index as a fallback.");
            BunnyLog.warning("[AuditService] For a hard size cap, drop the collection and restart, or run: "
                    + "db.runCommand({convertToCapped: '" + COLLECTION + "', size: " + CAP_BYTES + "})");

            ensureExportIndex();

        } catch (Exception e) {
            INITIALISED.set(false); // Let a later call retry.
            BunnyLog.error("[AuditService] Could not prepare the audit_logs collection", e);
        }
    }

    /**
     * Creates the index {@link #export} sorts on.
     *
     * <p>The export filters on {@code guildId} and sorts on {@code createdAt}, and neither
     * was indexed - so it was a full collection scan followed by a blocking in-memory sort.
     * That was survivable at the old ten-thousand-document cap and is not something to
     * carry into a fifty-thousand one: MongoDB refuses an in-memory sort above 32 MB, and
     * {@value #MAX_DOCUMENTS} documents at roughly 350 bytes lands close enough to that
     * ceiling to fail on a busy guild. With the index it is a bounded index scan that
     * touches only the {@value #EXPORT_LIMIT} documents it returns.
     *
     * <p>A compound index rather than {@code $natural} order, even though this collection
     * is usually capped and capped collections do return insertion order for free. The
     * uncapped fallback path above has no such guarantee once the TTL starts deleting, and
     * an export that silently returns misordered history on one of the two paths is worse
     * than an index this small costs. One index entry per insert, on a bounded collection.
     *
     * <p>Never throws: index creation needs a privilege the application user may not have
     * on a shared cluster, and an unindexed export is slow rather than broken.
     */
    private static void ensureExportIndex() {
        try {
            DB.getCollection(Document.class, COLLECTION).createIndex(
                    Indexes.compoundIndex(Indexes.ascending("guildId"), Indexes.descending("createdAt")),
                    new IndexOptions().background(true).name("audit_guild_time_idx"));
        } catch (Exception e) {
            BunnyLog.warning("[AuditService] Could not create the audit export index: " + e.getMessage());
            BunnyLog.warning("[AuditService] /admin logging export still works, but scans and sorts in memory.");
        }
    }

    /**
     * Widens an existing cap to match the constants above.
     *
     * <p>{@code createCollection} only applies its options when it actually creates
     * something, so on every server that has already run this bot the cap is whatever was
     * configured the day the collection was made. Raising {@link #CAP_MEGABYTES} without
     * this would change nothing anywhere it mattered, and would do it silently - the
     * startup line would still report a bounded collection, just not the bound in the
     * source.
     *
     * <p><b>Only ever grows.</b> A cap larger than configured is left alone: it means
     * somebody sized it deliberately, and shrinking a capped collection discards the
     * oldest entries immediately to fit. Growing one discards nothing.
     *
     * <p>{@code collMod} needs MongoDB 6.0 or newer, and the calling user needs
     * {@code collMod} on the database. Neither is guaranteed on a shared cluster, so a
     * failure logs the manual command and carries on - the old cap is still a hard
     * ceiling, and the trail is still bounded. Nothing here can fail a boot.
     */
    private static void growCapIfNeeded(Document options) {
        long currentSize = asLong(options.get("size"));
        long currentMax = asLong(options.get("max"));

        boolean growSize = currentSize > 0 && currentSize < CAP_BYTES;
        // A max of 0 or absent means "no document limit", which is already looser than
        // any number we would set. Only a real, smaller limit is worth raising.
        boolean growMax = currentMax > 0 && currentMax < MAX_DOCUMENTS;

        if (!growSize && !growMax) {
            BunnyLog.info("[AuditService] audit_logs is capped; storage is bounded.");
            return;
        }

        Document command = new Document("collMod", COLLECTION);
        if (growSize)
            command.append("cappedSize", CAP_BYTES);
        if (growMax)
            command.append("cappedMax", MAX_DOCUMENTS);

        try {
            DB.getDatabase().runCommand(command);
            BunnyLog.success("[AuditService] Grew the audit_logs cap to "
                    + CAP_MEGABYTES + " MB / " + MAX_DOCUMENTS + " docs max.");
        } catch (Exception e) {
            BunnyLog.warning("[AuditService] Could not grow the audit_logs cap: " + e.getMessage());
            BunnyLog.warning("[AuditService] Still bounded at the existing cap. To raise it by hand, run: "
                    + "db.runCommand(" + command.toJson() + ")");
        }
    }

    /** Capped-collection options come back as an int or a long depending on their size. */
    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * What kind of entry this is, and how loudly it may complain about its delivery.
     *
     * <p>The trail was originally admin-only, so every entry could assume its author was
     * a moderator standing in a channel they administer. Leg transactions broke that
     * assumption: they are written by ordinary members, in whatever channel they happened
     * to offer a leg in. Telling that member their server's audit logging is unconfigured
     * is noise aimed at somebody who cannot act on it, and it would fire in public chat.
     *
     * <p>So the category carries {@code notifiesOnBrokenDelivery}: admin entries still
     * nudge and still warn, and economy entries fall silent - the Mongo record is written
     * either way, and an admin who has configured a log channel sees them there.
     */
    public enum Category {

        /** A moderator changed something. */
        ADMIN("Admin Action", "Administrator", true),

        /** A member moved flesh in the Leg economy. */
        ECONOMY("Leg Transaction", "Member", false);

        private final String title;
        private final String actorLabel;
        private final boolean notifiesOnBrokenDelivery;

        Category(String title, String actorLabel, boolean notifiesOnBrokenDelivery) {
            this.title = title;
            this.actorLabel = actorLabel;
            this.notifiesOnBrokenDelivery = notifiesOnBrokenDelivery;
        }

        public String title() { return title; }

        /** Field name for whoever performed the action. */
        public String actorLabel() { return actorLabel; }

        boolean notifiesOnBrokenDelivery() { return notifiesOnBrokenDelivery; }

        /** Resolves a stored name, defaulting to {@link #ADMIN} for pre-category documents. */
        static Category fromStored(String name) {
            if (name != null)
                for (Category category : values())
                    if (category.name().equals(name))
                        return category;
            return ADMIN;
        }
    }

    /**
     * What an action was applied to.
     *
     * <p>A bare snowflake is ambiguous - the previous version rendered every 17-20 digit
     * target as {@code <@id>}, which turned an audited channel change into a mention of
     * a user who does not exist. The kind travels with the id so the renderer can never
     * guess wrong, and it is stored alongside it for the same reason.
     */
    public record AuditTarget(String id, Kind kind) {

        public enum Kind { USER, CHANNEL, ROLE, COMMAND, GLOBAL, NONE }

        public static AuditTarget user(String id) { return new AuditTarget(id, Kind.USER); }
        public static AuditTarget channel(String id) { return new AuditTarget(id, Kind.CHANNEL); }
        public static AuditTarget role(String id) { return new AuditTarget(id, Kind.ROLE); }
        public static AuditTarget command(String name) { return new AuditTarget(name, Kind.COMMAND); }
        public static AuditTarget global() { return new AuditTarget("ALL_USERS", Kind.GLOBAL); }
        public static AuditTarget none() { return new AuditTarget(null, Kind.NONE); }

        /** The Discord-renderable form, correct for the kind. */
        public String display() {
            if (id == null)
                return null;

            return switch (kind) {
                case USER -> "<@" + id + ">\n`" + id + "`";
                case CHANNEL -> "<#" + id + ">\n`" + id + "`";
                case ROLE -> "<@&" + id + ">\n`" + id + "`";
                case COMMAND -> "`" + id + "`";
                case GLOBAL -> "Every member in this server";
                case NONE -> null;
            };
        }
    }

    /**
     * Records an administrative action.
     *
     * <p>Order matters: MongoDB first, Discord second. The database write is the record
     * of truth, so a broken or deleted log channel must never be able to lose an audit
     * entry.
     *
     * @param fallback the channel the command was run in. Where the notice about a
     *                 missing or broken log channel goes - never where the entry itself
     *                 goes. May be null.
     */
    public static void record(Guild guild, User actor, MessageChannel fallback,
                              String action, AuditTarget target, String details) {
        record(guild, actor, fallback, Category.ADMIN, action, target, details);
    }

    /**
     * Records any auditable action.
     *
     * <p>Same contract as {@link #record(Guild, User, MessageChannel, String, AuditTarget, String)}
     * - Mongo first, Discord second - with the {@link Category} deciding how the entry is
     * titled and whether a missing or broken log channel is worth complaining about. See
     * {@link Category} for why an economy entry stays quiet.
     */
    public static void record(Guild guild, User actor, MessageChannel fallback, Category category,
                              String action, AuditTarget target, String details) {
        if (guild == null || actor == null)
            return;

        AuditTarget resolved = target == null ? AuditTarget.none() : target;
        Category resolvedCategory = category == null ? Category.ADMIN : category;

        persist(guild.getId(), actor, resolvedCategory, action, resolved, details);
        dispatch(guild, actor, fallback, resolvedCategory, action, resolved, details);
    }

    /**
     * Records one member handing a leg to another.
     *
     * <p>Both parties are mentioned: the trail's whole value here is being able to read
     * back who fed whom, and a bare display name goes stale the moment somebody renames
     * themselves. The receiver is the {@link AuditTarget} as well as appearing in the
     * details, so a future export or query can filter on them without parsing prose.
     *
     * @param legsRemaining what the giver has left, straight from the write's post-image
     */
    public static void recordLegTransfer(Guild guild, User sender, User receiver,
                                         MessageChannel fallback, int legsRemaining) {
        if (sender == null || receiver == null)
            return;

        record(guild, sender, fallback, Category.ECONOMY, "Leg Offered",
                AuditTarget.user(receiver.getId()),
                sender.getAsMention() + " gave a leg to " + receiver.getAsMention()
                        + ".\nThe giver has **" + Math.max(0, legsRemaining) + "** leg(s) remaining.");
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /** Never throws: a failed audit write must not fail the action it describes. */
    private static void persist(String guildId, User actor, Category category, String action,
                                AuditTarget target, String details) {
        try {
            initialise();

            DB.getCollection(Document.class, COLLECTION).insertOne(new Document()
                    .append("guildId", guildId)
                    .append("actorId", actor.getId())
                    .append("actorTag", actor.getName())
                    .append("category", category.name())
                    .append("action", action)
                    .append("target", target.id())
                    .append("targetKind", target.kind().name())
                    .append("details", details)
                    // A real BSON date, so the TTL fallback has something to index.
                    .append("createdAt", new Date()));

        } catch (Exception e) {
            BunnyLog.error("[AuditService] AUDIT DROPPED: guild=" + guildId
                    + " actor=" + actor.getId() + " action=" + action + " target=" + target.id(), e);
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** Newest first; enough to be useful without producing an unreadable wall. */
    public static final int EXPORT_LIMIT = 500;

    private static final DateTimeFormatter EXPORT_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** {@code <@123>} / {@code <@!123>} / {@code <@&123>} / {@code <#123>} down to the bare id. */
    private static final Pattern EXPORT_MENTION = Pattern.compile("<[@#][!&]?(\\d{17,20})>");

    /** Widest label the export prints, so every line in a block starts at the same column. */
    private static final int LABEL_WIDTH = "Administrator".length();

    /**
     * Scopes an export to one guild, and optionally to one category.
     *
     * <p>{@link Category#ADMIN} also matches documents written before the category field
     * existed: every one of those was an admin action, and leaving them out would make the
     * filtered export look like the trail began the day this shipped.
     */
    private static Bson exportFilter(String guildId, Category category) {
        Bson guildScope = Filters.eq("guildId", guildId);

        if (category == null)
            return guildScope;

        Bson categoryScope = category == Category.ADMIN
                ? Filters.or(Filters.eq("category", Category.ADMIN.name()),
                             Filters.exists("category", false))
                : Filters.eq("category", category.name());

        return Filters.and(guildScope, categoryScope);
    }

    private static String pad(String label) {
        return label.length() >= LABEL_WIDTH
                ? label
                : label + " ".repeat(LABEL_WIDTH - label.length());
    }

    /**
     * Renders this guild's audit trail as plain text.
     *
     * <p>Scoped to {@code guildId} and to the {@code audit_logs} collection this bot
     * owns - the cluster is shared, so nothing here reads or touches anything else.
     *
     * <p>Returns a string rather than writing a file: the caller uploads it straight
     * from a byte array, so nothing ever hits the host's flash storage.
     *
     * @return the rendered log, or null if the query failed.
     */
    public static String export(String guildId, String guildName) {
        return export(guildId, guildName, null);
    }

    /**
     * The same, narrowed to one kind of entry.
     *
     * <p>This exists because the trail is no longer admin-only. Leg transfers are written
     * by ordinary members and are far more frequent than moderation, so on a busy server
     * the newest {@value #EXPORT_LIMIT} entries can be almost entirely economy traffic -
     * and an admin exporting the log to check a moderation decision would find it pushed
     * off the end. The filter is applied in the query, not after it, so a narrowed export
     * gets a full {@value #EXPORT_LIMIT} entries of what was actually asked for.
     *
     * @param category the only kind to include, or null for everything
     */
    public static String export(String guildId, String guildName, Category category) {
        try {
            List<Document> entries = DB.getCollection(Document.class, COLLECTION)
                    .find(exportFilter(guildId, category))
                    .sort(Sorts.descending("createdAt"))
                    .limit(EXPORT_LIMIT)
                    .into(new ArrayList<>());

            StringBuilder out = new StringBuilder();
            out.append("BeastarsBot Audit Log\n")
                    .append("Server: ").append(guildName).append(" (").append(guildId).append(")\n")
                    .append("Showing: ")
                    .append(category == null ? "All entries" : category.title() + " entries")
                    .append("\n")
                    .append("Generated: ").append(EXPORT_STAMP.format(Instant.now())).append("\n")
                    .append("Entries: ").append(entries.size())
                    .append(entries.size() == EXPORT_LIMIT ? " (newest " + EXPORT_LIMIT + ")" : "")
                    .append("\n")
                    .append("=".repeat(72)).append("\n\n");

            if (entries.isEmpty()) {
                out.append("No matching audit entries recorded for this server yet.\n");
                return out.toString();
            }

            for (Document entry : entries) {
                Date when = entry.getDate("createdAt");
                Category entryCategory = Category.fromStored(entry.getString("category"));

                out.append("[").append(when == null ? "unknown" : EXPORT_STAMP.format(when.toInstant()))
                        .append("] ").append(entryCategory.title())
                        .append(" - ").append(entry.getString("action")).append("\n")
                        // Padded to the widest label the export can print, so the block stays
                        // aligned whichever label the category supplies.
                        .append("    ").append(pad(entryCategory.actorLabel())).append(": ")
                        .append(entry.getString("actorTag"))
                        .append(" (").append(entry.getString("actorId")).append(")\n");

                String target = entry.getString("target");
                if (target != null)
                    out.append("    ").append(pad("Target")).append(": ").append(target)
                            .append(" [").append(entry.getString("targetKind")).append("]\n");

                String details = entry.getString("details");
                if (details != null && !details.isBlank())
                    // Strip Discord markup so the plain-text file reads cleanly. Mentions
                    // become the bare id: <@123> is a link in Discord and noise in a text
                    // file, but the id itself is the part a reader needs.
                    out.append("    ").append(pad("Details")).append(": ")
                            .append(EXPORT_MENTION.matcher(details.replaceAll("[*`]", ""))
                                    .replaceAll("$1")
                                    .replace("\n", " "))
                            .append("\n");

                out.append("\n");
            }

            return out.toString();

        } catch (Exception e) {
            BunnyLog.error("[AuditService] Export failed for guild " + guildId, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Discord dispatch
    // ------------------------------------------------------------------

    private static void dispatch(Guild guild, User actor, MessageChannel fallback, Category category,
                                 String action, AuditTarget target, String details) {
        String channelId = GuildSettingsService.getLogChannelId(guild.getId());

        // Nothing configured: nudge, never publish. An audit entry names the target and
        // spells out what was done to them, so posting it into whatever channel the
        // admin happened to be standing in would broadcast moderation activity to
        // everyone there. The database already holds the record; the channel is a
        // convenience, and its absence is a configuration gap rather than a reason to
        // leak. The embed is not even built on this path.
        if (channelId == null) {
            if (category.notifiesOnBrokenDelivery())
                hintUnconfigured(guild, fallback);
            return;
        }

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) {
            if (category.notifiesOnBrokenDelivery())
                warnBroken(guild, actor, fallback, "the configured log channel no longer exists");
            return;
        }

        channel.sendMessageEmbeds(entryEmbed(actor, category, action, target, details)).queue(
                null,
                error -> {
                    // Deleted between the lookup and the send, or permissions revoked.
                    if (error instanceof ErrorResponseException) {
                        if (category.notifiesOnBrokenDelivery())
                            warnBroken(guild, actor, fallback,
                                    "I could not post to the configured log channel");
                        return;
                    }
                    BunnyLog.error("[AuditService] Log dispatch failed for guild " + guild.getId(), error);
                });
    }

    /**
     * Tells a guild once that audit logging is unconfigured.
     *
     * <p>Carries no actor, no target and no action - only the fact that a destination is
     * missing. Nothing about what was just done leaves the database.
     *
     * <p>Rate-limited to one notice per guild per {@value #HINT_INTERVAL_HOURS} hours:
     * a reminder that fires on every admin action stops being a reminder and becomes
     * noise that gets the bot muted.
     */
    private static void hintUnconfigured(Guild guild, MessageChannel fallback) {
        if (fallback == null || HINTED.getIfPresent(guild.getId()) != null)
            return;

        HINTED.put(guild.getId(), Boolean.TRUE);

        fallback.sendMessageEmbeds(new EmbedBuilder()
                        .setTitle("Audit Logging Not Configured")
                        .setDescription(BeastarsEmoji.CONFIRM
                                + " Admin actions are being recorded to the database, but no Discord"
                                + " log channel is set, so there is no readable trail here.\n\n"
                                + "Set one with `/admin logging set`, or export the history with"
                                + " `/admin logging export`.")
                        .setColor(AppDesign.ColorCodes.DEFAULT)
                        .setTimestamp(Instant.now())
                        .build())
                // Undo the claim if it never landed, so the guild still gets told.
                .queue(null, e -> HINTED.invalidate(guild.getId()));
    }

    /**
     * Tells the admin their logging is broken, in the channel they are actually looking at.
     *
     * <p>Silently swallowing this would leave an admin believing their audit trail is
     * being delivered when it is not. The Mongo record is already safe by this point, so
     * the message says so explicitly.
     *
     * <p>Like the unconfigured hint, this deliberately carries no action, target or
     * details - only that delivery failed. It mentions the actor because they are the
     * one who needs to fix it and they are already looking at this channel.
     *
     * <p>Rate-limited per guild: a deleted log channel would otherwise produce one of
     * these for every admin action until someone noticed.
     */
    private static void warnBroken(Guild guild, User actor, MessageChannel fallback, String problem) {
        // The console line sits under the same throttle as the Discord nudge. A guild
        // whose log channel was deleted hits this on every single admin action, and
        // repeating an unchanged configuration warning forever buries everything else.
        if (WARNED.getIfPresent(guild.getId()) != null)
            return;

        BunnyLog.warning("[AuditService] Log channel unusable for guild " + guild.getId()
                + ": " + problem + ". Further occurrences suppressed.");

        if (fallback == null)
            return;

        WARNED.put(guild.getId(), Boolean.TRUE);

        MessageEmbed embed = new EmbedBuilder()
                .setTitle("Audit Log Channel Unavailable")
                .setDescription(BeastarsEmoji.FAILURE + " " + problem + ".\n\n"
                        + "Your action was still recorded to the database. "
                        + "Set a new channel with `/admin logging set`.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();

        // The mention goes in the message content, not the embed. Discord does not fire
        // a push notification for a mention that only appears inside an embed, so the
        // previous version looked like a ping and silently wasn't one - which is the
        // worst possible outcome for a message whose entire job is to get noticed.
        fallback.sendMessage(actor.getAsMention())
                .addEmbeds(embed)
                .queue(null, e -> {
                    WARNED.invalidate(guild.getId());
                    BunnyLog.warning("[AuditService] Could not deliver the broken-log warning either.");
                });
    }

    private static MessageEmbed entryEmbed(User actor, Category category, String action,
                                           AuditTarget target, String details) {
        // Both parties in the first line the reader's eye lands on. The Target field
        // below carries the raw id for anyone who needs to copy it, but a field is
        // something you look up - the description is what the entry actually says, and
        // "who did it, and to whom" is the whole question an audit entry answers.
        String recipient = target.kind() == AuditTarget.Kind.USER && target.id() != null
                ? " on <@" + target.id() + ">"
                : "";

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(category.title() + ": " + action)
                .setDescription(BeastarsEmoji.PANEL + " " + actor.getAsMention()
                        + " performed **" + action + "**" + recipient + ".")
                .addField(category.actorLabel(), actor.getAsMention() + "\n`" + actor.getId() + "`", true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Audit Trail")
                .setTimestamp(Instant.now());

        String rendered = target.display();
        if (rendered != null)
            embed.addField("Target", rendered, true);

        if (details != null && !details.isBlank())
            embed.addField("Details", details, false);

        return embed.build();
    }
}
