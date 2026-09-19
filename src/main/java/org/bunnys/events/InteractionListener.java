package org.bunnys.events;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.CommandGate;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.handler.router.buttons.ButtonRouter;
import org.bunnys.handler.router.modals.ModalRouter;
import org.bunnys.handler.router.selects.SelectRouter;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * The interaction entry point: slash commands, autocomplete, buttons and modals.
 *
 * <p>All pre-execution checks moved to {@link CommandGate}, which the mention router
 * runs too - the two paths cannot drift apart on permissions or cooldowns because there
 * is only one implementation of either.
 */
@SuppressWarnings("unused")
public class InteractionListener extends BunnyEvent {

    public InteractionListener(BunnyHub client) {
        super(client);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getUser().isBot())
            return;

        ButtonRouter.handle(client, event);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getUser().isBot())
            return;

        SelectRouter.handle(client, event);
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        ModalRouter.handle(client, event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        CommandRegistry registry = client.getCommandRegistry();
        BunnyCommand command = registry.resolveCommand(event.getName());

        if (command == null)
            return;

        BunnySubcommand subcommand = command.resolve(event.getSubcommandGroup(), event.getSubcommandName());

        try {
            client.getCommandExecutor().submit(() -> {
                try {
                    List<String> choices = subcommand != null
                            ? subcommand.autocomplete(client, event)
                            : command.autocomplete(client, event);

                    if (choices == null || choices.isEmpty()) {
                        event.replyChoiceStrings(List.of()).queue(null, e -> {});
                        return;
                    }

                    String typed = event.getFocusedOption().getValue().toLowerCase();

                    event.replyChoiceStrings(choices.stream()
                            .filter(choice -> choice.toLowerCase().contains(typed))
                            .limit(25)
                            .toList()).queue(null, e -> {});

                } catch (Throwable err) {
                    // No user-facing notice: an autocomplete crash shows as an empty
                    // suggestion list, and interrupting someone mid-keystroke with an
                    // error embed would be worse than the missing suggestions.
                    ErrorReporter.report("autocomplete /" + event.getName(),
                            event.getGuild() == null ? null : event.getGuild().getId(), err);
                    event.replyChoiceStrings(List.of()).queue(null, e -> {});
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[InteractionListener] Autocomplete rejected under load: /" + event.getName());
            event.replyChoiceStrings(List.of()).queue(null, e -> {});
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getUser().isBot())
            return;

        CommandRegistry registry = client.getCommandRegistry();
        BunnyCommand command = registry.resolveCommand(event.getName());

        if (command == null)
            return;

        BunnySubcommand subcommand = command.resolve(event.getSubcommandGroup(), event.getSubcommandName());

        SlashContext ctx = new SlashContext(event);

        MessageEmbed denial = CommandGate.check(ctx, registry, command, subcommand);
        if (denial != null) {
            ctx.reply(denial, true);
            return;
        }

        try {
            client.getCommandExecutor().submit(() -> {
                try {
                    if (subcommand != null)
                        subcommand.execute(client, ctx);
                    else
                        command.execute(client, ctx);
                } catch (Throwable err) {
                    String reference = ErrorReporter.report("/" + event.getFullCommandName(),
                            ctx.getGuild() == null ? null : ctx.getGuild().getId(), err);
                    ctx.replyTransient(SystemEmbeds.crashed(reference, ctx.transientRepliesVanish()));
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[InteractionListener] /" + event.getName() + " rejected under load");
            // The invocation never ran, so it must not burn the caller's cooldown.
            CommandGate.release(ctx, command, subcommand);
            ctx.reply(SystemEmbeds.busy(), true);
        }
    }
}
