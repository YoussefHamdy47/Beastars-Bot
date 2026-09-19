package org.bunnys.beastars.commands.ooc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.bson.Document;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's OOC image album: link storage, photo caching and the reroll cooldown.
 *
 * <p>Named for what members use it for rather than for Imgur, which is only where the
 * pictures happen to be hosted. The collection keeps its original name so existing
 * documents are still found.
 *
 * <p>Three hand-rolled caches - a {@code ConcurrentHashMap} for links, a synchronized
 * {@code LinkedHashMap} LRU for photos, and another map for refresh cooldowns - are now
 * one Caffeine cache each. The link map in particular was unbounded and never expired.
 *
 * <p>The album link is stored with a targeted {@code $set} upsert instead of a
 * whole-document replace, and the {@code ImgurData} POJO it needed is gone: a
 * single-field document does not earn a codec.
 */
public final class OocService {

    public static final String COLLECTION = "Beastars Imgur Data";

    /**
     * The album a guild falls back to before an admin sets its own.
     *
     * <p>Configured rather than compiled in: it is somebody's album, and a published
     * repository should not name it. Unset simply means a guild must run
     * {@code /ooc setlink} before /ooc get works, which it reports cleanly.
     */
    public static final String DEFAULT_ALBUM = TokenLoader.optional("IMGUR_DEFAULT_ALBUM");

    private static final String LINK_FIELD = "imgurLink";
    private static final String REROLL_COOLDOWN_FIELD = "rerollCooldownSeconds";

    /** How long a member waits between presses of the reroll button, before an admin changes it. */
    public static final int DEFAULT_REROLL_COOLDOWN_SECONDS = 30;

    /** Zero is allowed and means no throttle at all. */
    public static final int MIN_REROLL_COOLDOWN_SECONDS = 0;

    /** An hour is already far past "throttle" and into "disabled"; beyond it is a typo. */
    public static final int MAX_REROLL_COOLDOWN_SECONDS = 3600;

    private static final Pattern ALBUM_URL = Pattern.compile(
            "^(?:https?://)?(?:(?:www|i|m)\\.)?imgur\\.com/(?:a/|gallery/)?([a-zA-Z0-9]{5,7})/?$");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * The guild's album link and its reroll cooldown, cached together.
     *
     * <p>Both live in the same document and both are wanted on the same click: the button
     * needs the cooldown to decide whether to run, and the run needs the link. Caching
     * them as one value keeps that one Mongo read rather than two, the way
     * {@code GuildSettingsService} pairs its two channels.
     */
    private static final Cache<String, Settings> GUILD_SETTINGS = CacheRegistry.register("ooc.guild_settings", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(6, TimeUnit.HOURS)
            .recordStats()
            .build());

    /** Album contents change rarely; a day's TTL keeps us far under Imgur's rate limit. */
    private static final Cache<String, List<String>> ALBUM_PHOTOS = CacheRegistry.register("ooc.album_photos", Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .recordStats()
            .build());

    private static final long REFRESH_COOLDOWN_HOURS = 6;

    /** Caffeine expiry *is* the cooldown: presence of a key means "too soon". */
    private static final Cache<String, Long> REFRESH_COOLDOWNS = CacheRegistry.register("ooc.refresh_cooldowns", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(REFRESH_COOLDOWN_HOURS, TimeUnit.HOURS)
            .recordStats()
            .build());

    private OocService() {}

    public enum Outcome { OK, INVALID_URL, ON_COOLDOWN, EMPTY_ALBUM, FAILED }

    public record RandomImage(String url, Outcome outcome) {

        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * One guild's stored configuration.
     *
     * @param albumLink        the album URL, already defaulted; may be null if nothing is configured anywhere
     * @param rerollCooldownSeconds seconds between reroll presses, per member
     */
    public record Settings(String albumLink, int rerollCooldownSeconds) {

        /**
         * True when this guild never set an album of its own and is riding the bot-wide
         * default.
         *
         * <p>Has to be derived by comparison rather than read off a field: the loader has
         * already substituted the default, so the record alone cannot say which of the
         * two it is holding. Lives here so the answer is computed once rather than
         * re-derived by each panel that wants to say "configured" or "default".
         */
        public boolean usingDefaultAlbum() {
            return albumLink != null && albumLink.equals(DEFAULT_ALBUM);
        }
    }

    /** Everything stored for a guild, never null. */
    public static Settings settings(String guildId) {
        Settings cached = GUILD_SETTINGS.get(guildId, k -> {
            try {
                Document doc = DB.getCollection(Document.class, COLLECTION)
                        .find(Filters.or(Filters.eq("_id", k), Filters.eq("guildID", k)))
                        .first();

                return doc == null ? defaults() : fromDocument(doc);
            } catch (Exception e) {
                BunnyLog.error("[OocService] Read failed for guild " + k, e);
                return null; // Keeps the failure out of the cache.
            }
        });

        return cached != null ? cached : defaults();
    }

    private static Settings defaults() {
        return new Settings(DEFAULT_ALBUM, DEFAULT_REROLL_COOLDOWN_SECONDS);
    }

    private static Settings fromDocument(Document doc) {
        String link = doc.getString(LINK_FIELD);
        Integer stored = doc.getInteger(REROLL_COOLDOWN_FIELD);

        return new Settings(
                link != null ? link : DEFAULT_ALBUM,
                stored == null ? DEFAULT_REROLL_COOLDOWN_SECONDS : clampCooldown(stored));
    }

