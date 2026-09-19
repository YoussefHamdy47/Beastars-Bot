package org.bunnys.commands;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.help.HelpMenuManager;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /help} - the paginated command reference. Also {@code @BotName help}.
 *
 * <p>Two ways in. Bare, it opens the category index with a picker menu underneath, which
 * is what someone who does not yet know what exists needs. With {@code command:}, it
 * goes straight to that command's detail card - the fast path for someone who knows the
 * name but not the arguments.
 *
 * <p>Deliberately left at the top level rather than folded under {@code /info}: it is
 * the first thing anybody types at a bot they do not know, and a discovery command that
 * has to be discovered first is not doing its job.
 */
public class Help extends BunnyCommand {

    public Help(BunnyHub client) {
        super(client);
        setName("help");
        setDescription("Show every command, with both slash and mention syntax");
        addAliases("commands", "h");
        setCategory("Information");
        setExample("/help command:manga");
        addOption(new OptionData(OptionType.STRING, "command",
                "Jump straight to one command's full usage", false, true));
    }

    @Override
    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("command"))
            return List.of();

        return HelpMenuManager.suggest(new ArrayList<>(client.getCommandRegistry().getCommands().values()));
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        List<BunnyCommand> commands = new ArrayList<>(client.getCommandRegistry().getCommands().values());

        String botName = ctx.getJDA().getSelfUser().getName();
        String avatar = ctx.getJDA().getSelfUser().getEffectiveAvatarUrl();

        String wanted = ctx.getString("command");

        if (wanted != null && !wanted.isBlank()) {
            BunnyCommand target = HelpMenuManager.findCommand(commands, wanted);

            if (target == null) {
                ctx.reply(HelpMenuManager.commandNotFound(wanted), true);
                return;
            }

            ctx.reply(HelpMenuManager.getCommandDetail(botName, avatar, target),
                    HelpMenuManager.getDetailComponents(commands, 1, target.getName()));
            return;
        }

        ctx.reply(HelpMenuManager.getHelpPage(botName, avatar, commands, 1),
                HelpMenuManager.getIndexComponents(commands, 1));
    }
}
