package org.bunnys.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.beastars.help.HelpMenuManager;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.router.selects.BunnySelect;

import java.util.ArrayList;
import java.util.List;

/**
 * The command picker under the help menu.
 *
 * <p>Not caller-locked, and no cooldown: help is public information, and locking the
 * menu to whoever opened it only annoys the next person to read the message. The same
 * reasoning as {@code HelpPaginationButton}.
 *
 * <p>The page number rides in the component id so the detail card can offer a way back
 * to the exact index page the reader came from.
 */
public class HelpCommandSelect extends BunnySelect {

    @Override
    public String getPrefix() {
        return "helppick";
    }

    @Override
    public void execute(BunnyHub client, StringSelectInteractionEvent event, String[] args) {
        if (event.getValues().isEmpty())
            return;

        String chosen = event.getValues().get(0);
        int page = args.length > 1 ? parsePage(args[1]) : 1;

        List<BunnyCommand> commands = new ArrayList<>(client.getCommandRegistry().getCommands().values());
        BunnyCommand target = HelpMenuManager.findCommand(commands, chosen);

        if (target == null) {
            // The menu was built from the registry, so this only happens if a command
            // disappeared between render and click. Say so rather than doing nothing.
            event.replyEmbeds(HelpMenuManager.commandNotFound(chosen)).setEphemeral(true).queue(null, e -> {});
            return;
        }

        String botName = event.getJDA().getSelfUser().getName();
        String avatar = event.getJDA().getSelfUser().getEffectiveAvatarUrl();

        event.editMessageEmbeds(HelpMenuManager.getCommandDetail(botName, avatar, target))
                .setComponents(HelpMenuManager.getDetailComponents(commands, page, target.getName()))
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
