package org.bunnys.beastars.commands.leg;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The role filter currently applied to one leaderboard message.
 *
 * <h2>Why this is not in the component id</h2>
 * Every other piece of leaderboard state - page, caller - rides in the button's custom
 * id, which is the pattern the rest of the bot uses. Roles cannot: Discord caps a custom
 * id at 100 characters and a role snowflake is nineteen of them, so
 * {@code leg_lb:next:2:<caller>} runs out of room after two or three roles. Filtering by
 * four roles would silently produce an id Discord rejects, and the button would simply
 * stop working.
 *
 * <p>So the filter lives here, keyed by the message it belongs to. That also makes it
 * per-message rather than per-user, which is the behaviour people expect: two
 * leaderboards open in a channel filter independently.
 *
 * <h2>Bounds</h2>
 * Caffeine with a size cap and an expiry, per the house rule - this is view state, not a
 * record of anything. A filter outliving the conversation it belongs to is worth nothing,
 * so it expires well before the message stops being interesting, and an expired entry
 * simply reads as "no filter" rather than as an error.
 */
public final class LegLeaderboardFilters {

    /** Long enough to page through a board, short enough that stale views clear themselves. */
    private static final long EXPIRY_MINUTES = 30L;

    private static final Cache<String, List<String>> FILTERS = CacheRegistry.register("legfilter.filters", Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterAccess(EXPIRY_MINUTES, TimeUnit.MINUTES)
            .recordStats()
            .build());

    private LegLeaderboardFilters() {}

    /** The roles filtering this message, or an empty list when it is showing everyone. */
    public static List<String> get(String messageId) {
        if (messageId == null)
            return List.of();

        List<String> roles = FILTERS.getIfPresent(messageId);
        return roles == null ? List.of() : roles;
    }

    /** Replaces the filter. An empty or null list clears it rather than storing nothing. */
    public static void set(String messageId, List<String> roleIds) {
        if (messageId == null)
            return;

        if (roleIds == null || roleIds.isEmpty()) {
            FILTERS.invalidate(messageId);
            return;
        }

        FILTERS.put(messageId, List.copyOf(roleIds));
    }

    public static void clear(String messageId) {
        if (messageId != null)
            FILTERS.invalidate(messageId);
    }
}
