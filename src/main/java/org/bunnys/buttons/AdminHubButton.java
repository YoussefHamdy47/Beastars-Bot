package org.bunnys.buttons;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.beastars.commands.admin.AccessService;
import org.bunnys.beastars.commands.admin.AdminComponents;
import org.bunnys.beastars.commands.admin.AdminComponents.CommandScope;
import org.bunnys.beastars.commands.admin.AdminComponents.HubPage;
import org.bunnys.beastars.commands.admin.AdminEmbeds;
import org.bunnys.beastars.commands.admin.AdminService;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.ooc.OocService;
import org.bunnys.beastars.commands.admin.GuildSettingsService;
import org.bunnys.beastars.commands.leg.LegConfigService;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.BunnyLog;

import java.util.List;

/**
 * Navigation for the master admin dashboard, plus the logging and access-control actions.
 *
 * <p>Pages swap the panel in place with {@code editOriginal} rather than opening new
 * messages, so an admin working through several sections leaves one ephemeral panel
 * behind instead of five.
 *
 * <p>Id format: {@code admin_hub:<page>[:<scope>]}. The optional scope segment is what
 * keeps the Manga page's controls from offering economy commands - see
 * {@link CommandScope}.
 *
 * <p>No cooldown: these are read-mostly navigation controls, and an admin flipping
 * between pages is expected behaviour rather than abuse.
 */
public class AdminHubButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return AdminComponents.HUB_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 2)
            return;

        if (event.getGuild() == null || event.getMember() == null) {
            event.replyEmbeds(AdminEmbeds.denied("This panel is only available inside a server."))
                    .setEphemeral(true).queue();
            return;
        }

        if (!AdminService.isAdmin(event.getMember())) {
            event.replyEmbeds(AdminEmbeds.denied("You do not have permission to use this."))
                    .setEphemeral(true).queue();
            return;
        }

        HubPage page = HubPage.from(args[1]);
        if (page == null) {
            event.replyEmbeds(AdminEmbeds.error("Unknown Section",
                    "That panel is no longer available. Reopen it with `/admin dashboard`."))
                    .setEphemeral(true).queue();
            return;
        }

        // Absent scope means the unscoped Access Control page.
        CommandScope scope = args.length > 2 ? CommandScope.from(args[2]) : CommandScope.ALL;

        String guildId = event.getGuild().getId();
        List<String> registered = commandNames(client);
        List<String> inScope = scope.filter(registered);

        try {
            switch (page) {
                case HOME -> render(event, AdminEmbeds.hub(event.getGuild().getName()),
                        AdminComponents.hub());

                case ACCESS -> render(event,
                        AdminEmbeds.accessOverview(AccessService.get(guildId), registered),
                        AdminComponents.accessPage());

                case MANGA -> render(event,
                        AdminEmbeds.mangaOverview(AccessService.get(guildId),
                                CommandScope.MANGA.filter(registered)),
                        AdminComponents.mangaPage());

                case ECONOMY -> {
                    var config = LegConfigService.getConfig(guildId);
                    render(event, AdminEmbeds.dashboard(config), AdminComponents.economyPage(config));
                }

                case LEADERBOARD -> {
                    var config = LegConfigService.getConfig(guildId);
                    render(event, AdminEmbeds.leaderboardSettings(config, event.getGuild().isLoaded()),
                            AdminComponents.leaderboardPage(config));
                }

                case LOGGING -> {
                    String channelId = GuildSettingsService.getLogChannelId(guildId);
                    render(event, AdminEmbeds.loggingOverview(channelId),
                            AdminComponents.loggingPage(channelId != null));
                }

                case OOC -> render(event,
                        AdminEmbeds.oocSettings(OocService.settings(guildId)),
                        AdminComponents.oocPage());

                case OOC_COOLDOWN -> event.replyModal(AdminComponents.oocCooldown(
                        OocService.rerollCooldownSeconds(guildId))).queue();

                case ACCESS_TOGGLE -> event.replyModal(AdminComponents.toggleCommand(
                        scope, inScope, disabledOf(guildId, inScope))).queue();

                case ACCESS_ROLES -> event.replyModal(AdminComponents.roleRule(
                        scope, inScope, disabledOf(guildId, inScope))).queue();

                case ACCESS_CHANNELS -> event.replyModal(AdminComponents.channelRule(
                        scope, inScope, disabledOf(guildId, inScope))).queue();

                case LOG_SET -> event.replyModal(AdminComponents.logChannel()).queue();

                case LOG_CLEAR -> clearLogging(event, guildId);
            }
        } catch (Exception ex) {
            BunnyLog.error("[AdminHubButton] Failed to handle " + page.id(), ex);
            if (!event.isAcknowledged())
                event.replyEmbeds(AdminEmbeds.systemError()).setEphemeral(true).queue(null, e -> {});
        }
    }

    private static void clearLogging(ButtonInteractionEvent event, String guildId) {
        event.deferEdit().queue();

        if (!GuildSettingsService.setLogChannel(guildId, null)) {
            event.getHook().sendMessageEmbeds(AdminEmbeds.error("Update Failed",
                    "Logging could not be disabled. Please try again."))
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        AuditService.record(event.getGuild(), event.getUser(), event.getChannel(),
                "Disable Audit Logging", AuditService.AuditTarget.none(),
                "Discord log channel cleared. Database logging is unaffected.");

        // Re-read rather than assuming: the panel must show what was actually stored.
        String current = GuildSettingsService.getLogChannelId(guildId);
        event.getHook().editOriginalEmbeds(AdminEmbeds.loggingOverview(current))
                .setComponents(AdminComponents.loggingPage(current != null))
                .queue(null, e -> {});
    }

    private static void render(ButtonInteractionEvent event, MessageEmbed embed, List<ActionRow> rows) {
        event.deferEdit().queue();
        event.getHook().editOriginalEmbeds(embed).setComponents(rows).queue(null, e -> {});
    }

    /** Live registry, sorted, so newly added commands are configurable immediately. */
    private static List<String> commandNames(BunnyHub client) {
        return client.getCommandRegistry().getCommands().values().stream()
                .filter(command -> !command.isDeveloperOnly())
                .map(command -> command.getName())
                .sorted()
                .toList();
    }

    private static List<String> disabledOf(String guildId, List<String> commands) {
        AccessService.GuildAccess access = AccessService.get(guildId);
        return commands.stream().filter(access::isDisabled).toList();
    }
}
