package org.bunnys.utils;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time-or-repeatable developer utility that synchronizes this application's
 * Discord <b>Application Emojis</b> (Developer Portal &rarr; Application &rarr;
 * Emojis) into {@link AppDesign.Emojis} as Java {@code String} constants.
 *
 * <p><b>This intentionally targets {@link JDA#retrieveApplicationEmojis()}</b>,
 * not guild emojis. Application emojis belong to the bot application itself and
 * are shared across every guild the bot is in.
 *
 * <h2>Why this is a name-keyed sync, not a full rebuild</h2>
 * Earlier versions of this exporter wiped the generated section and rebuilt every
 * constant from scratch on every run, sorted alphabetically. That's fine for
 * values, but it silently renamed constants whenever a new emoji's name sorted
 * ahead of an existing one, since alphabetical de-duplication suffixes
 * ({@code _2}, {@code _3}, ...) shift around. If any other code already
 * referenced {@code AppDesign.Emojis.SOME_NAME}, that would break with no warning.
 * <p>
 * This matters a lot for this project specifically: the exporter is expected to
 * be re-run repeatedly against a live test bot, and the final deployment target
 * is a client-hosted Raspberry Pi with no remote access, so there is no
 * opportunity to fix a broken build after the fact. Because of that, this
 * version:
 * <ul>
 *     <li>Matches existing constants to live emojis by the <b>Discord emoji
 *     name</b> embedded in the constant's value (e.g. the {@code Legoshi_Laughing}
 *     inside {@code "<:Legoshi_Laughing:123>"}), not by re-deriving names from
 *     scratch.</li>
 *     <li>Keeps an existing constant's Java name forever once assigned, and only
 *     ever updates its value (id / animated flag) when the underlying emoji is
 *     re-uploaded.</li>
 *     <li>Appends genuinely new emojis with freshly generated names, without
 *     touching anything that already exists.</li>
 *     <li>Removes constants whose emoji no longer exists on Discord, but logs
 *     each one by name so a dangling reference can be found and fixed before it
 *     turns into a compile error.</li>
 *     <li>Refuses to write anything if Discord's response looks like a partial
 *     failure (far fewer emojis than are already recorded), rather than treating
 *     that as a mass deletion.</li>
 * </ul>
 *
 * <p>This is still deliberately <b>not</b> wired into the bot's normal startup
 * path; run it manually (e.g. a developer-only slash command, or a scratch
 * invocation) whenever emojis change in the Developer Portal:
 *
 * <pre>{@code
 * ApplicationEmojiExporter.export(jda, Path.of("src/main/java/org/bunnys/utils/AppDesign.java"))
 *         .thenAccept(count -> System.out.println("Synced " + count + " application emoji constant(s)"))
 *         .exceptionally(err -> {
 *             err.printStackTrace();
 *             return null;
 *         });
 * }</pre>
 *
 * <p>The target file must already contain the marker comments described by
 * {@link #START_MARKER} / {@link #END_MARKER}, each on its own line, inside
 * {@code AppDesign.Emojis}. Everything outside those markers is left untouched.
 */
public final class ApplicationEmojiExporter {

    private ApplicationEmojiExporter() {
    }

    /** Distinctive text identifying the start of the generated block. Indentation-agnostic. */
    private static final String START_MARKER = "GENERATED APPLICATION EMOJIS - DO NOT EDIT";

    /** Distinctive text identifying the end of the generated block. Indentation-agnostic. */
    private static final String END_MARKER = "END GENERATED APPLICATION EMOJIS";

    /** Indentation used for generated constants. Matches AppDesign.Emojis's nesting. */
    private static final String INDENT = "        ";

    /**
     * Matches a previously generated constant line, e.g.
     * {@code public static final String LEGOSHI_LAUGHING = "<:Legoshi_Laughing:1535661959884705793>";}
     * Group 1 = Java constant name, group 2 = full emoji markdown, group 3 = raw Discord emoji name.
     */
    private static final Pattern CONSTANT_LINE = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+([A-Z0-9_]+)\\s*=\\s*\"(<a?:([A-Za-z0-9_]+):\\d+>)\"\\s*;"
    );

    /**
     * Minimal, JDA-independent snapshot of a Discord application emoji. Keeping the
     * sync/merge logic below free of JDA types makes it straightforward to unit
     * test in isolation.
     */
    record EmojiSnapshot(String name, String formatted) {
    }

    private record ExistingEntry(String constantName, String discordName) {
    }

    /**
     * Retrieves all application emojis and syncs them into the generated section
     * of {@code targetFile}, matching by Discord emoji name (see class Javadoc).
     *
     * @param jda        a JDA instance belonging to the target application (should be ready)
     * @param targetFile path to {@code AppDesign.java}
     * @return a future completing with the total number of generated constants after the sync,
     *         or completing exceptionally if the Discord request or the file write fails
     */
    public static CompletableFuture<Integer> export(JDA jda, Path targetFile) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        jda.retrieveApplicationEmojis().queue(
                emojis -> {
                    try {
                        List<EmojiSnapshot> snapshots = emojis.stream()
                                .map(e -> new EmojiSnapshot(e.getName(), e.getFormatted()))
                                .toList();
                        int count = sync(targetFile, snapshots);
                        BunnyLog.info("[ApplicationEmojiExporter] Synced " + count
                                + " application emoji constant(s) into " + targetFile);
                        future.complete(count);
                    } catch (IOException e) {
                        BunnyLog.error("[ApplicationEmojiExporter] Failed to write " + targetFile + ": " + e.getMessage());
                        future.completeExceptionally(e);
                    }
                },
                error -> {
                    BunnyLog.error("[ApplicationEmojiExporter] Failed to retrieve application emojis: " + error.getMessage());
                    future.completeExceptionally(error);
                }
        );

        return future;
    }

    // ---- Pure logic below: no JDA types, fully unit-testable in isolation. ----

    static int sync(Path targetFile, List<EmojiSnapshot> liveEmojis) throws IOException {
        if (!Files.exists(targetFile))
            throw new IOException("Target file does not exist: " + targetFile.toAbsolutePath());

        String original = Files.readString(targetFile, StandardCharsets.UTF_8);

        int startMarkerIdx = original.indexOf(START_MARKER);
        int endMarkerIdx = original.indexOf(END_MARKER);
        if (startMarkerIdx == -1 || endMarkerIdx == -1 || endMarkerIdx < startMarkerIdx) {
            throw new IOException(
                    "Could not locate the GENERATED APPLICATION EMOJIS marker comments in "
                            + targetFile.toAbsolutePath()
                            + ". Add the marker block once inside AppDesign.Emojis before running the exporter."
            );
        }

        int afterStartLine = original.indexOf('\n', startMarkerIdx) + 1;
        int endMarkerLineStart = original.lastIndexOf('\n', endMarkerIdx) + 1;
        if (afterStartLine <= 0 || endMarkerLineStart <= 0 || endMarkerLineStart < afterStartLine) {
            throw new IOException("Marker comments in " + targetFile.toAbsolutePath()
                    + " are malformed; expected each on its own line.");
        }

        String existingSection = original.substring(afterStartLine, endMarkerLineStart);

        // Keyed by Discord emoji name, in original file order.
        LinkedHashMap<String, ExistingEntry> existingByName = parseExisting(existingSection);

        // Safety guard: refuse to treat a flaky/partial API response as a mass
        // deletion. This is deliberately conservative because this may run
        // somewhere nobody can immediately fix (see class Javadoc).
        if (!existingByName.isEmpty()) {
            int liveCount = liveEmojis.size();
            int existingCount = existingByName.size();
            if (liveCount == 0 || liveCount < existingCount / 2) {
                throw new IOException(String.format(
                        "Refusing to sync: Discord returned %d application emoji(s) but %d constant(s) already "
                                + "exist in %s. This looks like a partial/failed API response rather than an "
                                + "intentional mass-deletion, so nothing was written. Investigate before re-running.",
                        liveCount, existingCount, targetFile.toAbsolutePath()));
            }
        }

        Map<String, EmojiSnapshot> liveByName = new LinkedHashMap<>();
        for (EmojiSnapshot snap : liveEmojis) {
            if (liveByName.put(snap.name(), snap) != null) {
                BunnyLog.warning("[ApplicationEmojiExporter] Duplicate application emoji name '" + snap.name()
                        + "' returned by Discord; keeping the last one seen.");
            }
        }

        Set<String> usedConstantNames = new HashSet<>();
        for (ExistingEntry e : existingByName.values())
            usedConstantNames.add(e.constantName());

        List<String> removed = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        // 1. Keep existing entries in their original order, refreshing their value
        //    if the emoji still exists. Constant names are NEVER changed here.
        for (Map.Entry<String, ExistingEntry> entry : existingByName.entrySet()) {
            String discordName = entry.getKey();
            ExistingEntry existing = entry.getValue();
            EmojiSnapshot live = liveByName.remove(discordName);

            if (live == null) {
                removed.add(existing.constantName() + " (\"" + discordName + "\")");
                continue;
            }

            lines.add(INDENT + "public static final String " + existing.constantName()
                    + " = \"" + live.formatted() + "\";");
        }

        // 2. Anything left in liveByName is new since the last run - append in
        //    alphabetical order with freshly generated, deduplicated names.
        List<EmojiSnapshot> newOnes = new ArrayList<>(liveByName.values());
        newOnes.sort(Comparator.comparing(EmojiSnapshot::name, String.CASE_INSENSITIVE_ORDER));

        for (EmojiSnapshot snap : newOnes) {
            String constantName = uniqueConstantName(toConstantName(snap.name()), usedConstantNames);
            lines.add(INDENT + "public static final String " + constantName
                    + " = \"" + snap.formatted() + "\";");
        }

        if (!removed.isEmpty()) {
            BunnyLog.warning("[ApplicationEmojiExporter] " + removed.size()
                    + " application emoji constant(s) no longer exist on Discord and were removed: "
                    + String.join(", ", removed)
                    + ". If any code still references these, it will fail to compile until updated.");
        }

        String generatedBody = String.join("\n", lines);
        String replacement = generatedBody.isEmpty() ? "\n" : "\n" + generatedBody + "\n\n";
        String updated = original.substring(0, afterStartLine) + replacement + original.substring(endMarkerLineStart);

        writeAtomically(targetFile, updated);

        return lines.size();
    }

    /** Parses previously generated constant lines into (discordName -> entry), preserving file order. */
    static LinkedHashMap<String, ExistingEntry> parseExisting(String section) {
        LinkedHashMap<String, ExistingEntry> byDiscordName = new LinkedHashMap<>();
        Matcher matcher = CONSTANT_LINE.matcher(section);
        while (matcher.find()) {
            String constantName = matcher.group(1);
            String discordName = matcher.group(3);
            byDiscordName.put(discordName, new ExistingEntry(constantName, discordName));
        }
        return byDiscordName;
    }

    private static void writeAtomically(Path targetFile, String content) throws IOException {
        // Write to a temp file in the same directory, then atomically move it over
        // the original. Avoids leaving a half-written AppDesign.java behind if the
        // process dies mid-write.
        Path parent = targetFile.toAbsolutePath().getParent();
        Path tempFile = Files.createTempFile(parent, "AppDesign", ".java.tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * Converts a Discord emoji name (letters, digits, underscores; may be
     * snake_case, PascalCase, or a mix) into a valid, conventional Java constant
     * name. Only used for genuinely new emojis - see class Javadoc for why
     * existing constants never get renamed.
     *
     * <pre>
     * Legoshi_Laughing -> LEGOSHI_LAUGHING
     * SomeAnimation     -> SOME_ANIMATION
     * </pre>
     */
    static String toConstantName(String rawName) {
        String sanitized = rawName.replaceAll("[^A-Za-z0-9_]", "_");
        sanitized = sanitized.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_");
        sanitized = sanitized.toUpperCase(Locale.ROOT);
        sanitized = sanitized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");

        if (sanitized.isEmpty())
            sanitized = "EMOJI";
        else if (Character.isDigit(sanitized.charAt(0)))
            sanitized = "EMOJI_" + sanitized;

        return sanitized;
    }

    /** Ensures {@code candidate} is unique, appending {@code _2}, {@code _3}, ... on collision. */
    static String uniqueConstantName(String candidate, Set<String> usedNames) {
        if (usedNames.add(candidate))
            return candidate;

        int suffix = 2;
        String attempt;
        do {
            attempt = candidate + "_" + suffix++;
        } while (!usedNames.add(attempt));

        return attempt;
    }
}