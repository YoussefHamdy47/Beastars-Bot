package org.bunnys.beastars.commands.presence;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.presence.PresenceService.Setting;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Timestamps;

import java.time.Instant;

/**
 * Every embed the presence command can produce.
 *
 * <p>House style throughout: plain-text titles and footers, descriptions and field values
 * leading with a {@link BeastarsEmoji}, and {@link AppDesign.ColorCodes#ERROR_RED}
 * reserved for genuine failures so red still means something.
 */
public final class PresenceEmbeds {

    private PresenceEmbeds() {}

    private static EmbedBuilder base(String title) {
        return new EmbedBuilder()
                .setTitle(title)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now());
    }

    public static MessageEmbed error(String title, String description) {
        return new EmbedBuilder()
                .setTitle(title)
                .setDescription(BeastarsEmoji.FAILURE + " " + description)
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Confirms a change, rendered from the stored post-image.
     *
     * <p>Shows the presence exactly as Discord will render it - verb included - because
     * "Listening to" versus "Playing" is the whole point of the setting and reading it
     * back as a bare string would hide the part that was chosen.
     */
    public static MessageEmbed updated(Setting setting) {
        EmbedBuilder embed = base("Presence Updated")
                .setDescription(BeastarsEmoji.EDIT + " Every server now sees **"
                        + setting.preview() + "** under my name.")
                .addField("Activity", BeastarsEmoji.PARAMETER + " " + setting.kind().label(), true)
                .addField("Text", BeastarsEmoji.LOG + " " + setting.text(), true);

        if (setting.url() != null)
            embed.addField("Stream Link", BeastarsEmoji.SERVER + " " + setting.url(), false);

        return embed.setFooter("This applies everywhere the bot is, not just this server").build();
    }

    /** The current presence, whether somebody set it or it is still the default. */
    public static MessageEmbed overview(Setting setting) {
        EmbedBuilder embed = base("Current Presence")
                .setDescription(BeastarsEmoji.PANEL + " I am showing **" + setting.preview()
                        + "** to every server I am in.")
                .addField("Activity", BeastarsEmoji.PARAMETER + " " + setting.kind().label(), true)
                .addField("Text", BeastarsEmoji.LOG + " " + setting.text(), true);

        if (setting.url() != null)
            embed.addField("Stream Link", BeastarsEmoji.SERVER + " " + setting.url(), false);

        embed.addField("Set By", setting.isDefault()
                        ? BeastarsEmoji.EMPTY + " *Nobody yet. This is the built-in default.*"
                        : BeastarsEmoji.SUCCESS + " <@" + setting.updatedBy() + ">\n"
                        + Timestamps.relative(setting.updatedAtEpochSeconds()),
                false);

        return embed.build();
    }

    public static MessageEmbed reset(Setting setting) {
        return base("Presence Reset")
                .setDescription(BeastarsEmoji.RESET + " Back to the default. Every server now sees **"
                        + setting.preview() + "** under my name.")
                .build();
    }

}
