package org.bunnys.beastars.commands.leg;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.beastars.commands.leg.LegService.Leaderboard;
import org.bunnys.handler.metrics.CacheRegistry;

import java.util.concurrent.TimeUnit;

/**
 * The ranked board pinned to one open leaderboard message.
 *
 * <h2>Why a leaderboard freezes</h2>
 * The board underneath a reader moves. Somebody earns a leg on page three while you are
 * reading page one, everything below them shifts by a place, and your next click shows a
 * page that never existed as a whole: a member who was on page one appears again on page
 * two, and whoever was last on page two is skipped entirely. Nothing is wrong with the
 * data, but the thing on screen is not a leaderboard, it is two half-leaderboards from
 * different moments stitched together.
 *
 * <p>Pinning the board the message opened with makes every page turn a view of one
 * consistent ranking. The Update button then does the refresh explicitly, at a moment the
 * reader chose, which is the only moment where a jump in the numbers reads as new
 * information rather than as a bug.
 *
 * <h2>Pinning is nearly free</h2>
 * {@link Leaderboard} is immutable and {@link LegService} already rebuilds it at most once
 * per cache window per guild, so this stores a <em>reference</em> to an object that
 * already exists. Every message opened inside the same window shares one board; the cost
 * is keeping an old one alive after the guild cache has moved on, which is bounded by the
 * cache below.
 *
 * <h2>Keyed by session, not by message</h2>
 * The message id is not known when the reply is built, so this uses the same session key
 * the buttons carry - the caller's id plus the moment the board was opened. That is the
 * pattern the manga reader already uses, and it means two people opening a board in the
 * same channel hold two independent snapshots.
 */
public final class LegLeaderboardSnapshots {

    /**
     * Long enough to read a board through, short enough that abandoned ones let go of
     * their entries. An expired snapshot is not an error: the handler simply takes a
     * fresh one and says so in the footer.
     */
    private static final long EXPIRY_MINUTES = 30L;

    /**
     * Bounded by open boards rather than by guilds. Most entries point at a board some
     * other entry also points at, so this holds far fewer distinct rankings than it does
     * keys.
     *
     * <p>Sized against the heap rather than against expected use. This is the one cache in
     * the bot that retains a whole ranking, and the worst case is every key pointing at a
     * different one: {@value LegService#LEADERBOARD_MAX_ENTRIES} entries each, on a host
     * running with a few hundred megabytes. Two hundred simultaneously-active boards is
     * already far past anything five servers will produce, and it keeps that worst case in
     * single-digit megabytes.
     */
    private static final Cache<String, Snapshot> SNAPSHOTS = CacheRegistry.register("leg.lb_snapshots",
            Caffeine.newBuilder()
                    .maximumSize(200)
                    .expireAfterAccess(EXPIRY_MINUTES, TimeUnit.MINUTES)
                    .recordStats()
                    .build());

    private LegLeaderboardSnapshots() {}

    /**
     * A pinned ranking and the moment it was taken.
     *
     * @param takenAtEpochSeconds shown to the reader as live markup, so a board that has
     *                            been open a while says so itself
     */
    private record Snapshot(Leaderboard board, long takenAtEpochSeconds) {}

    /** The key carried in every button on one open board. */
    public static String key(String callerId, long openedAtMillis) {
        return callerId + ":" + openedAtMillis;
    }

    /** The pinned board, or null when nothing is pinned or it has expired. */
    private static Snapshot get(String key) {
        return key == null ? null : SNAPSHOTS.getIfPresent(key);
    }

    /** Pins a board and returns the snapshot, so {@link #view} can render its timestamp. */
    private static Snapshot pin(String key, Leaderboard board) {
        Snapshot snapshot = new Snapshot(board, System.currentTimeMillis() / 1000L);

        if (key != null && board != null)
            SNAPSHOTS.put(key, snapshot);

        return snapshot;
    }

    public static void clear(String key) {
        if (key != null)
            SNAPSHOTS.invalidate(key);
    }

    /**
     * A page of the board plus the stamp to render alongside it.
     *
     * @param snapshotAtEpochSeconds when the pinned ranking was taken, or 0 for a live board
     */
    public record View(LegService.LeaderboardPage page, long snapshotAtEpochSeconds) {}

    /**
     * One page of a board, frozen or live, whichever this session is.
     *
     * <p>The single place that decides which. Both the command's first render and every
     * button click go through it, so the two cannot disagree about what "frozen" means or
     * drift on how an expired pin is handled.
     *
     * <p>An expired or missing pin is silently re-taken rather than treated as an error.
     * A board that stops working half an hour after it was posted is worse than one that
     * re-freezes and reports its new stamp, which the embed does.
     */
    public static View view(LegComponents.Session session, String guildId, int requestedPage,
                            String highlightUserId,
                            java.util.function.Predicate<String> userFilter) {

        if (!session.frozen())
            return new View(
                    LegService.leaderboardPage(guildId, requestedPage, highlightUserId, userFilter), 0L);

        Snapshot snapshot = get(session.snapshotKey());
        if (snapshot == null)
            snapshot = pin(session.snapshotKey(), LegService.currentBoard(guildId));

        return new View(
                LegService.pageOf(snapshot.board(), requestedPage, highlightUserId, userFilter),
                snapshot.takenAtEpochSeconds());
    }
}
