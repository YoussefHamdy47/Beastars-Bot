package org.bunnys.beastars.commands.leg;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.conversions.Bson;
import org.bunnys.beastars.database.LegConfigData;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Per-guild configuration for the Leg economy: on/off, the new-member wait
 * period, and the three role categories.
 *
 * <p>Replaces the old read-only {@code LegConfigManager}, which cached reads but
 * left every write scattered across the button and modal handlers. Writes now
 * live here, and each one returns the fresh document straight from Mongo via
 * {@code findOneAndUpdate} - the previous "invalidate, then immediately re-read"
 * dance cost a guaranteed database round trip on every single toggle.
 *
 * <p>This config is on the hot path: {@link LegRuleEngine} consults it on every
 * {@code /leg} invocation, so the cache is what stands between a busy guild and
 * one Mongo read per command.
 */
public final class LegConfigService {

    public static final String COLLECTION = "Beastars Leg Config";

    private static final Cache<String, LegConfigData> CONFIGS = CacheRegistry.register("legconfig.configs", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .recordStats()
            .build());

    private LegConfigService() {}

    /** The three mutually-exclusive role buckets an admin can configure. */
    public enum RoleCategory {
        ALLOWED("allowed_roles", "allowedRoles", "Allowed Roles"),
        BANNED("banned_roles", "bannedRoles", "Banned Roles"),
        BYPASS("bypass_roles", "bypassWaitRoles", "Wait Bypass Roles");

        private final String actionId;
        private final String field;
        private final String label;

        RoleCategory(String actionId, String field, String label) {
            this.actionId = actionId;
            this.field = field;
            this.label = label;
        }

        public String actionId() { return actionId; }
        public String field() { return field; }
        public String label() { return label; }

        /** Resolves a dashboard button/modal action id, or null if it isn't one. */
        public static RoleCategory fromActionId(String actionId) {
            for (RoleCategory category : values())
                if (category.actionId.equals(actionId))
                    return category;
            return null;
        }
    }

    private static MongoCollection<LegConfigData> configs() {
        return DB.getCollection(LegConfigData.class, COLLECTION);
    }

    /**
     * Returns a guild's config, never null. A guild that has never been
     * configured gets the defaults from {@link LegConfigData}, cached like any
     * other value so unconfigured guilds cost nothing.
     */
    public static LegConfigData getConfig(String guildId) {
        LegConfigData cached = CONFIGS.get(guildId, k -> {
            try {
                LegConfigData stored = configs().find(Filters.eq("_id", k)).first();
                return stored != null ? stored : new LegConfigData(k);
            } catch (Exception e) {
                BunnyLog.error("[LegConfigService] Read failed for guild " + k, e);
                return null; // Keeps the failure out of the cache.
            }
        });

        return cached != null ? cached : new LegConfigData(guildId);
    }

    /** Flips the economy on or off. @return the new config, or null on failure. */
    public static LegConfigData setEnabled(String guildId, boolean enabled) {
        return apply(guildId, Updates.set("enabled", enabled));
    }

    /** Sets the hours a new member must wait. @return the new config, or null on failure. */
    public static LegConfigData setWaitPeriod(String guildId, int hours) {
        return apply(guildId, Updates.set("waitPeriodHours", hours));
    }

    /**
     * Replaces one role category and strips those roles from the other two.
     *
     * <p>The categories are mutually exclusive by design - a role that both grants
     * access and bans it is unanswerable - so all three arrays are written in a
     * single combined update rather than three separate round trips.
     *
     * @return the new config, or null on failure.
     */
    public static LegConfigData setRoles(String guildId, RoleCategory category, List<String> roleIds) {
        LegConfigData current = getConfig(guildId);

        List<String> allowed = new ArrayList<>(current.getAllowedRoles());
        List<String> banned = new ArrayList<>(current.getBannedRoles());
        List<String> bypass = new ArrayList<>(current.getBypassWaitRoles());

        switch (category) {
            case ALLOWED -> {
                allowed = roleIds;
                banned.removeAll(roleIds);
                bypass.removeAll(roleIds);
            }
            case BANNED -> {
                banned = roleIds;
                allowed.removeAll(roleIds);
                bypass.removeAll(roleIds);
            }
            case BYPASS -> {
                bypass = roleIds;
                allowed.removeAll(roleIds);
                banned.removeAll(roleIds);
            }
        }

        return apply(guildId, Updates.combine(
                Updates.set(RoleCategory.ALLOWED.field(), allowed),
                Updates.set(RoleCategory.BANNED.field(), banned),
                Updates.set(RoleCategory.BYPASS.field(), bypass)));
    }

    /** Roles whose holders are hidden from the leaderboard. Display only; stats are untouched. */
    public static LegConfigData setLeaderboardHiddenRoles(String guildId, List<String> roleIds) {
        return apply(guildId, Updates.set("leaderboardHiddenRoles", roleIds));
    }

    public static LegConfigData setHideBannedOnLeaderboard(String guildId, boolean hide) {
        return apply(guildId, Updates.set("hideBannedOnLeaderboard", hide));
    }

    public static LegConfigData setHideDepartedOnLeaderboard(String guildId, boolean hide) {
        return apply(guildId, Updates.set("hideDepartedOnLeaderboard", hide));
    }

    /** Runs an update and seeds the cache with the post-image it returns. */
    private static LegConfigData apply(String guildId, Bson update) {
        try {
            LegConfigData updated = configs().findOneAndUpdate(
                    Filters.eq("_id", guildId),
                    update,
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

            // The leaderboard is cached and its query now depends on this config, so a
            // settings change has to drop it or the board keeps answering from the old
            // rules until its own TTL happens to run out.
            LegService.invalidateLeaderboard(guildId);

            if (updated == null) {
                CONFIGS.invalidate(guildId);
                return getConfig(guildId);
            }

            CONFIGS.put(guildId, updated);
            return updated;
        } catch (Exception e) {
            BunnyLog.error("[LegConfigService] Write failed for guild " + guildId, e);
            CONFIGS.invalidate(guildId);
            return null;
        }
    }
}
