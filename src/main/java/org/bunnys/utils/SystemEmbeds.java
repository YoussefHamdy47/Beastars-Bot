package org.bunnys.utils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.handler.commands.context.CommandContext;

import java.time.Instant;
import java.util.Collection;

/**
 * The framework's own user-facing embeds: permission denials, rate limits, crashes.
 *
 * <p>These were previously built inline inside {@code InteractionListener} - nine
 * separate {@code EmbedBuilder} chains that drifted from the feature embeds in colour,
 * title style and tone. Centralising them is what makes the house style actually
 * uniform rather than uniform-per-feature.
 *
 * <h2>House style</h2>
 * <ul>
 *   <li>Titles, field names and footers are <b>plain text</b> - Discord will not render
 *       a custom emoji there, and Unicode emoji are banned by the design system.</li>
 *   <li>Descriptions lead with a {@link BeastarsEmoji} constant.</li>
 *   <li>Colour is {@link AppDesign.ColorCodes#DEFAULT} for everything except genuine
 *       failures, which use {@link AppDesign.ColorCodes#ERROR_RED}.</li>
 * </ul>
 */
public final class SystemEmbeds {

    private SystemEmbeds() {}

    /** The bot's standard informational embed. */
    public static MessageEmbed notice(String title, String description) {
        return build(title, BeastarsEmoji.PANEL, description, false);
    }

    /** An action succeeded. */
    public static MessageEmbed success(String title, String description) {
        return build(title, BeastarsEmoji.SUCCESS, description, false);
    }

    /** The caller is not allowed to do this. */
    public static MessageEmbed denied(String title, String description) {
        return build(title, BeastarsEmoji.DENIED, description, true);
    }

    /** Bad input, or a rule refused the action. Not a crash. */
    public static MessageEmbed warning(String title, String description) {
        return build(title, BeastarsEmoji.CONFIRM, description, true);
    }

    /** Something in the plumbing broke. */
    public static MessageEmbed error(String title, String description) {
        return build(title, BeastarsEmoji.FAILURE, description, true);
    }

    // ------------------------------------------------------------------
    // Framework-specific
    // ------------------------------------------------------------------

    public static MessageEmbed rateLimited(String userMention, long expiresAtEpochSeconds) {
        return build("Slow Down", BeastarsEmoji.EXPIRED,
                userMention + ", you can use this again " + Timestamps.relative(expiresAtEpochSeconds)
                        + ".", true);
    }

    /**
     * A button was clicked again too soon.
     *
     * <p>Rendered as a Discord relative timestamp rather than a fixed string. A baked-in
     * "wait 2.6 seconds" is stale the instant it is sent and never updates; {@code <t:...:R>}
     * is rendered client-side, so the reader sees a live countdown that reaches zero on
     * its own and stays accurate however long the message sits on screen.
     *
     * <p>Discord's relative format has one-second granularity, so sub-second remainders
     * round up to the next whole second - better to say "in 1 second" and be briefly
     * pessimistic than to say "now" while the click is still blocked.
     */
    public static MessageEmbed buttonCooldown(long remainingMillis) {
        long expiresAtSeconds = (System.currentTimeMillis() + Math.max(0, remainingMillis) + 999) / 1000L;

        return build("Slow Down", BeastarsEmoji.EXPIRED,
                "You are clicking too fast. Please wait until " + Timestamps.relative(expiresAtSeconds) + ".", true);
    }

    public static MessageEmbed busy() {
        return build("Too Busy Right Now", BeastarsEmoji.EXHAUSTED,
                "The bot is under heavy load. Please try again in a few seconds.", true);
    }

    /**
     * A crash the user can report usefully.
     *
     * <p>The reference is the same id {@link org.bunnys.utils.ErrorReporter} printed to
     * the console and sent to the error channel. Quoting it back finds the exact stack
     * trace, which is the difference between a bug report that can be acted on and
     * "it broke earlier".
     *
     * @param vanishes whether this message will delete itself shortly. Said out loud
     *                 only when true - a user who is about to lose the reference needs
     *                 to know to copy it now, and telling someone their <em>ephemeral</em>
     *                 message will disappear is noise, since nobody else can see it anyway.
     */
    public static MessageEmbed crashed(String reference, boolean vanishes) {
        String body = "An unexpected error interrupted that command. The incident has been logged.\n"
                + "If you report this, quote reference `" + reference + "`.";

        if (vanishes)
            body += "\n*This message will delete itself in "
                    + CommandContext.TRANSIENT_SECONDS + " seconds.*";

        return build("Process Aborted", BeastarsEmoji.FAILURE, body, true);
    }

    public static MessageEmbed missingImplementation(String commandName, Collection<String> developerIds) {
        StringBuilder mentions = new StringBuilder();
        for (String id : developerIds)
            mentions.append("<@").append(id).append("> ");

        return build("Command Not Wired Up", BeastarsEmoji.FAILURE,
                "`" + commandName + "` has no execution logic. Please notify a developer: "
                        + mentions.toString().trim(), true);
    }

    /**
     * Explains that a command was reached by mention but needs the slash form.
     *
     * <p>Discord only permits a modal in response to an interaction, so anything that
     * opens a form is genuinely slash-only. Saying so is better than the alternative,
     * which is the bot appearing to ignore the user.
     */
    public static MessageEmbed slashOnly(String commandPath) {
        return build("Use the Slash Command", BeastarsEmoji.SLASH,
                "`/" + commandPath + "` opens a form, and Discord only allows those from slash commands.\n"
                        + "Run `/" + commandPath + "` instead.", true);
    }

    private static MessageEmbed build(String title, String emoji, String description, boolean failure) {
        return new EmbedBuilder()
                .setTitle(title)
                .setDescription(emoji + " " + description)
                .setColor(failure ? AppDesign.ColorCodes.ERROR_RED : AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now())
                .build();
    }
}
