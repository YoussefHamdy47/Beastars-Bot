package org.bunnys.beastars.commands.manga;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.manga.MangaService.PageResult;
import org.bunnys.utils.AppDesign;

import java.time.Instant;

/**
 * Every embed the manga reader can produce.
 *
 * <p>The old page embed had a bare chapter title and a raw hex colour; error paths
 * were plain strings sent with {@code sendMessage}, so a failed page turn produced
 * unstyled text in the middle of a styled reader.
 */
public final class MangaEmbeds {

    /** Attachment name the reader uploads each page under. */
    public static final String PAGE_ATTACHMENT = "page.png";

    private MangaEmbeds() {}

    /**
     * The reader itself.
     *
     * @param action   what the reader last did, shown in the footer
     * @param userName who did it
     */
    public static MessageEmbed page(PageResult result, String action, String userName) {
        String seriesName = MangaCatalog.seriesName(result.ref().series());

        return new EmbedBuilder()
                .setTitle(clampTitle(result.title()))
                .setDescription(BeastarsEmoji.PAGE + " ["
                        + seriesName + " | " + result.ref().group() + " | " + result.ref().source()
                        + "](" + result.rawUrl() + ")")
                .setImage("attachment://" + PAGE_ATTACHMENT)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Last Action: " + userName + " | " + action
                        + " • " + result.page() + " / " + result.maxPages())
                .build();
    }

    /**
     * Keeps an upstream chapter title inside Discord's embed-title limit.
     *
     * <p>The title is whatever MangaDex has stored, which is not a length this bot
     * controls. {@code setTitle} throws over {@value MessageEmbed#TITLE_MAX_LENGTH}
     * characters, and that throw would escape the render - so one over-long title upstream
     * would take out the whole reader, page turns included, with a generic error and
     * nothing pointing at the cause.
     */
    private static String clampTitle(String title) {
        if (title == null || title.length() <= MessageEmbed.TITLE_MAX_LENGTH)
            return title;
        return title.substring(0, MessageEmbed.TITLE_MAX_LENGTH - 1) + "...";
    }

    public static MessageEmbed error(String title, String description) {
        return new EmbedBuilder()
                .setTitle(title)
                .setDescription(BeastarsEmoji.FAILURE + " " + description)
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    /** Someone clicked a control on a reader they did not open. */
    public static MessageEmbed notYourSession() {
        return new EmbedBuilder()
                .setTitle("Not Your Session")
                .setDescription(BeastarsEmoji.NOT_YOURS
                        + " This is not your reading session. Run `/manga` to start your own.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    public static MessageEmbed sessionExpired() {
        return new EmbedBuilder()
                .setTitle("Session Expired")
                .setDescription(BeastarsEmoji.EXPIRED
                        + " This reading session timed out after 5 minutes of inactivity. Run `/manga` to start again.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }
}
