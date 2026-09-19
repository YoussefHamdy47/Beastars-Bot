package org.bunnys.beastars.commands.image;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.database.ImageData;
import org.bunnys.utils.AppDesign;

import java.time.Instant;
import java.util.List;

/** Every embed the image-shortcut feature can produce. */
public final class ImageEmbeds {

    /** Discord truncates embed descriptions past this. */
    private static final int DESCRIPTION_LIMIT = 4096;

    private ImageEmbeds() {}

    public static MessageEmbed image(ImageData.ImageEntry entry, String userName) {
        return new EmbedBuilder()
                .setTitle(entry.getName())
                .setDescription(BeastarsEmoji.IMAGE + " [Open original image](" + entry.getUrl() + ")")
                .setImage(entry.getUrl())
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Requested by " + userName + " • Image Shortcuts")
                .setTimestamp(Instant.now())
                .build();
    }

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

    public static MessageEmbed denied(String description) {
        return new EmbedBuilder()
                .setTitle("Permission Denied")
                .setDescription(BeastarsEmoji.DENIED + " " + description)
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * The shortcut catalogue.
     *
     * <p>Truncates rather than letting Discord reject the whole embed - a guild with
     * enough shortcuts to overflow the description used to get nothing back at all.
     */
    public static MessageEmbed catalogue(List<ImageData.ImageEntry> images) {
        if (images.isEmpty())
            return new EmbedBuilder()
                    .setTitle("Custom Server Images")
                    .setDescription(BeastarsEmoji.EMPTY
                            + " There are no custom image shortcuts saved for this server yet.\n"
                            + "Admins can add one with `/image add [name] [url]`.")
                    .setColor(AppDesign.ColorCodes.DEFAULT)
                    .setTimestamp(Instant.now())
                    .build();

        StringBuilder body = new StringBuilder(BeastarsEmoji.GALLERY + " Saved shortcuts:\n\n");
        int shown = 0;

        for (ImageData.ImageEntry entry : images) {
            String line = "**" + (shown + 1) + ".** `" + entry.getName()
                    + "`: [View image](" + entry.getUrl() + ")\n";

            if (body.length() + line.length() > DESCRIPTION_LIMIT - 64)
                break;

            body.append(line);
            shown++;
        }

        if (shown < images.size())
            body.append("\n*...and ").append(images.size() - shown).append(" more.*");

        return new EmbedBuilder()
                .setTitle("Custom Server Images (" + images.size() + ")")
                .setDescription(body.toString())
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now())
                .build();
    }
}