    /** Keeps a hand-edited or legacy value inside the range the admin panel enforces. */
    public static int clampCooldown(int seconds) {
        return Math.max(MIN_REROLL_COOLDOWN_SECONDS, Math.min(MAX_REROLL_COOLDOWN_SECONDS, seconds));
    }

    /** The guild's album URL, falling back to the shared default. */
    public static String albumLink(String guildId) {
        return settings(guildId).albumLink();
    }

    /** Seconds a member must wait between presses of the reroll button. */
    public static int rerollCooldownSeconds(String guildId) {
        return settings(guildId).rerollCooldownSeconds();
    }

    /**
     * Stores a new reroll cooldown.
     *
     * @return the stored settings, or null when the write failed.
     */
    public static Settings setRerollCooldown(String guildId, int seconds) {
        int value = clampCooldown(seconds);

        try {
            Document after = DB.getCollection(Document.class, COLLECTION).findOneAndUpdate(
                    Filters.eq("_id", guildId),
                    Updates.combine(
                            Updates.set(REROLL_COOLDOWN_FIELD, value),
                            Updates.setOnInsert("guildID", guildId)),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

            Settings stored = after == null ? new Settings(albumLink(guildId), value) : fromDocument(after);
            GUILD_SETTINGS.put(guildId, stored);
            return stored;
        } catch (Exception e) {
            BunnyLog.error("[OocService] Failed to set the reroll cooldown for guild " + guildId, e);
            GUILD_SETTINGS.invalidate(guildId);
            return null;
        }
    }

    /**
     * Points the guild at a new album.
     *
     * <p>Unlike the old version this no longer writes a default document on read - a
     * guild that never configured anything simply uses the default in memory, so
     * viewing an album stopped being a write.
     */
    public static Outcome setAlbumLink(String guildId, String url) {
        Matcher matcher = ALBUM_URL.matcher(url.trim());
        if (!matcher.find())
            return Outcome.INVALID_URL;

        try {
            DB.getCollection(Document.class, COLLECTION).updateOne(
                    Filters.eq("_id", guildId),
                    Updates.combine(
                            Updates.set(LINK_FIELD, url.trim()),
                            Updates.setOnInsert("guildID", guildId)),
                    new UpdateOptions().upsert(true));

            // Reread rather than patching one field into a stale pair: the cooldown may
            // have changed since this entry was cached.
            GUILD_SETTINGS.invalidate(guildId);
            ALBUM_PHOTOS.invalidate(matcher.group(1));
            return Outcome.OK;
        } catch (Exception e) {
            BunnyLog.error("[OocService] Failed to set album link for guild " + guildId, e);
            GUILD_SETTINGS.invalidate(guildId);
            return Outcome.FAILED;
        }
    }

    /** Drops the cached photo list so the next request re-reads Imgur. */
    public static Outcome refresh(String guildId) {
        if (REFRESH_COOLDOWNS.getIfPresent(guildId) != null)
            return Outcome.ON_COOLDOWN;

        Matcher matcher = ALBUM_URL.matcher(albumLink(guildId));
        if (!matcher.find())
            return Outcome.INVALID_URL;

        ALBUM_PHOTOS.invalidate(matcher.group(1));
        REFRESH_COOLDOWNS.put(guildId, System.currentTimeMillis());
        return Outcome.OK;
    }

    public static long refreshCooldownHours() {
        return REFRESH_COOLDOWN_HOURS;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public static RandomImage randomImage(String guildId) {
        Matcher matcher = ALBUM_URL.matcher(albumLink(guildId));
        if (!matcher.find())
            return new RandomImage(null, Outcome.INVALID_URL);

        String albumId = matcher.group(1);

        try {
            List<String> photos = ALBUM_PHOTOS.get(albumId, OocService::fetchAlbum);

            if (photos == null)
                return new RandomImage(null, Outcome.FAILED);
            if (photos.isEmpty())
                return new RandomImage(null, Outcome.EMPTY_ALBUM);

            return new RandomImage(photos.get(ThreadLocalRandom.current().nextInt(photos.size())), Outcome.OK);
        } catch (Exception e) {
            BunnyLog.error("[OocService] Random image failed for album " + albumId, e);
            return new RandomImage(null, Outcome.FAILED);
        }
    }

    /** Returns null on failure so a transient Imgur outage is not cached for a day. */
    private static List<String> fetchAlbum(String albumId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.imgur.com/3/album/" + albumId + "/images"))
                    .header("Authorization", "Client-ID " + TokenLoader.optional("ImgurKey"))
                    .header("User-Agent", "BeastarsBot/4.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                BunnyLog.warning("[OocService] Imgur API returned HTTP " + response.statusCode()
                        + " for album " + albumId);
                return null;
            }

            DataArray data = DataObject.fromJson(response.body()).getArray("data");
            List<String> links = new ArrayList<>(data.length());
            for (int i = 0; i < data.length(); i++)
                links.add(data.getObject(i).getString("link"));

            return links;
        } catch (Exception e) {
            BunnyLog.error("[OocService] Album fetch failed for " + albumId, e);
            return null;
        }
    }
}
