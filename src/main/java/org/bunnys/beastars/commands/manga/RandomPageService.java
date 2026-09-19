package org.bunnys.beastars.commands.manga;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.bunnys.beastars.commands.manga.MangaService.MangaRef;
import org.bunnys.beastars.commands.manga.MangaService.PageResult;
import org.bunnys.utils.BunnyLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Picks a random page from a series.
 *
 * <p>Moved out of its own single-file {@code commands.random} package: it is a manga
 * reader entry point that delegates straight to {@link MangaService}, and keeping it
 * next to the reader let it stop carrying a private duplicate of the MangaDex series
 * catalogue - that lookup now comes from {@link MangaCatalog}.
 *
 * <h2>Cache corrections</h2>
 * The chapter catalogue previously lived in a plain {@code ConcurrentHashMap} that was
 * <em>unbounded and never expired</em>: a bot running for months would keep serving a
 * chapter index frozen at boot, so newly published chapters could never be drawn. It is
 * now a bounded Caffeine cache with a 12-hour write expiry.
 */
public final class RandomPageService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Re-scan the chapter index twice a day so new releases become drawable. */
    private static final Cache<String, Map<Integer, Integer>> CATALOG = CacheRegistry.register("randompage.catalog", Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(12, TimeUnit.HOURS)
            .recordStats()
            .build());

    /** Recently served pages, to keep consecutive rolls from repeating. */
    private static final Cache<String, Boolean> RECENT = CacheRegistry.register("randompage.recent", Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .recordStats()
            .build());

    private static final int REROLL_ATTEMPTS = 10;

    private RandomPageService() {}

    /** Draws a page, avoiding anything served recently, and fetches it. */
    public static PageResult fetchRandomPage(String series, String source, String group) {
        try {
            Map<Integer, Integer> catalog = CATALOG.get(series, k -> loadCatalog(series));

            if (catalog == null || catalog.isEmpty())
                return PageResult.failure(
                        "Failed to load the chapter index for " + MangaCatalog.seriesName(series) + ".");

            List<Integer> chapters = new ArrayList<>(catalog.keySet());

            for (int attempt = 0; attempt < REROLL_ATTEMPTS; attempt++) {
                int chapter = chapters.get(ThreadLocalRandom.current().nextInt(chapters.size()));
                int maxPages = catalog.getOrDefault(chapter, 0);
                if (maxPages <= 0)
                    continue;

                int page = ThreadLocalRandom.current().nextInt(1, maxPages + 1);
                String historyKey = series + ":" + chapter + ":" + page;

                if (RECENT.getIfPresent(historyKey) == null) {
                    RECENT.put(historyKey, Boolean.TRUE);
                    return MangaService.fetchPage(new MangaRef(series, source, group, chapter), page);
                }
            }

            // Every reroll landed on something recent - take whatever comes up.
            int chapter = chapters.get(ThreadLocalRandom.current().nextInt(chapters.size()));
            int maxPages = Math.max(1, catalog.getOrDefault(chapter, 1));
            int page = ThreadLocalRandom.current().nextInt(1, maxPages + 1);

            return MangaService.fetchPage(new MangaRef(series, source, group, chapter), page);

        } catch (Exception e) {
            BunnyLog.error("[RandomPageService] Random draw failed for series " + series, e);
            return PageResult.failure("An error occurred while generating a random page.");
        }
    }

    /**
     * Chapter number to page count, straight from the MangaDex feed.
     *
     * <p>Returns null rather than an empty map on failure, so Caffeine does not cache
     * an outage for the next twelve hours.
     */
    private static Map<Integer, Integer> loadCatalog(String series) {
        String seriesId = MangaCatalog.mangaDexId(series);
        if (seriesId == null)
            return null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mangadex.org/manga/" + seriesId
                            + "/feed?translatedLanguage[]=en&limit=500"))
                    .header("User-Agent", "BeastarsBot/4.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                BunnyLog.warning("[RandomPageService] MangaDex feed returned HTTP " + response.statusCode());
                return null;
            }

            DataArray data = DataObject.fromJson(response.body()).getArray("data");
            Map<Integer, Integer> catalog = new HashMap<>();

            for (int i = 0; i < data.length(); i++) {
                DataObject attrs = data.getObject(i).getObject("attributes");
                if (attrs.isNull("chapter") || attrs.isNull("pages"))
                    continue;

                try {
                    int pages = attrs.getInt("pages");
                    if (pages > 0)
                        catalog.put(Integer.parseInt(attrs.getString("chapter")), pages);
                } catch (NumberFormatException ignored) {
                    // Half-chapters like "12.5" are not drawable targets.
                }
            }

            return catalog.isEmpty() ? null : catalog;

        } catch (Exception e) {
            BunnyLog.error("[RandomPageService] Could not load chapter index for " + series, e);
            return null;
        }
    }
}
