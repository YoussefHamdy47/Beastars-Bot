package org.bunnys.modals;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.admin.AccessService;
import org.bunnys.beastars.commands.admin.AccessService.Mode;
import org.bunnys.beastars.commands.admin.AccessService.Scope;
import org.bunnys.beastars.commands.admin.AdminComponents;
import org.bunnys.beastars.commands.admin.AdminComponents.CommandScope;
import org.bunnys.beastars.commands.admin.AdminComponents.HubPage;
import org.bunnys.beastars.commands.admin.AdminEmbeds;
import org.bunnys.beastars.commands.admin.AdminService;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.ooc.OocService;
import org.bunnys.beastars.commands.admin.GuildSettingsService;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies access-control and logging changes made from the master dashboard.
 *
 * <p>Same shape as {@code AdminLegModal}: authorise, read the selections, validate,
 * delegate, audit, reply. Every write goes through {@link AccessService} or
 * {@link GuildSettingsService}, and every one of them is audited.
 */
public class AdminAccessModal extends BunnyModal {

    @Override
    public String getPrefix() {
        return AdminComponents.ACCESS_MODAL_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
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

        HubPage action = HubPage.from(args[1]);
        if (action == null) {
            event.replyEmbeds(AdminEmbeds.error("Unknown Form",
                    "That form is no longer available. Reopen it with `/admin dashboard`."))
                    .setEphemeral(true).queue();
            return;
        }

        // The scope the modal was opened under, so a manga form can only write manga.
        CommandScope scope = args.length > 2 ? CommandScope.from(args[2]) : CommandScope.ALL;

        event.deferReply(true).queue();

        try {
            switch (action) {
                case ACCESS_TOGGLE -> toggleCommand(event, client, scope);
                case ACCESS_ROLES -> setRule(event, client, scope, Scope.ROLE);
                case ACCESS_CHANNELS -> setRule(event, client, scope, Scope.CHANNEL);
                case LOG_SET -> setLogChannel(event);
                case OOC_COOLDOWN -> setOocCooldown(event, client);
                default -> reply(event, AdminEmbeds.systemError());
            }
        } catch (Exception ex) {
            BunnyLog.error("[AdminAccessModal] Failed to apply " + action.id(), ex);
            reply(event, AdminEmbeds.systemError());
        }
    }

    /**
     * Re-renders the panel the modal was opened from, using freshly-read state.
     *
     * <p>Without this the confirmation says one thing while the panel behind it still
     * shows the old configuration. {@code getMessage()} is non-null only when the modal
     * came from a component, so a slash-invoked form simply skips it.
     */
    private static void refreshPanel(ModalInteractionEvent event, BunnyHub client, CommandScope scope) {
        // The Economy panel shows economy settings, not access rules, so an access
        // change there leaves nothing on screen to update.
        if (event.getMessage() == null || scope == CommandScope.ECONOMY)
            return;

        // Each scoped page shows its own thing, so the refresh has to match the panel the
        // modal was opened from rather than defaulting everything to access rules. Every
        // branch re-reads after the write; none of them render from what was submitted.
        MessageEmbed embed;
        List<ActionRow> rows;

        if (scope == CommandScope.OOC) {
            // Reads the OOC document rather than the access rules, so the access lookup
            // below is deliberately not hoisted out of these branches.
            embed = AdminEmbeds.oocSettings(OocService.settings(event.getGuild().getId()));
            rows = AdminComponents.oocPage();
        } else {
            List<String> registered = commandNames(client);
            AccessService.GuildAccess access = AccessService.get(event.getGuild().getId());

            embed = scope == CommandScope.MANGA
                    ? AdminEmbeds.mangaOverview(access, CommandScope.MANGA.filter(registered))
                    : AdminEmbeds.accessOverview(access, registered);

            rows = scope == CommandScope.MANGA
                    ? AdminComponents.mangaPage()
                    : AdminComponents.accessPage();
        }

        event.getMessage().editMessageEmbeds(embed).setComponents(rows).queue(null, e -> {});
    }

    private static void refreshLoggingPanel(ModalInteractionEvent event, String guildId) {
        if (event.getMessage() == null)
            return;

        String current = GuildSettingsService.getLogChannelId(guildId);
        event.getMessage()
                .editMessageEmbeds(AdminEmbeds.loggingOverview(current))
                .setComponents(AdminComponents.loggingPage(current != null))
                .queue(null, e -> {});
    }

