package org.bunnys.utils;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import org.bunnys.beastars.BeastarsEmoji;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-points the emoji constants at whichever application is actually running.
 *
 * <h2>The problem</h2>
 * Application emojis belong to the bot <em>application</em>, not to a guild. Upload the
 * same artwork to a test application and a production application and you get identical
 * names but completely different snowflake IDs. {@link AppDesign} stores fully-formed
 * {@code <:Name:id>} literals generated against one of them, so running the other bot
 * renders every emoji as broken raw text.
 *
 * <h2>The fix</h2>
 * The <b>name</b> is the stable identity, and it is already embedded in every stored
 * literal. At startup this fetches the running application's emojis and rewrites each
 * constant in place, matching on that name and swapping in the live ID.
 *
 * <p>That makes the source constants a <em>fallback</em> rather than the truth, so the
 * same build works against any bot token with no regeneration step and no code changes
 * at any call site. {@code AppDesign.java} only needs regenerating when emoji are added
 * or renamed - see {@code setReloadEmojis} on the builder for that.
 *
 * <p>Constants are only overwritten when a live match is found, and a response with no
 * emoji at all is treated as a failed call rather than a mass deletion. A bad sync can
 * therefore never leave the bot worse off than the compiled-in defaults.
 */
public final class EmojiRegistry {

    /** Matches a stored literal, capturing the emoji name: {@code <a?:Name:id>}. */
    private static final Pattern EMOJI_LITERAL = Pattern.compile("<a?:([A-Za-z0-9_]+):\\d+>");

    private static volatile int liveCount;
    private static volatile int reboundCount;

    /**
     * Publication fence for the rebound constants. Written <b>last</b> in
     * {@link #rebind}, after every field assignment - that ordering is the whole
     * point and must not be moved.
     *
     * @see #fence()
     */
    private static volatile boolean published;

    private EmojiRegistry() {}


    /**
     * Publication fence. Call once on an entry path before any emoji constant is read.
     *
     * <h2>Why this exists</h2>
     * {@link BeastarsEmoji}'s fields are plain (non-volatile) statics, written here from
     * a JDA callback thread and read later by command workers. Without a
     * happens-before edge between those two threads the JMM permits a worker to observe
     * the compiled-in fallback rather than the rebound value.
     *
     * <p>The consequence is mild - the fallback is a valid emoji literal, so the worst
     * case is the documented "wrong application's ID" behaviour - but it is a real data
     * race, and the cure is normally {@code volatile} on all sixty-odd fields. That would
     * put an acquire barrier on every embed render forever to fix a startup-only hazard.
     *
     * <p>One volatile read is enough instead. {@code published} is stored after every
     * field write, so a thread that reads it {@code true} has an edge covering all of
     * them, and a thread that then hands work to the bounded executor passes that edge on
     * through the queue. Reading it before the race window has closed simply yields
     * {@code false} and the fallback - which is correct, because at that point the sync
     * genuinely has not happened.
     *
     * <p>The returned value is incidental; the <em>read</em> is the point. A volatile read
     * is a synchronization action and is never elided, used or not.
     *
     * @return whether the rebind has completed
     */
    public static boolean fence() {
        return published;
    }

    /** How many application emojis the running bot reported. */
    public static int liveCount() {
        return liveCount;
    }

    /** How many constants were re-pointed at live IDs. */
    public static int reboundCount() {
        return reboundCount;
    }

    /**
     * Fetches the running application's emojis and re-points the constants.
     *
     * <p>Asynchronous and non-fatal: a failure leaves the compiled-in literals in place,
     * which is exactly the behaviour the bot had before this existed.
     *
     * @param onComplete run once the outcome is known, whichever way it went, so a
     *                   caller can report accurate counts rather than racing the fetch.
     */
    public static void sync(JDA jda, Runnable onComplete) {
        jda.retrieveApplicationEmojis().queue(
                emojis -> {
                    rebind(emojis);
                    onComplete.run();
                },
                error -> {
                    BunnyLog.warning("[EmojiRegistry] Could not fetch application emojis ("
                            + error.getMessage() + "). Using the compiled-in IDs.");
                    onComplete.run();
                });
    }

    private static void rebind(List<ApplicationEmoji> emojis) {
        liveCount = emojis.size();

        if (emojis.isEmpty()) {
            // Either this application genuinely has none, or the call came back empty.
            // Overwriting on that basis would blank every emoji in the bot.
            BunnyLog.warning("[EmojiRegistry] The application reported 0 emojis; keeping compiled-in IDs.");
            return;
        }

        // Case-insensitive: Discord preserves the case you upload with, and the point of
        // this class is to survive the same artwork being re-uploaded elsewhere.
        Map<String, String> live = new HashMap<>();
        for (ApplicationEmoji emoji : emojis)
            live.put(emoji.getName().toLowerCase(), emoji.getFormatted());

        int rebound = 0;
        int unchanged = 0;
        int missing = 0;

        for (Field field : BeastarsEmoji.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();

            // Only the mutable String aliases; anything final is a compile-time constant
            // that consumers have already inlined, so rewriting it would achieve nothing.
            if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.getType() != String.class)
                continue;

            try {
                String current = (String) field.get(null);
                if (current == null)
                    continue;

                Matcher matcher = EMOJI_LITERAL.matcher(current);
                if (!matcher.matches())
                    continue; // Not an application emoji literal; leave it alone.

                String replacement = live.get(matcher.group(1).toLowerCase());

                if (replacement == null) {
                    missing++;
                    BunnyLog.warning("[EmojiRegistry] '" + matcher.group(1)
                            + "' is not uploaded to this application; " + field.getName()
                            + " will render as raw text.");
                } else if (replacement.equals(current)) {
                    unchanged++;
                } else {
                    field.set(null, replacement);
                    rebound++;
                }
            } catch (IllegalAccessException e) {
                BunnyLog.warning("[EmojiRegistry] Could not rebind " + field.getName() + ": " + e.getMessage());
            }
        }

        reboundCount = rebound;

        // MUST be the final write. Everything above happens-before this store, so a
        // reader that observes `true` also observes every rebound field. See fence().
        published = true;

        if (rebound > 0)
            BunnyLog.success("[EmojiRegistry] Re-pointed " + rebound + " emoji constant(s) at this application"
                    + " (" + unchanged + " already correct"
                    + (missing > 0 ? ", " + missing + " missing" : "") + ").");
        else
            BunnyLog.info("[EmojiRegistry] All " + unchanged + " emoji constant(s) already match this application"
                    + (missing > 0 ? " (" + missing + " missing)" : "") + ".");
    }
}
