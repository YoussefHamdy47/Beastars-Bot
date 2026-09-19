package org.bunnys.beastars.commands.wiki;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.bunnys.utils.BunnyLog;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the Beastars Fandom wiki.
 *
 * <p>Autocomplete fires on nearly every keystroke, so the suggestion cache is what
 * stands between this bot and a Fandom rate limit. It was a synchronized
 * {@code LinkedHashMap} with a size bound but no expiry - a renamed article stayed
 * wrong in the dropdown until restart. Caffeine adds the TTL.
 *
 * <p>Article lookups are now cached too. They previously hit Fandom on every single
 * invocation, including repeats of the same popular character.
 */
public final class WikiService {

    private static final String API = "https://beastars.fandom.com/api.php";
    private static final String ARTICLE_BASE = "https://beastars.fandom.com/wiki/";

    private static final int SUMMARY_LIMIT = 300;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Cache<String, List<String>> SUGGESTIONS = CacheRegistry.register("wiki.suggestions", Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(6, TimeUnit.HOURS)
            .recordStats()
            .build());

    private static final Cache<String, Article> ARTICLES = CacheRegistry.register("wiki.articles", Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(6, TimeUnit.HOURS)
            .recordStats()
            .build());

    private WikiService() {}

    /** A resolved wiki article. */
    public record Article(String title, String summary, String url, String thumbnailUrl) {}

    /**
     * Looks up an article.
     *
     * @return the article, or null when the wiki has no page for the query.
     */
    public static Article lookup(String query) {
        if (query == null || query.isBlank())
            return null;

        return ARTICLES.get(query.toLowerCase().trim(), k -> fetchArticle(query));
    }

    /** Autocomplete suggestions, capped at Discord's 25-choice limit. */
    public static List<String> suggest(String query) {
        if (query == null || query.isBlank())
            return List.of();

        List<String> cached = SUGGESTIONS.get(query.toLowerCase().trim(), k -> fetchSuggestions(query));
        return cached != null ? cached : List.of();
    }

    public static String articleUrl(String title) {
        return ARTICLE_BASE + encode(title.replace(" ", "_"));
    }

    // ------------------------------------------------------------------
    // Upstream
    // ------------------------------------------------------------------

    private static Article fetchArticle(String query) {
        try {
            String url = API + "?action=query&prop=extracts%7Cpageimages&format=json"
                    + "&exintro=1&explaintext=1&redirects=1&titles=" + encode(query);

            HttpResponse<String> response = HTTP.send(
                    get(url, 10), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                return null;

            DataObject body = DataObject.fromJson(response.body());

            // Fandom answers a query it cannot serve with a body that simply has no
            // `query` key, and a query it served with nothing in it with an empty `pages`
            // object. Both used to reach the blanket catch below - as a NoSuchElement or
            // a missing-key throw - so "there is no article called that", the single most
            // ordinary outcome of a wiki search, printed a stack trace. It is a null.
            if (!body.hasKey("query"))
                return null;

            DataObject pages = body.getObject("query").getObject("pages");

            var ids = pages.keys().iterator();
            if (!ids.hasNext())
                return null;

            String firstId = ids.next();
            if (firstId.equals("-1"))
                return null; // No such article.

            DataObject page = pages.getObject(firstId);
            String title = page.getString("title", query);
            String summary = page.getString("extract", "");

            if (summary.isEmpty())
                summary = searchSnippet(title);
            if (summary.isEmpty())
                summary = "No summary available for this page.";
            else if (summary.length() > SUMMARY_LIMIT)
                summary = summary.substring(0, SUMMARY_LIMIT - 3) + "...";

            String thumbnail = page.hasKey("thumbnail")
                    ? page.getObject("thumbnail").getString("source", null)
                    : null;

            return new Article(title, summary, articleUrl(title), thumbnail);

        } catch (Exception e) {
            BunnyLog.error("[WikiService] Article lookup failed for '" + query + "'", e);
            return null;
        }
    }

    private static List<String> fetchSuggestions(String query) {
        try {
            HttpResponse<String> response = HTTP.send(
                    get(API + "?action=opensearch&format=json&search=" + encode(query), 5),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                return null;

            DataArray json = DataArray.fromJson(response.body());
            if (json.length() <= 1)
                return List.of();

            DataArray results = json.getArray(1);
            List<String> suggestions = new ArrayList<>(Math.min(results.length(), 25));
            for (int i = 0; i < results.length() && i < 25; i++)
                suggestions.add(results.getString(i));

            return suggestions;
        } catch (Exception e) {
            BunnyLog.error("[WikiService] Autocomplete failed for '" + query + "'", e);
            return null; // Not cached; the next keystroke retries.
        }
    }

    private static String searchSnippet(String title) {
        try {
            HttpResponse<String> response = HTTP.send(
                    get(API + "?action=opensearch&format=json&search=" + encode(title), 10),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                return "";

            DataArray json = DataArray.fromJson(response.body());
            if (json.length() > 2) {
                DataArray snippets = json.getArray(2);
                if (snippets.length() > 0)
                    return snippets.getString(0);
            }
        } catch (Exception e) {
            BunnyLog.error("[WikiService] Snippet search failed for '" + title + "'", e);
        }
        return "";
    }

    private static HttpRequest get(String url, int timeoutSeconds) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "BeastarsBot/4.0")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