    private static List<String> commandNames(BunnyHub client) {
        return client.getCommandRegistry().getCommands().values().stream()
                .filter(command -> !command.isDeveloperOnly())
                .map(command -> command.getName())
                .sorted()
                .toList();
    }

    // ------------------------------------------------------------------
    // Access control
    // ------------------------------------------------------------------

    private static void toggleCommand(ModalInteractionEvent event, BunnyHub client, CommandScope scope) {
        String command = selected(event, AdminComponents.INPUT_COMMAND);
        String state = selected(event, AdminComponents.INPUT_MODE);

        if (command == null || state == null) {
            reply(event, AdminEmbeds.error("Nothing Selected",
                    "Pick both a command and a state, then submit again."));
            return;
        }

        Guild guild = event.getGuild();

        // Belt and braces: the picker only offered in-scope commands, but the scope is
        // re-checked here so a replayed or crafted submission cannot reach past it.
        if (!inScope(client, scope, command)) {
            reply(event, AdminEmbeds.error("Out of Scope",
                    "`/" + command + "` is not managed by the " + scope.label() + " panel."));
            return;
        }

        boolean enable = state.equals("enabled");

        if (!AccessService.setEnabled(guild.getId(), command, enable)) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The command state could not be saved. Please try again."));
            return;
        }

        AuditService.record(guild, event.getUser(), event.getChannel(),
                enable ? "Enable Command" : "Disable Command",
                AuditService.AuditTarget.command("/" + command),
                "`/" + command + "` is now **" + (enable ? "enabled" : "disabled") + "** server-wide.");

        refreshPanel(event, client, scope);

