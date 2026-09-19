package org.bunnys.handler.utils;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.bunnys.utils.BunnyLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads every value the bot must not have compiled into it.
 *
 * <p>{@code .env} first, then the real process environment, so a container or a systemd
 * unit can supply the same keys with no file present at all.
 *
 * <h2>What belongs here</h2>
 * Tokens are the obvious case but not the only one. A private Google Drive folder id is a
 * working link to somebody's files, a Discord channel id names a private channel, and a
 * user id names a person. None are secrets in the cryptographic sense; all are things you
 * would rather not hand to a stranger who cloned the repository.
 *
 * <h2>Missing values</h2>
 * Three situations, deliberately distinguished:
 * <ul>
 *   <li>{@link #require} - the bot cannot run without it. Fails at startup naming the key,
 *       rather than throwing a {@code NullPointerException} an hour later from somewhere
 *       unrelated.</li>
 *   <li>{@link #optional} - a feature switches itself off. A fork with no Drive folders
 *       should still read MangaDex rather than refusing to start.</li>
 *   <li>{@link #orDefault} - a sensible fallback exists and nobody needs to think about it.</li>
 * </ul>
 *
 * <p>Never logs a value - only ever the key it was asked for. A token in a console
 * scrollback is a token in a screenshot.
 */
public class TokenLoader {

    /**
     * Where {@code .env} is looked for, in order.
     *
     * <p>{@code src/main/resources} first, because that is where this project keeps it and
     * it is what an IDE run finds. The working directory second, which is what a deployed
     * jar sees when the file sits beside it. Neither is guaranteed, so the last resort is
     * the real process environment, which is how a container or systemd unit supplies the
     * same keys with no file at all.
     *
     * <p>The library's plain {@code Dotenv.load()} only ever looks in the working
     * directory, so running from an IDE and running from a jar would otherwise disagree
     * about where configuration lives.
     */
    private static final String[] SEARCH_PATH = {
            "src/main/resources",
            "."
    };

    /**
     * Loaded once, lazily, on whichever thread asks first.
     *
     * <p>A holder class gets exactly-once initialisation and safe publication from the JVM
     * itself, with no lock on the read path - which matters because these are read from
     * every command worker.
     */
    private static final class Holder {
        static final Dotenv INSTANCE = load();

        private static Dotenv load() {
            for (String directory : SEARCH_PATH) {
                if (!Files.isReadable(Path.of(directory, ".env")))
                    continue;

                try {
                    Dotenv dotenv = Dotenv.configure().directory(directory).load();
                    BunnyLog.info("[Config] Loaded " + Path.of(directory, ".env"));
                    return dotenv;
                } catch (DotenvException e) {
                    // Present but unreadable or malformed. Say which file, then keep
                    // looking - a broken file should not mask a good one further down.
                    BunnyLog.warning("[Config] Could not read " + Path.of(directory, ".env")
                            + " (" + e.getMessage() + "). Trying the next location.");
                }
            }

            BunnyLog.warning("[Config] No .env file found in " + String.join(" or ", SEARCH_PATH)
                    + ". Reading system environment variables instead.");

            return Dotenv.configure().ignoreIfMissing().load();
        }
    }

    private TokenLoader() {}

    private static Dotenv dotenv() {
        return Holder.INSTANCE;
    }

    /** The value, or null when absent or blank. Silent - callers decide what that means. */
    public static String optional(String key) {
        String value = dotenv().get(key);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * A value the bot cannot start without.
     *
     * @throws IllegalStateException naming the key, so the fix is obvious from the message
     */
    public static String require(String key) {
        String value = optional(key);

        if (value == null)
            throw new IllegalStateException(
                    "[Config] Required setting '" + key + "' is missing. "
                            + "Add it to your .env file. See .env.example for the full list.");

        return value;
    }

    /** The value, or the fallback when it is absent. */
    public static String orDefault(String key, String fallback) {
        String value = optional(key);
        return value != null ? value : fallback;
    }

    /**
     * Reads an on/off switch from the environment.
     *
     * <p>Accepts the spellings people actually type in a {@code .env} on a headless box -
     * {@code true}, {@code yes}, {@code y}, {@code on}, {@code 1} - rather than only the
     * one {@code Boolean.parseBoolean} recognises. Anything unrecognised falls back
     * rather than silently reading as false, so a typo cannot quietly disable something.
     */
    public static boolean flag(String key, boolean fallback) {
        String value = optional(key);
        if (value == null || value.isBlank())
            return fallback;

        return switch (value.trim().toLowerCase()) {
            case "true", "yes", "y", "on", "1" -> true;
            case "false", "no", "n", "off", "0" -> false;
            default -> fallback;
        };
    }

    /**
     * A comma-separated list, trimmed and with blanks dropped.
     *
     * <p>Empty rather than null when unset, so callers can iterate without a guard.
     */
    public static List<String> list(String key) {
        String raw = optional(key);
        if (raw == null)
            return List.of();

        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
                values.add(trimmed);
        }

        return values;
    }

    /**
     * Reports which of the given keys are missing, without throwing.
     *
     * <p>Used at startup so somebody setting the bot up sees <em>every</em> missing key at
     * once, rather than fixing them one restart at a time.
     */
    public static List<String> missing(String... keys) {
        List<String> absent = new ArrayList<>();
        for (String key : keys)
            if (optional(key) == null)
                absent.add(key);

        return absent;
    }

    /** @throws IllegalArgumentException when the token is missing; there is no useful fallback */
    public static String getToken(String tokenKey) {
        String keyToSearch = (tokenKey != null && !tokenKey.isEmpty()) ? tokenKey : "DISCORD_TOKEN";

        String token = optional(keyToSearch);

        if (token == null) {
            BunnyLog.error("Failed to load token. Key '" + keyToSearch + "' not found.");
            throw new IllegalArgumentException("Missing Discord Token");
        }

        BunnyLog.info("Discord token retrieved successfully using key: " + keyToSearch);
        return token;
    }
}
