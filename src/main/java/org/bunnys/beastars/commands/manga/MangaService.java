package org.bunnys.beastars.commands.manga;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.bunnys.beastars.database.MangaChapterData;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single owner of manga chapter data: remote fetching, MongoDB persistence and
 * caching.
 *
 * <p>Split out of the old {@code MangaLogic}, which fetched from three upstreams,
 * wrote to Mongo, held two caches <em>and</em> built the embed. Rendering now lives in
 * {@link MangaEmbeds} and the source catalogue in {@link MangaCatalog}.
 *
 * <h2>Caching, and why it differs by source</h2>
 * Caffeine in front, MongoDB behind, and only then the upstream API. Caffeine's loader
 * runs once per key even under concurrent misses, which is what stops a popular chapter
 * from firing a burst of identical MangaDex calls when a server wakes up.
 *
 * <p>What is cached, and for how long, is <em>not</em> uniform: a chapter's page list is
 * only as durable as the URLs in it. Google Drive URLs are built from permanent file ids,
 * so those are cached for a day and written through to MongoDB. MangaDex URLs carry a host
 * assignment that the API guarantees for fifteen minutes, so those are held briefly in
 * memory and never persisted at all. Treating the two alike is what previously left dead
 * URLs in the database indefinitely - see {@link #MANGADEX_PAGE_TTL} and
 * {@link #loadPages}.
 */
public final class MangaService {

    public static final String COLLECTION = "Manga Chapters";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String USER_AGENT = "BunnyHub/1.0";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Compiled once. These sat inside {@code Pattern.compile} calls in the method bodies,
     * so every Drive chapter recompiled the same two expressions - once for the folder id
     * and then once per file while sorting a chapter's pages.
     */
    private static final Pattern DRIVE_ID = Pattern.compile("[-\\w]{25,}");
    private static final Pattern LEADING_NUMBER = Pattern.compile("(\\d{1,9})");

    /**
     * How long a MangaDex page list may be reused.
     *
     * <p>MangaDex hands out page URLs against a host assignment from
     * {@code /at-home/server/:id}, and its documentation is explicit about the lifetime:
     * <i>"We guarantee 15 minutes. Could be more, could be less. Call the
     * /at-home/server/:chapter-id again if you need it after that long but then get a 403
     * error."</i> A URL held past that window stops resolving, and the reader shows a
     * fetch failure on a chapter that is perfectly available.
     *
     * <p>Ten minutes leaves a five-minute margin inside the guarantee, which matters
     * because the expiry is checked when a page is <em>requested</em> and the download
     * happens after. It also comfortably outlives a reader session, which times out after
     * five minutes of inactivity - so an active reader never turns a page into a
     * re-fetch, and an idle one cannot page at all.
     */
    private static final Duration MANGADEX_PAGE_TTL = Duration.ofMinutes(10);

    /** Drive file ids are permanent, so those URLs are cached for as long as they are wanted. */
    private static final Duration DRIVE_PAGE_TTL = Duration.ofHours(24);

    /**
     * Page lists, with a per-entry lifetime that depends on the source.
     *
     * <p>This was one flat 24-hour access expiry for every source. That is right for Drive,
     * whose URLs are permanent, and wrong for MangaDex by an order of magnitude - see
     * {@link #MANGADEX_PAGE_TTL}. Caffeine's {@code Expiry} lets the two live in one cache
     * with the two different lifetimes they actually have.
     *
     * <p>Note the asymmetry on read: a Drive entry renews on access, exactly as before,
     * because nothing about it goes stale. A MangaDex entry does <em>not</em> - reading a
     * time-limited URL cannot make it valid for longer, and renewing on access is how a
     * heavily-read chapter would pin an expired host assignment indefinitely.
     */
    private static final Cache<String, List<String>> PAGES = CacheRegistry.register("manga.pages", Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfter(new Expiry<String, List<String>>() {
                @Override
                public long expireAfterCreate(String key, List<String> value, long currentTime) {
                    return lifetimeOf(key);
                }

                @Override
                public long expireAfterUpdate(String key, List<String> value, long currentTime,
                                              long currentDuration) {
                    return lifetimeOf(key);
                }

                @Override
                public long expireAfterRead(String key, List<String> value, long currentTime,
                                            long currentDuration) {
                    return isMangaDexKey(key) ? currentDuration : DRIVE_PAGE_TTL.toNanos();
                }
            })
            .recordStats()
            .build());

    /** Titles are metadata, not URLs - nothing about them expires. */
    private static final Cache<String, String> TITLES = CacheRegistry.register("manga.titles", Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .recordStats()
            .build());

    private static long lifetimeOf(String key) {
        return (isMangaDexKey(key) ? MANGADEX_PAGE_TTL : DRIVE_PAGE_TTL).toNanos();
    }

    /** The source is the first segment of {@link MangaRef#cacheKey()}. */
    private static boolean isMangaDexKey(String key) {
        int end = key.indexOf(':');
        return end > 0 && MangaCatalog.isMangaDex(key.substring(0, end));
    }

    private MangaService() {}

    /** Identifies one chapter across all sources. */
    public record MangaRef(String series, String source, String group, int chapter) {

        public String cacheKey() {
            return source + ":" + group + ":" + series + ":" + chapter;
        }
    }

    /**
     * One rendered page, or a failure.
     *
     * @param image the downloaded page bytes, already fully read; nothing to close.
     */
    public record PageResult(
            MangaRef ref,
            int page,
            int maxPages,
            String rawUrl,
            byte[] image,
            String title,
            String error) {

        public boolean failed() {
            return error != null;
        }

        static PageResult failure(String message) {
            return new PageResult(null, 0, 0, null, null, null, message);
        }
    }

    /**
     * Resolves a chapter, downloads the requested page and reports how many there are.
     *
     * @param page 1-indexed page number.
     */
    public static PageResult fetchPage(MangaRef ref, int page) {
        String key = ref.cacheKey();

        try {
            List<String> pages = PAGES.get(key, k -> loadPages(ref, k));

            if (pages == null || pages.isEmpty())
                return PageResult.failure("No pages found for the requested chapter.");

            if (page < 1 || page > pages.size())
                return PageResult.failure("Invalid page number. This chapter has " + pages.size() + " page(s).");

            // A chapter served from Mongo skips the metadata call that also carries
            // the title, so backfill it once rather than on every page turn.
            if (MangaCatalog.isMangaDex(ref.source()) && TITLES.getIfPresent(key) == null)
                backfillTitle(ref, key);

            String rawUrl = pages.get(page - 1);
            byte[] image = download(rawUrl);

            String cachedTitle = TITLES.getIfPresent(key);
            String title = (cachedTitle == null || cachedTitle.isEmpty())
                    ? "Chapter " + ref.chapter()
                    : cachedTitle;

            return new PageResult(ref, page, pages.size(), rawUrl, image, title, null);

        } catch (Exception e) {
            BunnyLog.error("[MangaService] Failed to fetch page " + page + " of " + key, e);
            return PageResult.failure("An error occurred while fetching the page.");
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Caffeine loader: MongoDB first, upstream second, and persist what we scan.
     *
     * <p>Returns null on failure so the miss is not cached and the next reader retries.
     *
     * <h2>MangaDex chapters are never stored, and never read back</h2>
     * MongoDB is a durable store, and a MangaDex page list is durable for fifteen minutes
     * (see {@link #MANGADEX_PAGE_TTL}). Writing one here bought a permanent record of URLs
     * that stop resolving the same afternoon: the read path returned any non-empty stored
     * {@code pages} array without ever consulting the {@code last_updated} it had written
     * alongside it, so once a chapter was opened, that chapter served dead URLs forever.
     * The upstream call this was avoiding is a single request, and it is a request that
     * has to happen anyway for the URLs to work at all.
     *
     * <p>Drive is the opposite case and keeps its persistence: {@code lh3.googleusercontent}
     * URLs are built from permanent file ids, and resolving one costs two Google Drive API
     * calls against a quota-limited key.
     *
     * <p>The read is skipped as well as the write, so pre-existing MangaDex documents left
     * over from before this change are inert rather than actively wrong.
     */
    private static List<String> loadPages(MangaRef ref, String key) {
        boolean mangaDex = MangaCatalog.isMangaDex(ref.source());

        try {
            if (!mangaDex) {
                MangaChapterData stored = DB.findOne(MangaChapterData.class, COLLECTION, Filters.eq("_id", key));
                if (stored != null && stored.getPages() != null && !stored.getPages().isEmpty())
                    return stored.getPages();
            }

            List<String> fetched = mangaDex ? fetchFromMangaDex(ref, key) : fetchFromDrive(ref);

            if (!mangaDex && fetched != null && !fetched.isEmpty())
                persist(key, fetched);

            return fetched;
        } catch (IllegalArgumentException e) {
            // "Chapter 999999 not found" is a user typing a number that does not exist,
            // not a fault. It already reaches them as a clean "Chapter Unavailable"
            // embed, so a stack trace in the console is noise that trains whoever reads
            // it to skim past real failures. One line, no trace.
            BunnyLog.info("[MangaService] " + key + ": " + e.getMessage());
            return null;
        } catch (Exception e) {
            // Anything else - a network fault, malformed upstream JSON, a database
            // error - is genuinely unexpected and keeps its trace.
            BunnyLog.error("[MangaService] Chapter load failed for " + key, e);
            return null;
        }
    }

    /**
     * Stores a scanned chapter with targeted {@code $set} operators.
     *
     * <p>Was a whole-document {@code replaceOne}. Page lists routinely run to dozens of
     * URLs, so writing only the two fields that changed is both smaller on the wire and
     * safe against clobbering any field a future schema adds.
     */
    private static void persist(String key, List<String> pages) {
        try {
            DB.getCollection(MangaChapterData.class, COLLECTION).updateOne(
                    Filters.eq("_id", key),
                    Updates.combine(
                            Updates.set("pages", pages),
                            Updates.set("last_updated", System.currentTimeMillis())),
                    new UpdateOptions().upsert(true));
        } catch (Exception e) {
            // The pages are already in hand; failing to cache them in Mongo is not
            // worth failing the read the user is waiting on.
            BunnyLog.error("[MangaService] Could not persist chapter " + key, e);
        }
    }

    /**
     * Fetches the chapter's title once, for a chapter that came back from Mongo.
     *
     * <p>The empty result is cached too, and that is the whole point. Plenty of MangaDex
     * chapters genuinely have no title, and the previous version only stored a non-empty
     * one - so for every untitled chapter the {@code getIfPresent == null} check upstream
     * stayed true forever and each page turn re-ran this: a synchronous download of the
     * series' entire 500-chapter feed, on the bounded command worker, to re-learn that
     * there is still no title. An empty string is a real answer and is cached as one; the
     * renderer already treats empty and absent identically and shows "Chapter N".
     *
     * <p>A <em>failed</em> fetch still caches nothing, so a network blip retries rather
     * than pinning "no title" for the next 24 hours.
     */
    private static void backfillTitle(MangaRef ref, String key) {
        try {
            String title = fetchMangaDexTitle(ref);
            TITLES.put(key, title == null ? "" : title);
        } catch (Exception ignored) {
            // Cosmetic only - the embed falls back to "Chapter N".
        }
    }

    // ------------------------------------------------------------------
    // Upstreams
    // ------------------------------------------------------------------

    /**
     * Downloads a page fully into memory.
     *
     * <p>Bytes rather than a stream, deliberately. An {@code InputStream} handed back to a
     * caller keeps its HTTP connection open until somebody closes it, and every path
     * between here and JDA consuming it - an embed that fails to build, a reply that
     * throws, an early return - leaks that connection permanently. Over a process meant
     * to run for years, a leak per intermittent failure eventually exhausts the pool and
     * then the file descriptors.
     *
     * <p>Reading it here means the connection is released before this method returns, and
     * no stream ever escapes the service. The cost is one page held in memory per
     * in-flight request, which is bounded by the worker count and measured in megabytes.
     */
    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                get(url).build(), HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200)
            throw new IllegalStateException("Image download failed with HTTP " + response.statusCode());

        return response.body();
    }

    private static String mangaDexFeed(String seriesId) {
        return "https://api.mangadex.org/manga/" + seriesId + "/feed?translatedLanguage[]=en&limit=500";
    }

    private static String fetchMangaDexTitle(MangaRef ref) throws Exception {
        String seriesId = MangaCatalog.mangaDexId(ref.series());
        if (seriesId == null)
            return "";

        HttpResponse<String> response = HTTP.send(
                get(mangaDexFeed(seriesId)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            return "";

        DataArray data = DataObject.fromJson(response.body()).getArray("data");
        for (int i = 0; i < data.length(); i++) {
            DataObject attrs = data.getObject(i).getObject("attributes");
            if (attrs.getString("chapter", "").equals(String.valueOf(ref.chapter())))
                return attrs.isNull("title") ? "" : attrs.getString("title");
        }
        return "";
    }

    private static List<String> fetchFromMangaDex(MangaRef ref, String key) throws Exception {
        String seriesId = MangaCatalog.mangaDexId(ref.series());
        if (seriesId == null)
            throw new IllegalArgumentException("Unknown series for MangaDex: " + ref.series());

        HttpResponse<String> feed = HTTP.send(
                get(mangaDexFeed(seriesId)).build(), HttpResponse.BodyHandlers.ofString());
        if (feed.statusCode() != 200)
            throw new IllegalStateException("MangaDex chapter list returned HTTP " + feed.statusCode());

        DataArray data = DataObject.fromJson(feed.body()).getArray("data");

        String chapterId = null;
        String chapterTitle = "";

        for (int i = 0; i < data.length(); i++) {
            DataObject chapter = data.getObject(i);
            DataObject attrs = chapter.getObject("attributes");
            if (attrs.getString("chapter", "").equals(String.valueOf(ref.chapter()))) {
                chapterId = chapter.getString("id");
                if (!attrs.isNull("title"))
                    chapterTitle = attrs.getString("title");
                break;
            }
        }

        if (chapterId == null)
            throw new IllegalArgumentException("Chapter " + ref.chapter() + " not found.");

        TITLES.put(key, chapterTitle);

        HttpResponse<String> atHome = HTTP.send(
                get("https://api.mangadex.org/at-home/server/" + chapterId).build(),
                HttpResponse.BodyHandlers.ofString());
        if (atHome.statusCode() != 200)
            throw new IllegalStateException("MangaDex page list returned HTTP " + atHome.statusCode());

        DataObject json = DataObject.fromJson(atHome.body());
        String baseUrl = json.getString("baseUrl");
        DataObject chapterObj = json.getObject("chapter");
        String hash = chapterObj.getString("hash");
        DataArray pages = chapterObj.getArray("dataSaver");

        List<String> urls = new ArrayList<>(pages.length());
        for (int i = 0; i < pages.length(); i++)
            urls.add(baseUrl + "/data-saver/" + hash + "/" + pages.getString(i));

        return urls;
    }

    private static List<String> fetchFromDrive(MangaRef ref) throws Exception {
        String folderUrl = MangaCatalog.driveFolder(ref.series(), ref.source(), ref.group());
        if (folderUrl == null)
            throw new IllegalArgumentException("No Drive folder configured for that series and source.");

        String apiKey = TokenLoader.optional("DriveKey");
        if (apiKey == null || apiKey.isEmpty())
            throw new IllegalStateException("Google Drive API key not configured.");

        String rootId = extractDriveId(folderUrl);
        DataArray folders = driveList(rootId, apiKey);

        String chapterFolderId = null;
        Pattern chapterPattern = Pattern.compile("^Ch\\. 0*" + ref.chapter() + "(?![\\.\\d])");

        for (int i = 0; i < folders.length(); i++) {
            DataObject folder = folders.getObject(i);
            if (chapterPattern.matcher(folder.getString("name")).find()) {
                chapterFolderId = folder.getString("id");
                break;
            }
        }

        if (chapterFolderId == null)
            throw new IllegalArgumentException("Chapter " + ref.chapter() + " not found on Drive.");

        DataArray files = driveList(chapterFolderId, apiKey);

        List<DataObject> sorted = new ArrayList<>(files.length());
        for (int i = 0; i < files.length(); i++)
            sorted.add(files.getObject(i));

        sorted.sort((a, b) -> Integer.compare(
                leadingNumber(a.getString("name")), leadingNumber(b.getString("name"))));

        List<String> urls = new ArrayList<>(sorted.size());
        for (DataObject file : sorted)
            urls.add("https://lh3.googleusercontent.com/d/" + file.getString("id"));

        return urls;
    }

    private static DataArray driveList(String folderId, String apiKey) throws Exception {
        String query = ("'" + folderId + "' in parents and trashed=false")
                .replace(" ", "%20").replace("=", "%3D").replace("'", "%27");

        String url = "https://www.googleapis.com/drive/v3/files?q=" + query
                + "&fields=files(id,name)&pageSize=1000&key=" + apiKey;

        HttpResponse<String> response = HTTP.send(get(url).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Google Drive returned HTTP " + response.statusCode());

        return DataObject.fromJson(response.body()).getArray("files");
    }

    private static HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET();
    }

    private static String extractDriveId(String url) {
        Matcher matcher = DRIVE_ID.matcher(url);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * The number a Drive filename sorts on.
     *
     * <p>Bounded to nine digits: {@code (\d+)} would happily match a forty-digit run out
     * of some malformed filename and hand {@code Integer.parseInt} something that does not
     * fit, turning a cosmetic sort problem into a thrown chapter load. Nine digits always
     * fit, and no real page is numbered past them.
     */
    private static int leadingNumber(String name) {
        Matcher matcher = LEADING_NUMBER.matcher(name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
