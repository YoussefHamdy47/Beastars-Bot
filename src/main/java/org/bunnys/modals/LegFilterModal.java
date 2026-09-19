package org.bunnys.modals;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.beastars.commands.leg.LegComponents;
import org.bunnys.beastars.commands.leg.LegEmbeds;
import org.bunnys.beastars.commands.leg.LegLeaderboardFilters;
import org.bunnys.buttons.LegLeaderboardButton;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;

import java.util.ArrayList;
import java.util.List;

/**
 * The roles chosen in the leaderboard's filter dialog.
 *
 * <p>Stores the choice against the message it came from, then re-renders that message
 * through the same path the pagination buttons use - so a filtered board pages, finds
 * and clears exactly like an unfiltered one.
 */
public class LegFilterModal extends BunnyModal {

    @Override
    public String getPrefix() {
        return LegComponents.FILTER_MODAL;
    }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        if (event.getGuild() == null || event.getMessage() == null)
            return;

        var mapping = event.getValue(LegComponents.INPUT_FILTER_ROLES);
        List<String> chosen = new ArrayList<>();

        if (mapping != null)
            chosen.addAll(mapping.getAsStringList());

        // Re-validated against the guild: a role can be deleted between the dialog
        // opening and it being submitted, and a filter on a role nobody holds any more
        // would silently empty the board.
        List<String> valid = new ArrayList<>();
        for (String id : chosen)
            if (event.getGuild().getRoleById(id) != null)
                valid.add(id);

        if (!chosen.isEmpty() && valid.isEmpty()) {
            event.replyEmbeds(LegEmbeds.error("Roles Not Found",
                    "Those roles no longer exist in this server.")).setEphemeral(true).queue(null, e -> {});
            return;
        }

        String messageId = event.getMessage().getId();
        LegLeaderboardFilters.set(messageId, valid);

        event.deferEdit().queue();

        // The board keeps its own session, so applying a filter does not silently thaw a
        // frozen board or freeze a live one. The modal id carries nothing, so the session
        // is read back off the buttons the message already has.
        LegComponents.Session session = sessionOf(event, event.getUser().getId());

        // Back to page one: the page somebody was on has no meaning once the set of rows
        // underneath it changes.
        LegLeaderboardButton.render(event.getHook(), event.getGuild(), messageId,
                1, null, session);
    }

    /**
     * Recovers the board's session from the buttons on the message the modal came from.
     *
     * <p>The filter modal has a fixed custom id and so carries no session of its own.
     * Rather than inventing one, which would reset a frozen board to a fresh snapshot on
     * every filter change, this reads it back off any leaderboard button already on the
     * message. Falling back to a new frozen session only happens if the message has no
     * recognisable controls left, in which case there is nothing to preserve anyway.
     */
    private static LegComponents.Session sessionOf(ModalInteractionEvent event, String callerId) {
        if (event.getMessage() != null) {
            String prefix = LegComponents.LEADERBOARD_PREFIX + ":";

            for (Button button : event.getMessage().getComponentTree().findAll(Button.class)) {
                String id = button.getCustomId();
                if (id == null || !id.startsWith(prefix))
                    continue;

                LegComponents.Session parsed = LegComponents.parseSession(id.split(":"));
                if (parsed != null)
                    return parsed;
            }
        }

        return LegComponents.Session.opening(callerId, true);
    }
}