        reply(event, AdminEmbeds.success(
                enable ? "Command Enabled" : "Command Disabled",
                enable ? BeastarsEmoji.ENABLED : BeastarsEmoji.DISABLED,
                "`/" + command + "` is now **" + (enable ? "enabled" : "disabled") + "** in this server."
                        + "\n*Server managers always bypass this.*"));
    }

    /**
     * Stores the OOC reroll cooldown.
     *
     * <p>Parsed rather than trusted: Discord's text input constrains length, not content,
     * so anything at all can arrive here. A value outside the allowed range is clamped by
     * the service rather than refused, and the confirmation reports what was actually
     * stored, so an admin who types 99999 is told they got the ceiling instead of being
     * left thinking they got what they typed.
     */
    private static void setOocCooldown(ModalInteractionEvent event, BunnyHub client) {
        Guild guild = event.getGuild();
        if (guild == null) {
            reply(event, AdminEmbeds.error("Server Only", "This form only works inside a server."));
            return;
        }

        var mapping = event.getValue(AdminComponents.INPUT_OOC_COOLDOWN);
        if (mapping == null) {
            reply(event, AdminEmbeds.systemError());
            return;
        }

        int requested;
        try {
            requested = Integer.parseInt(mapping.getAsString().trim());
        } catch (NumberFormatException e) {
            reply(event, AdminEmbeds.error("Not a Number",
                    "Enter the cooldown as a whole number of seconds, for example `30`."));
            return;
        }

        OocService.Settings stored = OocService.setRerollCooldown(guild.getId(), requested);
        if (stored == null) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The cooldown could not be saved. Please try again."));
            return;
        }

        int applied = stored.rerollCooldownSeconds();

        AuditService.record(guild, event.getUser(), event.getChannel(),
                "Set OOC Reroll Cooldown", AuditService.AuditTarget.command("/ooc"),
                "Members now wait **" + applied + "** second" + (applied == 1 ? "" : "s")
                        + " between rerolls.");

        refreshPanel(event, client, CommandScope.OOC);

        reply(event, AdminEmbeds.success("Cooldown Updated", BeastarsEmoji.TIMER,
                applied <= 0
                        ? "Rerolls are no longer throttled."
                        : "Members must now wait **" + applied + "** second"
                        + (applied == 1 ? "" : "s") + " between rerolls."
                        + (applied == requested ? ""
                        : "\n*Adjusted from " + requested + " to stay within the allowed range.*")));
    }

    private static void setRule(ModalInteractionEvent event, BunnyHub client,
                                CommandScope commandScope, Scope ruleScope) {
        String command = selected(event, AdminComponents.INPUT_COMMAND);
        String rawMode = selected(event, AdminComponents.INPUT_MODE);

        if (command == null || rawMode == null) {
            reply(event, AdminEmbeds.error("Nothing Selected",
                    "Pick both a command and a rule type, then submit again."));
            return;
        }

        Guild guild = event.getGuild();

        if (!inScope(client, commandScope, command)) {
            reply(event, AdminEmbeds.error("Out of Scope",
                    "`/" + command + "` is not managed by the " + commandScope.label() + " panel."));
            return;
        }

        Mode mode = rawMode.equals("allow") ? Mode.ALLOW : Mode.DENY;

        List<String> ids = ruleScope == Scope.ROLE
                ? validRoles(event, guild)
                : validChannels(event, guild);

        if (!AccessService.setRule(guild.getId(), command, ruleScope, mode, ids)) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The rule could not be saved. Please try again."));
            return;
        }

        String scopeName = ruleScope == Scope.ROLE ? "role" : "channel";
        String modeName = mode == Mode.ALLOW ? "allow-list" : "deny-list";

        String summary = ids.isEmpty()
                ? "The " + scopeName + " " + modeName + " for `/" + command + "` has been cleared."
                : "`/" + command + "` now has **" + ids.size() + "** " + scopeName
                        + "(s) on its " + modeName + ".";

        AuditService.record(guild, event.getUser(), event.getChannel(),
                "Update Access Rule", AuditService.AuditTarget.command("/" + command), summary);

        refreshPanel(event, client, commandScope);

        reply(event, AdminEmbeds.success("Access Rule Updated",
                ruleScope == Scope.ROLE ? BeastarsEmoji.ROLES : BeastarsEmoji.ROLE_ALLOWED,
                summary + "\n*Server managers always bypass this.*"));
    }

    /** True when the command genuinely belongs to the panel that submitted the form. */
    private static boolean inScope(BunnyHub client, CommandScope scope, String command) {
        return scope.filter(commandNames(client)).contains(command);
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    private static void setLogChannel(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        List<String> selected = selectedList(event, AdminComponents.INPUT_LOG_CHANNEL);

        if (selected.isEmpty()) {
            reply(event, AdminEmbeds.error("No Channel Selected",
                    "Pick a channel from the dropdown and submit again."));
            return;
        }

        GuildChannel channel = guild.getGuildChannelById(selected.get(0));

        // Entity selects can offer categories and voice channels; only a text-capable
        // channel can receive an embed, so reject anything else with a clear reason.
        if (channel == null || channel.getType() != ChannelType.TEXT) {
            reply(event, AdminEmbeds.error("Not a Text Channel",
                    "Audit logs can only be posted to a normal text channel."));
            return;
        }

        if (!guild.getSelfMember().hasPermission(channel,
                net.dv8tion.jda.api.Permission.MESSAGE_SEND,
                net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS)) {
            reply(event, AdminEmbeds.error("Missing Permission",
                    "I cannot post embeds in " + channel.getAsMention()
                            + ". Grant Send Messages and Embed Links there, then try again."));
            return;
        }

        if (!GuildSettingsService.setLogChannel(guild.getId(), channel.getId())) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The log channel could not be saved. Please try again."));
            return;
        }

        // Ordered so this very action is the first entry the new channel receives.
        AuditService.record(guild, event.getUser(), event.getChannel(),
                "Set Audit Log Channel", AuditService.AuditTarget.channel(channel.getId()),
                "Audit logs will now be posted to " + channel.getAsMention() + ".");

        refreshLoggingPanel(event, guild.getId());

        reply(event, AdminEmbeds.success("Logging Enabled", BeastarsEmoji.EDIT,
                "Admin actions will now be posted to " + channel.getAsMention() + "."
                        + "\n*Entries are always written to the database as well.*"));
    }

    // ------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------

    private static void reply(ModalInteractionEvent event, MessageEmbed embed) {
        event.getHook().sendMessageEmbeds(embed).queue(null, e -> {});
    }

    /** First value of a single-select, or null when nothing came back. */
    private static String selected(ModalInteractionEvent event, String id) {
        List<String> values = selectedList(event, id);
        return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> selectedList(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? List.of() : mapping.getAsStringList();
    }

    /** Roles that still exist, since one can vanish between render and submit. */
    private static List<String> validRoles(ModalInteractionEvent event, Guild guild) {
        return AdminComponents.validateRoles(guild, selectedList(event, AdminComponents.INPUT_ROLES));
    }

    private static List<String> validChannels(ModalInteractionEvent event, Guild guild) {
        List<String> valid = new ArrayList<>();
        for (String id : selectedList(event, AdminComponents.INPUT_CHANNELS))
            if (!valid.contains(id) && guild.getGuildChannelById(id) != null)
                valid.add(id);
        return valid;
    }
}
