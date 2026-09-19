package org.bunnys.handler.router;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;

import java.util.concurrent.TimeUnit;

/**
 * Per-user click throttling shared by every component router.
 *
 * <p>Extracted from {@code ButtonRouter} when select menus gained a router of their own:
 * the policy belongs to the handler, but the bookkeeping is identical whatever kind of
 * component is being throttled, and two copies of a compare-and-set would eventually
 * drift apart.
 *
 * <p>What is stored is the UNIX timestamp of the last accepted interaction, keyed by
 * {@code prefix:userId}. Remaining time is derived by subtraction at click time, so a
 * rejection can state exactly how long is left rather than restating the configured
 * duration. Keying by prefix rather than globally means a cooldown on one component
 * never blocks a different one.
 */
public final class ComponentCooldowns {

    /**
     * The longest cooldown this class can actually enforce.
     *
     * <p>An evicted entry reads as "never used", so a claim can only be held for as long
     * as its timestamp survives in the cache below. That used to be an implicit
     * relationship, documented as "it only has to outlive the longest cooldown any handler
     * declares" and satisfied by every handler declaring a compile-time constant of a few
     * seconds.
     *
     * <p>It stopped being satisfied the moment a cooldown became a value a server
     * administrator types in: the OOC reroll cooldown accepts up to an hour, and against a
     * five-minute window anything above five minutes was silently enforced as five. The
     * admin set ten minutes, the panel confirmed ten minutes, and members rerolled every
     * five, with nothing anywhere reporting a problem.
     *
     * <p>So the window is now stated as a limit rather than left as an assumption, sits
     * comfortably above the largest configurable cooldown in the bot, and {@link #claim}
     * clamps anything longer instead of under-enforcing it quietly.
     */
    public static final long MAX_ENFORCEABLE_MILLIS = TimeUnit.HOURS.toMillis(2);

    /**
     * Correctness comes from the stored timestamp; the expiry bounds memory <em>and</em>
     * caps how long a claim can be held. See {@link #MAX_ENFORCEABLE_MILLIS}.
     */
    private static final Cache<String, Long> LAST_USE = CacheRegistry.register("component.last_use", Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(MAX_ENFORCEABLE_MILLIS, TimeUnit.MILLISECONDS)
            .recordStats()
            .build());

    private ComponentCooldowns() {}

    /**
     * Attempts to claim an interaction slot.
     *
     * <p>Atomic on purpose: a double-click delivers two interactions near-simultaneously,
     * and a read-then-write would let both observe an empty cache and both proceed -
     * exactly the race a cooldown on a destructive action exists to prevent.
     *
     * @return milliseconds still to wait, or 0 when the interaction is allowed
     */
    public static long claim(String prefix, String userId, long cooldownMillis) {
        if (cooldownMillis <= 0)
            return 0; // No policy: no cache traffic at all.

        // Clamped rather than honoured-then-lost. A caller asking for longer than the
        // cache can hold would otherwise be told the claim succeeded and then have it
        // expire early, which is the failure that is impossible to see from the outside.
        long enforceable = Math.min(cooldownMillis, MAX_ENFORCEABLE_MILLIS);

        long now = System.currentTimeMillis();
        long[] remaining = {0};

        LAST_USE.asMap().compute(prefix + ":" + userId, (key, lastUse) -> {
            if (lastUse == null) {
                remaining[0] = 0;
                return now;
            }

            long elapsed = now - lastUse;
            if (elapsed >= enforceable) {
                remaining[0] = 0;
                return now;
            }

            remaining[0] = enforceable - elapsed;
            return lastUse; // Keep the original deadline; a spammer cannot extend it.
        });

        return remaining[0];
    }

    /** Gives a claim back, for an interaction that was accepted but never actually ran. */
    public static void release(String prefix, String userId) {
        LAST_USE.invalidate(prefix + ":" + userId);
    }
}
