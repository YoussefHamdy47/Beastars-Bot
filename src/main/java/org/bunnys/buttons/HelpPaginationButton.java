package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.beastars.help.HelpMenuManager;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.router.buttons.BunnyButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Every control on the help menu: paging, both ends, the category chooser, and the way
 * back from a command detail card.
 *
 * <p>Not caller-locked: help is public information, and locking the buttons to whoever
 * ran it only annoys the next person to read the message.
 */
public class HelpPaginationButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return "help";
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 3)
            return;

        List<BunnyCommand> commands = new ArrayList<>(client.getCommandRegistry().getCommands().values());
        int currentPage = parsePage(args[2]);
        int totalPages = HelpMenuManager.getTotalPages(commands);

        // The chooser is a modal, so it replies to the interaction rather than editing
        // the message. It has to return before the render below runs.
        if ("browse".equals(args[1])) {
            event.replyModal(HelpMenuManager.categoryModal(commands, currentPage)).queue(null, e -> {});
            return;
        }

        int target = switch (args[1]) {
            case "next" -> currentPage + 1;
            case "prev" -> currentPage - 1;
            case "first" -> 1;
            case "last" -> totalPages;
            // "back" leaves a detail card for the index page it was opened from.
            default -> currentPage;
        };

        // HelpMenuManager clamps, but the buttons need the clamped value too or a
        // click at the boundary would render page N while labelling itself N+1.
        int page = Math.max(1, Math.min(target, totalPages));

        String botName = event.getJDA().getSelfUser().getName();
        String avatar = event.getJDA().getSelfUser().getEffectiveAvatarUrl();

        event.editMessageEmbeds(HelpMenuManager.getHelpPage(botName, avatar, commands, page))
                .setComponents(HelpMenuManager.getIndexComponents(commands, page))
                .queue(null, e -> {});
    }

    private static int parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
