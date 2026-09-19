package org.bunnys.beastars.commands.presence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.User;
import org.bson.Document;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.metrics.CacheRegistry;
import org.bunnys.utils.BunnyLog;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The bot's own presence - the "Playing ..." line under its name - and the only thing that
 * writes it.
 *
 * <h2>This setting is global, not per-guild</h2>
 * Everything else an administrator can configure in this bot is scoped to their own
 * server. The presence is not: Discord gives a bot <em>one</em> presence for the whole
 * process, so whoever changes it changes what every server sees. That is a property of
 * the platform and cannot be worked around - the same line is broadcast to all of them.
 * It is why this lives behind its own command rather than under {@code /admin}, and why
 * every change is audited with the name of whoever made it.
 *
 * <h2>What Discord will and will not render</h2>
 * A bot presence carries a type, a line of text, and - for {@code STREAMING} only - a
 * URL. It carries no image: rich-presence artwork is a feature of the game/RPC clients,
 * and JDA offers no way to send it, as {@link Activity}'s factory methods show. The
 * closest thing to a logo in a presence is the bot's avatar, which is a separate account
 * setting entirely.
 *
 * <p>Unicode emoji are ordinary characters and render fine. Custom Discord emoji do not:
 * presence text is never parsed as markdown, so {@code <:Name:12345>} appears verbatim,
 * as that literal string. {@link #parse} rejects it rather than letting somebody publish
 * it to every server and find out afterwards.
 *
 * <h2>Storage</h2>
 * One document in {@code bot_settings}, keyed {@value #DOCUMENT_ID}. The presence has to
 * survive a restart - an administrator who sets it should not find the bot back on its
 * default after the next deploy - and the process has nowhere else to keep it.
 */
public final class PresenceService {

    /** Process-wide settings that belong to no guild. */
    public static final String COLLECTION = "bot_settings";

    static final String DOCUMENT_ID = "presence";

    /**
     * Both limits are 128, but they are different fields on Discord's side - the text
     * lands in {@code name} for most types and in {@code state} for a custom status.
     * Taking the smaller of the two means one check covers every type, and it is the same
     * check JDA will apply when the activity is built.
     */
    public static final int MAX_TEXT_LENGTH =
            Math.min(Activity.MAX_ACTIVITY_NAME_LENGTH, Activity.MAX_ACTIVITY_STATE_LENGTH);

    /** {@code <:Name:123>} and the animated {@code <a:Name:123>} form. */
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:[A-Za-z0-9_]+:\\d+>");

    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    /**
     * One entry, but a real cache all the same.
     *
     * <p>The read happens at startup and whenever somebody asks what the presence is, and
     * the house rule is that a database read sits behind Caffeine. Writes seed it from the
     * post-image, so the confirmation embed never re-reads.
     */
    private static final Cache<String, Setting> STORED = CacheRegistry.register("presence.setting",
            Caffeine.newBuilder()
                    .maximumSize(4)
                    .expireAfterWrite(6, TimeUnit.HOURS)
                    .recordStats()
                    .build());

    private PresenceService() {}

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    /**
     * The activity types a bot may actually use, with the wording Discord renders.
     *
     * <p>Wrapping {@link Activity.ActivityType} rather than exposing it directly gives the
     * command one list to build its choices from, the mention parser one place to resolve
     * a typed word against, and the embeds the same verb Discord will show - so a
     * confirmation reads exactly like the finished presence.
     */
    public enum Kind {

        PLAYING("playing", "Playing"),
        LISTENING("listening", "Listening to"),
        WATCHING("watching", "Watching"),
        COMPETING("competing", "Competing in"),
        STREAMING("streaming", "Streaming"),

        /** Renders as the bare text, with no verb in front of it. */
        CUSTOM("custom", "");

        private final String code;
        private final String verb;

        Kind(String code, String verb) {
            this.code = code;
            this.verb = verb;
        }

        /** The value stored and accepted on the command line. */
        public String code() { return code; }

        /** What Discord puts before the text, or empty for a custom status. */
        public String verb() { return verb; }

        /** Human label for a choice list, e.g. {@code "Listening to ..."}. */
        public String label() {
            return this == CUSTOM ? "Custom status" : verb;
        }

        /** True when this type requires a stream URL. */
        public boolean needsUrl() {
            return this == STREAMING;
        }

        /** How the finished presence will read under the bot's name. */
        public String preview(String text) {
            return verb.isEmpty() ? text : verb + " " + text;
        }

        /** Resolves a typed or stored value, or null when it names nothing usable. */
        public static Kind resolve(String value) {
            if (value == null || value.isBlank())
                return null;

            String needle = value.trim();
            for (Kind kind : values())
                if (kind.code.equalsIgnoreCase(needle) || kind.name().equalsIgnoreCase(needle))
                    return kind;

            return null;
        }

        public static List<String> codes() {
            return List.of(PLAYING.code, LISTENING.code, WATCHING.code,
                    COMPETING.code, STREAMING.code, CUSTOM.code);
        }
    }

    /**
     * A presence that has been validated and is safe to publish.
     *
     * @param url             the stream URL; non-null only for {@link Kind#STREAMING}
     * @param updatedBy       user id of whoever set it, or null for the built-in default
     * @param updatedAtEpochSeconds when it was set, or 0 for the built-in default
     */
    public record Setting(Kind kind, String text, String url,
                          String updatedBy, long updatedAtEpochSeconds) {

        public Activity toActivity() {
            return switch (kind) {
                case PLAYING -> Activity.playing(text);
                case LISTENING -> Activity.listening(text);
                case WATCHING -> Activity.watching(text);
                case COMPETING -> Activity.competing(text);
                case STREAMING -> Activity.streaming(text, url);
                case CUSTOM -> Activity.customStatus(text);
            };
        }

        /** How it reads under the bot's name in Discord. */
        public String preview() {
            return kind.preview(text);
        }

        /** True when nobody has set this and it is the compiled-in default. */
        public boolean isDefault() {
            return updatedBy == null;
        }
    }

    /**
     * The outcome of validating a request.
     *
     * <p>Carries the refusal's title and body rather than a boolean, so the caller renders
     * one embed and never has to work out <em>why</em> something was rejected. Each
     * rejection names the specific thing that was wrong and what to do instead.
     */
    public record Parsed(Setting setting, String errorTitle, String errorMessage) {

        public boolean failed() {
            return setting == null;
        }

        static Parsed reject(String title, String message) {
            return new Parsed(null, title, message);
        }
    }

    /** The presence used until somebody sets one, and the one {@code reset} returns to. */
    public static Setting defaultSetting() {
        return new Setting(Kind.WATCHING, "Beastars", null, null, 0L);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Turns raw command input into something publishable, or explains why it is not.
     *
     * <p>Everything Discord would reject, silently mangle, or render as literal text is
     * caught here - before the write and before the broadcast. The slash path already
     * constrains the type through choices; the mention path constrains nothing, and both
     * arrive here.
     *
     * @param actor whoever is making the change; recorded on the returned setting
     */
    public static Parsed parse(String rawKind, String rawText, String rawUrl, User actor) {
        Kind kind = Kind.resolve(rawKind);

        if (kind == null) {
            boolean omitted = rawKind == null || rawKind.isBlank();
            return Parsed.reject(
                    omitted ? "Activity Type Required" : "Unknown Activity Type",
                    (omitted
                            ? "Tell me what the bot should be doing, one of "
                            : "`" + rawKind + "` is not an activity Discord supports. Pick one of ")
                            + inlineCodes(Kind.codes()) + ".");
        }

        if (rawText == null || rawText.isBlank())
            return Parsed.reject("Text Required",
                    "Tell me what the presence should say, for example `text:Beastars`.");

        String text = rawText.trim();

        // A presence is a single line. Discord does not render the break, so a pasted
        // multi-line value silently loses its shape rather than being refused.
        if (LINE_BREAK.matcher(text).find())
            return Parsed.reject("One Line Only",
                    "A presence is a single line, and Discord will not render the line breaks."
                            + " Put it on one line and try again.");

        // Rejected rather than stripped: somebody typing a custom emoji meant to see that
        // emoji, and silently removing it would publish something they did not write.
        if (CUSTOM_EMOJI.matcher(text).find())
            return Parsed.reject("Custom Emoji Will Not Render",
                    "Presence text is never parsed as markdown, so a custom emoji shows up as"
                            + " its raw `<:Name:123>` text to everyone.\n\n"
                            + "Standard Unicode emoji work fine here, so use one of those instead.");

        if (text.length() > MAX_TEXT_LENGTH)
            return Parsed.reject("Text Too Long",
                    "Discord allows **" + MAX_TEXT_LENGTH + "** characters in a presence;"
                            + " that was **" + text.length() + "**."
                            + "\nTrim it by " + (text.length() - MAX_TEXT_LENGTH) + " and try again.");

        String url = (rawUrl == null || rawUrl.isBlank()) ? null : rawUrl.trim();

        if (kind.needsUrl()) {
            if (url == null)
                return Parsed.reject("Stream Link Required",
                        "`streaming` is the one activity Discord needs a link for. It is what"
                                + " turns the name purple and makes it clickable.\n\n"
                                + "Add `url:` with a Twitch or YouTube link, or pick another activity.");

            if (!Activity.isValidStreamingUrl(url))
                return Parsed.reject("Stream Link Not Accepted",
                        "Discord only accepts a **Twitch** or **YouTube** link here, and"
                                + " `" + url + "` is neither.\n\n"
                                + "For example: `url:https://twitch.tv/yourchannel`.");

        } else if (url != null) {
            // Silently dropping it would leave somebody believing they had set a link.
            return Parsed.reject("Link Not Usable Here",
                    "Only `streaming` uses a link. Discord ignores it for **" + kind.label()
                            + "**.\n\nDrop the `url:` option, or switch the activity to `streaming`.");
        }

        return new Parsed(
                new Setting(kind, text, url,
                        actor == null ? null : actor.getId(),
                        System.currentTimeMillis() / 1000L),
                null, null);
    }

    private static String inlineCodes(List<String> codes) {
        return String.join(", ", codes.stream().map(code -> "`" + code + "`").toList());
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The stored presence, or the built-in default when none has been set.
     *
     * <p>Never null, and never throws: a database that cannot be read must not stop the
     * bot from having a presence at all.
     */
    public static Setting current() {
        Setting cached = STORED.get(DOCUMENT_ID, key -> {
            try {
                Document doc = DB.getCollection(Document.class, COLLECTION)
                        .find(Filters.eq("_id", key))
                        .first();

                return doc == null ? defaultSetting() : fromDocument(doc);
            } catch (Exception e) {
                BunnyLog.error("[PresenceService] Could not read the stored presence", e);
                return null; // Keeps the failure out of the cache.
            }
        });

        return cached != null ? cached : defaultSetting();
    }

    /**
     * Reads a stored document, falling back to the default if it is unusable.
     *
     * <p>A hand-edited or half-written document must not leave the bot with no presence,
     * so anything that does not resolve is treated as "unset" rather than as an error.
     */
    private static Setting fromDocument(Document doc) {
        Kind kind = Kind.resolve(doc.getString("type"));
        String text = doc.getString("text");

        if (kind == null || text == null || text.isBlank())
            return defaultSetting();

        String url = doc.getString("url");
        if (kind.needsUrl() && (url == null || !Activity.isValidStreamingUrl(url)))
            return defaultSetting();

        Long updatedAt = doc.getLong("updatedAt");

        return new Setting(kind, text, kind.needsUrl() ? url : null,
                doc.getString("updatedBy"), updatedAt == null ? 0L : updatedAt);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Stores a presence and publishes it, in that order.
     *
     * <p>The database first, so a restart cannot revert to something an administrator was
     * told had been applied. The gateway second, from the <em>post-image</em> the write
     * returned rather than from the submitted input - so what the bot broadcasts and what
     * the confirmation claims are the same object.
     *
     * @return the stored setting, or null when the write failed and nothing was published.
     */
    public static Setting apply(JDA jda, Setting setting) {
        if (setting == null)
            return null;

        try {
            Document after = DB.getCollection(Document.class, COLLECTION).findOneAndUpdate(
                    Filters.eq("_id", DOCUMENT_ID),
                    Updates.combine(
                            Updates.set("type", setting.kind().name()),
                            Updates.set("text", setting.text()),
                            setting.url() == null
                                    ? Updates.unset("url")
                                    : Updates.set("url", setting.url()),
                            Updates.set("updatedBy", setting.updatedBy()),
                            Updates.set("updatedAt", setting.updatedAtEpochSeconds())),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

            Setting stored = after == null ? setting : fromDocument(after);
            STORED.put(DOCUMENT_ID, stored);

            publish(jda, stored);
            return stored;

        } catch (Exception e) {
            BunnyLog.error("[PresenceService] Could not save the presence", e);
            STORED.invalidate(DOCUMENT_ID);
            return null;
        }
    }

    /**
     * Clears the stored presence and returns the bot to its default.
     *
     * @return the default, now live, or null when the delete failed.
     */
    public static Setting reset(JDA jda) {
        try {
            DB.getCollection(Document.class, COLLECTION)
                    .deleteOne(Filters.eq("_id", DOCUMENT_ID));

            Setting fallback = defaultSetting();
            STORED.put(DOCUMENT_ID, fallback);

            publish(jda, fallback);
            return fallback;

        } catch (Exception e) {
            BunnyLog.error("[PresenceService] Could not clear the stored presence", e);
            STORED.invalidate(DOCUMENT_ID);
            return null;
        }
    }

    /**
     * Applies whatever is stored to a freshly-connected gateway.
     *
     * <p>Called once at startup. Reads the database, so it belongs on a worker rather than
     * on the thread that handed us the ready event - see the call site.
     */
    public static void applyStored(JDA jda) {
        Setting setting = current();
        publish(jda, setting);

        BunnyLog.info("[PresenceService] Presence set to \"" + setting.preview() + "\""
                + (setting.isDefault() ? " (default)." : "."));
    }

    /** Never throws: an unsettable presence is cosmetic and must not fail a startup. */
    private static void publish(JDA jda, Setting setting) {
        if (jda == null || setting == null)
            return;

        try {
            jda.getPresence().setActivity(setting.toActivity());
        } catch (Exception e) {
            BunnyLog.error("[PresenceService] Could not publish the presence to Discord", e);
        }
    }
}
