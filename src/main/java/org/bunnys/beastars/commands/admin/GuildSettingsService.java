package org.bunnys.beastars.commands.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;

import java.util.concurrent.TimeUnit;

/**
 * Guild-level bot settings that are not tied to any one feature.
 *
 * <p>Two channels live here: the audit log channel that {@link AuditService} writes
 * administrative actions to, and the error log channel that
 * {@link org.bunnys.utils.ErrorReporter} writes crashes to. Both are bot infrastructure
 * rather than economy configuration, which is why neither belongs in
 * {@code LegConfigService}.
 *
 * <p>Caffeine sits in front because the audit read happens on the hot path of every
 * admin mutation and the crash read happens on every unhandled exception, while the
 * answers change perhaps once in a guild's lifetime. Both fields are cached together,
 * as one document read, so a guild costs one round trip rather than two.
 */
public final class GuildSettingsService {

    public static final String COLLECTION = "guild_settings";

    private static final String LOG_CHANNEL_FIELD = "logChannelId";
    private static final String ERROR_CHANNEL_FIELD = "errorChannelId";

    /** A sentinel for "no channel configured", so absence can be cached like any value. */
    private static final String NONE = "";

    /**
     * One entry per guild, holding every setting in the document.
     *
     * <p>Fields are the sentinel rather than null when unset, so "configured as nothing"
     * and "not yet read" stay distinguishable - the loader returns null on failure, and
     * only that null keeps a database error out of the cache.
     */
    private record Settings(String logChannelId, String errorChannelId) {}

    private static final Cache<String, Settings> SETTINGS = CacheRegistry.register("settings.settings", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(6, TimeUnit.HOURS)
            .recordStats()
            .build());

    private GuildSettingsService() {}

    private static Settings load(String guildId) {
        return SETTINGS.get(guildId, k -> {
            try {
                Document doc = DB.getCollection(Document.class, COLLECTION)
                        .find(Filters.eq("_id", k))
                        .first();

                if (doc == null)
                    return new Settings(NONE, NONE);

                return new Settings(
                        doc.getString(LOG_CHANNEL_FIELD) == null ? NONE : doc.getString(LOG_CHANNEL_FIELD),
                        doc.getString(ERROR_CHANNEL_FIELD) == null ? NONE : doc.getString(ERROR_CHANNEL_FIELD));
            } catch (Exception e) {
                BunnyLog.error("[GuildSettingsService] Read failed for guild " + k, e);
                return null; // Keeps the failure out of the cache.
            }
        });
    }

    private static String orNull(String value) {
        return (value == null || value.equals(NONE)) ? null : value;
    }

    /** The configured audit log channel id, or null when logging is not set up. */
    public static String getLogChannelId(String guildId) {
        Settings settings = load(guildId);
        return settings == null ? null : orNull(settings.logChannelId());
    }

    /** The configured error log channel id, or null when crash reporting is not set up. */
    public static String getErrorChannelId(String guildId) {
        Settings settings = load(guildId);
        return settings == null ? null : orNull(settings.errorChannelId());
    }

    /**
     * Sets or clears the audit log channel.
     *
     * @param channelId the channel, or null to disable Discord logging entirely.
     * @return true when the write landed.
     */
    public static boolean setLogChannel(String guildId, String channelId) {
        return update(guildId, LOG_CHANNEL_FIELD, channelId);
    }

    /**
     * Sets or clears the error log channel.
     *
     * @param channelId the channel, or null to stop sending this guild's crashes anywhere.
     * @return true when the write landed.
     */
    public static boolean setErrorChannel(String guildId, String channelId) {
        return update(guildId, ERROR_CHANNEL_FIELD, channelId);
    }

    /**
     * One field, one round trip, cache refreshed from the post-image.
     *
     * <p>{@code findOneAndUpdate} with {@code ReturnDocument.AFTER} means the cache is
     * seeded from what the database actually holds rather than from what this method was
     * asked to write - so a concurrent change to the other field is picked up here
     * instead of being clobbered in the cache until the next expiry.
     */
    private static boolean update(String guildId, String field, String channelId) {
        try {
            Document after = DB.getCollection(Document.class, COLLECTION).findOneAndUpdate(
                    Filters.eq("_id", guildId),
                    channelId == null ? Updates.unset(field) : Updates.set(field, channelId),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

            if (after == null) {
                SETTINGS.invalidate(guildId);
                return true;
            }

            SETTINGS.put(guildId, new Settings(
                    after.getString(LOG_CHANNEL_FIELD) == null ? NONE : after.getString(LOG_CHANNEL_FIELD),
                    after.getString(ERROR_CHANNEL_FIELD) == null ? NONE : after.getString(ERROR_CHANNEL_FIELD)));
            return true;
        } catch (Exception e) {
            BunnyLog.error("[GuildSettingsService] Failed to set " + field + " for guild " + guildId, e);
            SETTINGS.invalidate(guildId);
            return false;
        }
    }
}
