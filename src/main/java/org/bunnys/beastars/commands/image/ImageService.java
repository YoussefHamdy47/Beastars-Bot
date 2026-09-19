package org.bunnys.beastars.commands.image;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.bunnys.beastars.database.ImageData;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Custom per-guild image shortcuts: storage, caching and validation.
 *
 * <h2>Why this was rewritten</h2>
 * Every add and remove used to pull the guild's <em>entire</em> image list into memory,
 * mutate the Java object, and write the whole document back with {@code replaceOne}.
 * With two admins editing at once the second write silently discarded the first, and a
 * guild with hundreds of shortcuts paid the full document size on every single edit.
 *
 * <p>All three mutations are now targeted array operators - {@code $push},
 * {@code $pull}, and a positional {@code $set} through an array filter - so each one
 * touches exactly the element it means to and concurrent edits compose instead of
 * clobbering.
 *
 * <p>The hand-rolled {@code LinkedHashMap} LRU is now Caffeine, which brings a size
 * bound the old map had (100 entries) plus the expiry it did not: a stale guild used to
 * sit in memory forever.
 */
public final class ImageService {

    public static final String COLLECTION = "Beastars Images";

    private static final String GUILD_FIELD = "guildID";
    private static final String IMAGES_FIELD = "images";

    /** Direct image links, or a Discord CDN attachment. */
    private static final Pattern IMAGE_URL = Pattern.compile(
            "^(https?://.*\\.(?:png|jpg|jpeg|gif|svg|webp)(?:\\?.*)?)$|"
                    + "^https://(cdn|media)\\.discordapp\\.(com|net)/attachments/\\d+/\\d+/.+$",
            Pattern.CASE_INSENSITIVE);

    private static final Cache<String, ImageData> GUILDS = CacheRegistry.register("image.guilds", Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(6, TimeUnit.HOURS)
            .recordStats()
            .build());

    private ImageService() {}

    /** What happened to a shortcut. */
    public enum Outcome { ADDED, UPDATED, REMOVED, NOT_FOUND, INVALID_URL, FAILED }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** A guild's shortcuts, never null. Served from Caffeine after the first read. */
    public static List<ImageData.ImageEntry> list(String guildId) {
        ImageData data = load(guildId);
        List<ImageData.ImageEntry> images = data.getImages();
        return images != null ? images : List.of();
    }

    /** Case-insensitive lookup, the way users actually type shortcut names. */
    public static Optional<ImageData.ImageEntry> find(String guildId, String name) {
        String target = name.trim();
        return list(guildId).stream()
                .filter(entry -> entry.getName() != null && entry.getName().equalsIgnoreCase(target))
                .findFirst();
    }

    /** Shortcut names matching an autocomplete prefix, capped at Discord's limit. */
    public static List<String> suggest(String guildId, String prefix) {
        String query = prefix.toLowerCase();
        return list(guildId).stream()
                .map(ImageData.ImageEntry::getName)
                .filter(name -> name != null && name.toLowerCase().startsWith(query))
                .limit(25)
                .toList();
    }

    private static ImageData load(String guildId) {
        ImageData cached = GUILDS.get(guildId, k -> {
            try {
                ImageData stored = DB.findOne(ImageData.class, COLLECTION, Filters.eq(GUILD_FIELD, k));
                if (stored == null)
                    return new ImageData(k, new ArrayList<>());
                if (stored.getImages() == null)
                    stored.setImages(new ArrayList<>());
                return stored;
            } catch (Exception e) {
                BunnyLog.error("[ImageService] Read failed for guild " + k, e);
                return null; // Keeps the failure out of the cache.
            }
        });

        return cached != null ? cached : new ImageData(guildId, new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Adds a shortcut, or repoints an existing one with the same name.
     *
     * @return {@link Outcome#ADDED}, {@link Outcome#UPDATED}, {@link Outcome#INVALID_URL}
     *         or {@link Outcome#FAILED}.
     */
    public static Outcome save(String guildId, String name, String url) {
        String cleanName = name.trim();
        String cleanUrl = url.trim();

        if (!IMAGE_URL.matcher(cleanUrl).matches())
            return Outcome.INVALID_URL;

        Optional<ImageData.ImageEntry> existing = find(guildId, cleanName);

        try {
            if (existing.isPresent()) {
                // Positional update through an array filter: rewrites one URL rather
                // than the whole array, and matches the stored casing exactly.
                DB.getCollection(ImageData.class, COLLECTION).updateOne(
                        Filters.eq(GUILD_FIELD, guildId),
                        Updates.set(IMAGES_FIELD + ".$[entry].URL", cleanUrl),
                        new UpdateOptions().arrayFilters(
                                List.of(new Document("entry.name", existing.get().getName()))));

                GUILDS.invalidate(guildId);
                return Outcome.UPDATED;
            }

            DB.getCollection(ImageData.class, COLLECTION).updateOne(
                    Filters.eq(GUILD_FIELD, guildId),
                    Updates.combine(
                            Updates.push(IMAGES_FIELD, newEntry(cleanName, cleanUrl)),
                            Updates.setOnInsert(GUILD_FIELD, guildId)),
                    new UpdateOptions().upsert(true));

            GUILDS.invalidate(guildId);
            return Outcome.ADDED;

        } catch (Exception e) {
            BunnyLog.error("[ImageService] Save failed for '" + cleanName + "' in guild " + guildId, e);
            GUILDS.invalidate(guildId);
            return Outcome.FAILED;
        }
    }

    /** @return {@link Outcome#REMOVED}, {@link Outcome#NOT_FOUND} or {@link Outcome#FAILED}. */
    public static Outcome remove(String guildId, String name) {
        Optional<ImageData.ImageEntry> existing = find(guildId, name.trim());
        if (existing.isEmpty())
            return Outcome.NOT_FOUND;

        try {
            // Pull by the stored name so the case-insensitive lookup above stays the
            // single place casing is reasoned about.
            DB.getCollection(ImageData.class, COLLECTION).updateOne(
                    Filters.eq(GUILD_FIELD, guildId),
                    Updates.pull(IMAGES_FIELD, new Document("name", existing.get().getName())));

            GUILDS.invalidate(guildId);
            return Outcome.REMOVED;
        } catch (Exception e) {
            BunnyLog.error("[ImageService] Remove failed for '" + name + "' in guild " + guildId, e);
            GUILDS.invalidate(guildId);
            return Outcome.FAILED;
        }
    }

    /**
     * Builds the sub-document by hand rather than encoding the POJO.
     *
     * <p>The stored shape came from Mongoose and uses {@code URL} in caps; spelling the
     * field names out here means the update operators cannot drift from the schema the
     * legacy documents actually have.
     */
    private static Document newEntry(String name, String url) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        String imageId = slug + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(0xFFFFFF));

        return new Document("_id", new ObjectId())
                .append("name", name)
                .append("imageID", imageId)
                .append("URL", url);
    }
}
