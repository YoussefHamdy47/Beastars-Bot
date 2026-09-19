package org.bunnys.beastars.commands.manga;

import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a series/source/group request onto the upstream that serves it.
 *
 * <h2>Nothing here is compiled in any more</h2>
 * Every identifier below used to be a literal in this file: eight Google Drive folder
 * links and three MangaDex series ids. The Drive links are the reason it matters - they
 * are working links into somebody's Drive, and a public repository containing them is a
 * public repository containing those folders.
 *
 * <p>They are read from the environment at class load and cached. A missing entry is not
 * an error: the source simply reports itself as unavailable, so a fork that has only the
 * MangaDex ids still works for MangaDex and says something sensible for the rest.
 *
 * <p>Lookups stay a strict allow-list. User input is only ever <em>compared</em> against
 * known codes and never interpolated into a URL, so an arbitrary {@code source} from the
 * mention path cannot reach the network.
 */
public final class MangaCatalog {

    public static final String DEFAULT_SERIES = "BST";
    public static final String DEFAULT_SOURCE = "MD";
    public static final String DEFAULT_GROUP = "HCS";

    /**
     * Drive folders, keyed {@code SOURCE:GROUP:SERIES}.
     *
     * <p>{@code GROUP} is {@code *} where the source does not distinguish one - Viz and
     * Raw are single-scan sources, so a group would be a field nobody could fill in.
     */
    private static final Map<String, String> DRIVE_FOLDERS = loadDriveFolders();

    /** MangaDex series ids, keyed by series code. */
    private static final Map<String, String> MANGADEX_IDS = loadMangaDexIds();

    private MangaCatalog() {}

    private static Map<String, String> loadDriveFolders() {
        Map<String, String> folders = new LinkedHashMap<>();

        put(folders, "V:*:BST", "DRIVE_VIZ_BEASTARS");
        put(folders, "V:*:BC", "DRIVE_VIZ_BEAST_COMPLEX");
        put(folders, "R:*:BST", "DRIVE_RAW_BEASTARS");
        put(folders, "R:*:BC", "DRIVE_RAW_BEAST_COMPLEX");
        put(folders, "G:HG:BST", "DRIVE_HYBRIDGUMI_BEASTARS");
        put(folders, "G:HG:BC", "DRIVE_HYBRIDGUMI_BEAST_COMPLEX");
        put(folders, "G:HCS:BST", "DRIVE_HCS_BEASTARS");
        put(folders, "G:HCS:BC", "DRIVE_HCS_BEAST_COMPLEX");

        // Original Beast Complex has exactly one Drive folder per source, so its group
        // key is the wildcard: whatever group somebody picks - or none at all - resolves
        // to the same place. That is what stops "no group selected" reporting the series
        // as non-existent, without changing which group BST and BC default to.
        put(folders, "G:*:OBC", "DRIVE_ORIGINAL_BEAST_COMPLEX");
        put(folders, "R:*:OBC", "DRIVE_RAW_ORIGINAL_BEAST_COMPLEX");

        if (folders.isEmpty())
            BunnyLog.warning("[MangaCatalog] No Google Drive folders configured. "
                    + "The G, V and R sources will report themselves unavailable.");

        return folders;
    }

    private static Map<String, String> loadMangaDexIds() {
        Map<String, String> ids = new LinkedHashMap<>();

        put(ids, "BST", "MANGADEX_BEASTARS");
        put(ids, "BC", "MANGADEX_BEAST_COMPLEX");
        put(ids, "OBC", "MANGADEX_ORIGINAL_BEAST_COMPLEX");
        put(ids, "PG", "MANGADEX_PARU_GRAFFITI");

        if (ids.isEmpty())
            BunnyLog.warning("[MangaCatalog] No MangaDex series configured. "
                    + "The MD source will report itself unavailable.");

        return ids;
    }

    /** Records a mapping only when the environment actually supplies one. */
    private static void put(Map<String, String> target, String key, String envKey) {
        String value = TokenLoader.optional(envKey);
        if (value != null)
            target.put(key, value);
    }

    /** Human-readable series name for embeds. Falls back to Beastars, the default. */
    public static String seriesName(String series) {
        return switch (normaliseSeries(series)) {
            case "BC" -> "Beast Complex";
            case "OBC" -> "Original Beast Complex";
            case "PG" -> "Paru Graffiti";
            default -> "Beastars";
        };
    }

    /** MangaDex id for a series code, or null if the code names nothing configured. */
    public static String mangaDexId(String series) {
        return MANGADEX_IDS.get(normaliseSeries(series));
    }

