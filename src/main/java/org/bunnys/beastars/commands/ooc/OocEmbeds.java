package org.bunnys.beastars.commands.ooc;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.utils.AppDesign;

import java.time.Instant;

/** Every embed the Imgur album feature can produce. */
public final class OocEmbeds {

    private OocEmbeds() {}

    public static MessageEmbed success(String title, String description) {
        return new EmbedBuilder()
                .setTitle(title)
                .setDescription(BeastarsEmoji.SUCCESS + " " + description)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now())
                .build();
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
     * Maps a failed draw onto the right wording.
     *
     * <p>Here rather than at the call sites because there are two of them now, the command
     * and the reroll button, and copy that is duplicated is copy that drifts.
     */
    public static MessageEmbed failure(OocService.Outcome outcome) {
        return switch (outcome) {
            case INVALID_URL -> error("Album Not Configured",
                    "The configured album link is not valid. An admin can fix it with `/ooc setlink`.");
            case EMPTY_ALBUM -> error("Album Empty",
                    "The configured album has no images in it.");
            default -> error("Album Unreachable",
                    "Could not fetch images. Make sure the album is public and try again.");
        };
    }
}
