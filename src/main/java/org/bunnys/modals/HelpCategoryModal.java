package org.bunnys.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.beastars.help.HelpMenuManager;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.router.modals.BunnyModal;

import java.util.ArrayList;
import java.util.List;

/**
 * The category chosen in the help menu's <b>Browse</b> dialog.
 *
 * <p>Edits the message the button was clicked on, so the reader lands on the category
 * they picked rather than getting a second, separate help message beside the first.
 */
public class HelpCategoryModal extends BunnyModal {

    @Override
    public String getPrefix() {
        return HelpMenuManager.BROWSE_MODAL;
    }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        var mapping = event.getValue(HelpMenuManager.INPUT_CATEGORY);
        List<String> chosen = mapping == null ? List.of() : mapping.getAsStringList();

        List<BunnyCommand> commands = new ArrayList<>(client.getCommandRegistry().getCommands().values());

        // Nothing selected, or a category that has since disappeared, both resolve to
        // page 1 rather than failing - the reader still gets a usable help menu.
        int page = chosen.isEmpty() ? 1 : HelpMenuManager.categoryPage(commands, chosen.get(0));

        String botName = event.getJDA().getSelfUser().getName();
        String avatar = event.getJDA().getSelfUser().getEffectiveAvatarUrl();

        // editComponents rather than reply: the modal was opened from the help message,
        // and that message is what should change.
        event.editMessageEmbeds(HelpMenuManager.getHelpPage(botName, avatar, commands, page))
                .setComponents(HelpMenuManager.getIndexComponents(commands, page))
                .queue(null, e -> {});
    }
}
