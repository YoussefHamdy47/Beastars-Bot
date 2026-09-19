package org.bunnys.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.admin.GuildSettingsService;
import org.bunnys.handler.utils.TokenLoader;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * One place where a crash becomes three things: a console line, a Discord report, and a
 * reference the user can quote back.
 *
 * <h2>The reference</h2>
 * Every report gets a short random id, printed to the console, shown in the Discord
 * report, and shown to the user in the error embed. That turns "the bot broke" into
 * "the bot broke, reference 7f3a91c4", which finds the exact incident in one search.
 * Without it a user report and a log line can only be matched by guessing at timestamps.
 *
 * <h2>Why Discord and not a file</h2>
 * This bot targets hardware that boots off an SD card, where a log file is a slow way of
 * wearing out the storage. Discord is already a durable, searchable, remotely readable
 * log that costs the host nothing - so crashes go to a channel instead, and the disk is
 * never touched.
 *
 * <h2>Where a report goes</h2>
 * <ul>
 *   <li>{@code DEVELOPER_CHANNEL_ID} from the environment, always, if the running bot can
 *       see it. Set once at deployment so crash reporting works before any guild has
 *       configured anything, and it cannot be switched off by a guild admin.</li>
 *   <li>The originating guild's own error channel, when one is configured. A guild only
 *       ever sees crashes that happened inside it - one server's failures are never
 *       published into another's.</li>
 * </ul>
 *
 * <h2>Not making things worse</h2>
 * A crash reporter that can itself crash, or that floods a channel during a crash loop,
 * is worse than none. So: every send is fire-and-forget, a send failure is reported to
 * the console and never back through {@link #report}, and identical crashes are
 * throttled to one Discord message per {@link #THROTTLE_SECONDS}. The console line is
 * never throttled - that is the complete record.
 */
public final class ErrorReporter {

    /**
     * The developer channel, from the environment.
     *
     * <p>Was compiled in. A channel id names a private channel, and a published repository
     * should not. Unset means crash reports go to the console and to whatever channel each
     * guild configures, which is a working deployment rather than a broken one.
     */
    private static final String DEVELOPER_CHANNEL_ID = TokenLoader.optional("DEVELOPER_CHANNEL_ID");

    /** How long an identical crash is suppressed for, in Discord only. */
    private static final long THROTTLE_SECONDS = 60L;

    /** Discord caps a field value at 1024; a stack trace has to fit inside a code block. */
    private static final int MAX_TRACE_CHARS = 900;

    /**
     * Identical crashes seen recently, keyed by context and exception type.
     *
     * <p>A hot loop can throw thousands of times a minute. Without this the channel
     * becomes unreadable at exactly the moment somebody needs to read it, and the bot
     * spends its rate limit on saying the same thing.
     */
    private static final Cache<String, Boolean> RECENTLY_SENT = CacheRegistry.register("errors.recently_sent", Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(THROTTLE_SECONDS, TimeUnit.SECONDS)
            .recordStats()
            .build());

    /** Null until the gateway is up; crashes before that are console-only. */
    private static volatile JDA jda;

    private ErrorReporter() {}

    /** Hands the reporter the running bot, once it can actually send anything. */
    public static void attach(JDA runningJda) {
        jda = runningJda;
    }

    /** Where crash reports are going, for the startup summary. */
    public static String destination() {
        if (jda == null)
            return "console only (not connected yet)";

        if (DEVELOPER_CHANNEL_ID == null)
            return "console  ·  per-guild error channels (no developer channel configured)";

        TextChannel developer = developerChannel();
        return developer == null
                ? "console only (the configured developer channel is not visible)"
                : "console  ·  #" + developer.getName() + "  ·  per-guild error channels";
    }

    /**
     * Records a crash everywhere it belongs.
     *
     * @param context where it happened, e.g. {@code "/manga"} or {@code "button leg_offer"}
     * @param guildId the guild it happened in, or null outside one
     * @return the reference id to show the user
     */
    public static String report(String context, String guildId, Throwable error) {
        String reference = newReference();

        BunnyLog.error("[" + context + "] crashed  (ref " + reference + ")", error);
        dispatch(reference, context, guildId, error);

        return reference;
    }

    /** For crashes with no guild behind them. */
    public static String report(String context, Throwable error) {
        return report(context, null, error);
    }

    /**
     * Records a crash and tells the user, in one call.
     *
     * <p>For the component routers, which hold a raw interaction rather than a
     * {@code CommandContext}. Every interaction can answer ephemerally, so nothing here
     * needs the self-deleting fallback the mention path requires - and an ephemeral
     * notice never needs to warn that it will disappear.
     *
     * @param event the interaction that crashed; left alone if something already answered it
     */
    public static void reportAndReply(String context, Throwable error, IReplyCallback event) {
        String guildId = (event != null && event.getGuild() != null) ? event.getGuild().getId() : null;
        String reference = report(context, guildId, error);

        if (event == null || event.isAcknowledged())
            return;

        event.replyEmbeds(SystemEmbeds.crashed(reference, false)).setEphemeral(true)
                .queue(null, e -> BunnyLog.warning("[ErrorReporter] Could not deliver crash notice for ref "
                        + reference + ": " + e.getMessage()));
    }

    private static String newReference() {
        // Only has to be unique among the handful of crashes a human is looking at, so
        // eight hex characters is plenty and stays short enough to read aloud.
        return String.format("%08x", ThreadLocalRandom.current().nextInt());
    }

    // ------------------------------------------------------------------
    // Discord dispatch
    // ------------------------------------------------------------------

    private static void dispatch(String reference, String context, String guildId, Throwable error) {
        JDA client = jda;
        if (client == null)
            return;

        // Everything below is best-effort. A failure here must never reach report()
        // again, or one broken channel becomes an unbounded recursion.
        try {
            if (isThrottled(context, error))
                return;

            MessageEmbed embed = reportEmbed(reference, context, guildId, error);

            TextChannel developer = developerChannel();
            if (developer != null)
                send(developer, embed, reference);

            TextChannel guildChannel = guildChannel(client, guildId);
            if (guildChannel != null && (developer == null || !guildChannel.getId().equals(developer.getId())))
                send(guildChannel, embed, reference);

        } catch (Throwable suppressed) {
            BunnyLog.warning("[ErrorReporter] Could not report ref " + reference
                    + " to Discord: " + suppressed);
        }
    }

    /**
     * @return true when an identical crash was already reported inside the window
     */
    private static boolean isThrottled(String context, Throwable error) {
        String key = context + "|" + (error == null ? "none" : error.getClass().getName());

        // putIfAbsent returns the existing value, so a non-null answer means somebody
        // already claimed this key inside the window.
        return RECENTLY_SENT.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }

    private static TextChannel developerChannel() {
        JDA client = jda;
        if (client == null)
            return null;

        // Unconfigured, or the bot is not in that server: both simply skip this.
        if (DEVELOPER_CHANNEL_ID == null)
            return null;

        try {
            return client.getTextChannelById(DEVELOPER_CHANNEL_ID);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static TextChannel guildChannel(JDA client, String guildId) {
        if (guildId == null)
            return null;

        String channelId = GuildSettingsService.getErrorChannelId(guildId);
        if (channelId == null)
            return null;

        var guild = client.getGuildById(guildId);
        return guild == null ? null : guild.getTextChannelById(channelId);
    }

    private static void send(TextChannel channel, MessageEmbed embed, String reference) {
        if (!channel.canTalk())
            return;

        channel.sendMessageEmbeds(embed).queue(
                null,
                e -> BunnyLog.warning("[ErrorReporter] Could not post ref " + reference
                        + " to #" + channel.getName() + ": " + e.getMessage()));
    }

    private static MessageEmbed reportEmbed(String reference, String context,
                                            String guildId, Throwable error) {
        return new EmbedBuilder()
                .setTitle("Unhandled Error")
                .setDescription(BeastarsEmoji.FAILURE + " `" + context + "` failed.")
                .addField("Reference", BeastarsEmoji.PANEL + " `" + reference + "`", true)
                .addField("Origin", BeastarsEmoji.SERVER + " "
                        + (guildId == null ? "Outside a server" : "`" + guildId + "`"), true)
                .addField("Exception", BeastarsEmoji.FAILURE + " `"
                        + (error == null ? "unknown" : error.getClass().getSimpleName()) + "`", true)
                .addField("Message", BeastarsEmoji.PARAMETER + " "
                        + summarise(error), false)
                .addField("Stack Trace", "```\n" + trace(error) + "\n```", false)
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setFooter("Identical errors are reported at most once per "
                        + THROTTLE_SECONDS + " seconds")
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Patterns that must never reach a Discord channel.
     *
     * <p>A crash report is published - to the developer channel, and to a channel a guild
     * admin chose. Exception text is not written with that in mind: a MongoDB driver
     * failure can carry the connection string it was given, and the Google Drive key
     * travels in a query parameter because Google's API takes it no other way, which is
     * the classic route for a key to end up in a log. Redaction happens on the way out,
     * once, rather than relying on every future upstream to be discreet.
     */
    private static final java.util.regex.Pattern[] SECRETS = {
            // mongodb://user:pass@host and the +srv form - credentials only, host kept
            // because knowing which cluster failed is the point of the report.
            java.util.regex.Pattern.compile("(mongodb(?:\\+srv)?://)[^:@/\\s]+:[^@/\\s]+@"),
            // ?key=... or &key=... / api_key / apikey / access_token
            java.util.regex.Pattern.compile("([?&](?:api_?key|key|access_token)=)[^&\\s\"']+",
                    java.util.regex.Pattern.CASE_INSENSITIVE),
            // Authorization headers, however they are spelled.
            java.util.regex.Pattern.compile("((?:Client-ID|Bearer|Bot)\\s+)[\\w.\\-]{8,}",
                    java.util.regex.Pattern.CASE_INSENSITIVE),
            // A Discord bot token's three dot-separated segments.
            java.util.regex.Pattern.compile("[\\w-]{23,28}\\.[\\w-]{6}\\.[\\w-]{27,}")
    };

    /**
     * Strips anything secret-shaped from text bound for Discord.
     *
     * <p>Deliberately applied to the rendered text rather than to the sources: it cannot
     * know what a future exception will decide to include, so it filters the one place
     * everything passes through.
     */
    static String redact(String text) {
        if (text == null || text.isEmpty())
            return text;

        String safe = text;
        for (int i = 0; i < SECRETS.length; i++)
            // The last pattern has no capture group to preserve; the rest keep their
            // prefix so the report still says *what* was redacted.
            safe = SECRETS[i].matcher(safe).replaceAll(i == SECRETS.length - 1 ? "[REDACTED]" : "$1[REDACTED]");

        return safe;
    }

    private static String summarise(Throwable error) {
        if (error == null || error.getMessage() == null)
            return "*No message.*";

        String message = redact(error.getMessage());
        return "`" + (message.length() > 300 ? message.substring(0, 297) + "..." : message) + "`";
    }

    /**
     * The top of the stack, trimmed to fit a field.
     *
     * <p>The first frames are the ones that identify the fault; the tail is executor and
     * gateway plumbing that is identical for every crash in the bot.
     */
    private static String trace(Throwable error) {
        if (error == null)
            return "(no throwable supplied)";

        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            error.printStackTrace(writer);
        }

        // Redact before truncating: a secret sitting past the cut would otherwise be
        // scanned out of the wrong half, and the fence-breaking guard must apply to the
        // redacted text so a payload cannot smuggle in a closing fence either.
        String full = redact(buffer.toString()).replace("```", "`​``");

        return full.length() > MAX_TRACE_CHARS
                ? full.substring(0, MAX_TRACE_CHARS) + "\n... truncated, see console"
                : full;
    }
}
