package org.bunnys.beastars.commands.ooc;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.bunnys.beastars.BeastarsEmoji;

import java.util.List;

/**
 * The controls attached to a posted OOC image.
 *
 * <p>Two buttons and no state: the reroll draws from the guild's album, which the handler
 * already knows from the interaction, so nothing needs carrying in the custom id. That is
 * deliberate rather than lazy, because it means an old message's button keeps working
 * after the album is repointed instead of silently serving the previous album.
 */
public final class OocComponents {

    /** Routes to {@code OocRerollButton}. */
    public static final String REROLL_PREFIX = "ooc_reroll";

    private OocComponents() {}

    /**
     * Reroll plus a direct link to the image.
     *
     * <p>The link button carries the current image, so it is rebuilt on every reroll
     * rather than left pointing at whatever was posted first.
     */
    public static List<ActionRow> imageControls(String imageUrl) {
        return List.of(ActionRow.of(
                Button.primary(REROLL_PREFIX, "Another")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.RANDOM)),
                Button.link(imageUrl, "View Original")));
    }
}