    /**
     * Google Drive folder for a series/source/group, or null if unmapped or unconfigured.
     *
     * <p>Tries the exact group first, then the group-independent entry. That second
     * lookup is what makes a single-folder series work: Original Beast Complex has one
     * Drive folder per source rather than one per scanlation group, so picking a group -
     * or picking none and getting the default - must resolve to the same place instead of
     * reporting the series as non-existent.
     */
    public static String driveFolder(String series, String source, String group) {
        String normalisedSource = upper(source);
        if (normalisedSource == null)
            return null;

        String normalisedSeries = normaliseSeries(series);

        // Viz and Raw are single-scan sources; only Drive-hosted scanlations have a group.
        if ("G".equals(normalisedSource)) {
            String exact = DRIVE_FOLDERS.get(
                    normalisedSource + ":" + normaliseGroup(group) + ":" + normalisedSeries);
            if (exact != null)
                return exact;
        }

        return DRIVE_FOLDERS.get(normalisedSource + ":*:" + normalisedSeries);
    }

    public static boolean isMangaDex(String source) {
        return "MD".equalsIgnoreCase(source);
    }

    /**
     * Every series code this bot serves, in the order the commands offer them.
     *
     * <p>Adding a series means touching this list <em>and</em> the {@code addChoice}
     * calls on the commands - the choices carry display names, which this does not.
     */
    private static final List<String> SERIES_CODES = List.of("BST", "BC", "OBC", "PG");

    /**
     * Resolves a user-supplied series to a canonical code, or {@code null} if it names
     * nothing this bot serves.
     *
     * <p>Distinct from {@link #seriesName} and the internal normaliser, both of which
     * fall back to Beastars for anything unrecognised. That fallback is right when the
     * series is optional and wrong now that it is required: a reader who typed a series
     * that does not exist must be told, not quietly handed a different comic.
     *
     * <p>Accepts the code or the full name, so the mention path is as forgiving as
     * slash autocomplete.
     */
    public static String resolveSeries(String series) {
        if (series == null || series.isBlank())
            return null;

        String code = normaliseSeries(series);
        return SERIES_CODES.contains(code) ? code : null;
    }

    /** The valid series codes, for error messages that tell the reader what to type. */
    public static List<String> seriesCodes() {
        return SERIES_CODES;
    }

    /**
     * Every source code this bot can read from.
     *
     * <p>{@code MD} is served over the MangaDex API; the rest resolve to a Google Drive
     * folder. A source that is configured but has no folder for a given series is a
     * different failure, reported by {@link #driveFolder} returning null.
     */
    private static final List<String> SOURCE_CODES = List.of("MD", "G", "V", "R");

    /**
     * Resolves a user-supplied source to a canonical code, or {@code null} for anything
     * this bot does not read from.
     *
     * <p>Exists for the same reason {@link #resolveSeries} does. An unrecognised source
     * used to fall through to the Drive path and surface as "No Drive folder configured
     * for that series and source" - which describes a deployment problem the reader
     * cannot fix, when what actually happened is that they typed a code that does not
     * exist. Checking here means a typo is reported as a typo.
     *
     * <p>Accepts the name as well as the code, exactly as {@link #resolveSeries} does.
     * Slash users pick from a choice list and never type either, but somebody writing
     * {@code @BotName manga BST raw 5 1} is spelling out what they mean, and refusing that
     * while accepting {@code Beast Complex} for the series would be an inconsistency with
     * no reason behind it. The names mirror the labels the slash choices display.
     */
    public static String resolveSource(String source) {
        if (source == null || source.isBlank())
            return null;

        return switch (upper(source)) {
            case "MD", "MANGADEX" -> "MD";
            case "G", "DRIVE", "GOOGLE DRIVE" -> "G";
            case "V", "VIZ", "OFFICIAL" -> "V";
            case "R", "RAW", "JAPANESE" -> "R";
            default -> null;
        };
    }

    /** The valid source codes, for error messages that tell the reader what to type. */
    public static List<String> sourceCodes() {
        return SOURCE_CODES;
    }

    /** Accepts the code or the full name, so the mention path is as forgiving as autocomplete. */
    private static String normaliseSeries(String series) {
        if (series == null)
            return DEFAULT_SERIES;
        // Checked before BC: "Original Beast Complex" ends with "Beast Complex", so the
        // more specific name has to win or OBC would silently resolve to BC.
        if ("OBC".equalsIgnoreCase(series) || "Original Beast Complex".equalsIgnoreCase(series))
            return "OBC";
        if ("BC".equalsIgnoreCase(series) || "Beast Complex".equalsIgnoreCase(series))
            return "BC";
        if ("PG".equalsIgnoreCase(series) || "Paru Graffiti".equalsIgnoreCase(series))
            return "PG";
        if ("BST".equalsIgnoreCase(series) || "Beastars".equalsIgnoreCase(series))
            return "BST";

        return upper(series);
    }

    private static String normaliseGroup(String group) {
        return group == null ? DEFAULT_GROUP : upper(group);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
