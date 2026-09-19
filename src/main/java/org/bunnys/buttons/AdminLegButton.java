package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.beastars.commands.admin.AdminComponents;
import org.bunnys.beastars.commands.admin.AdminComponents.AdminAction;
import org.bunnys.beastars.commands.admin.AdminEmbeds;
import org.bunnys.beastars.commands.admin.AdminService;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.leg.LegConfigService;
import org.bunnys.beastars.commands.leg.LegConfigService.RoleCategory;
import org.bunnys.beastars.database.LegConfigData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.BunnyLog;

/**
 * Routes clicks on the {@code /admin dashboard} panel.
 *
 * <p>Every action either toggles a setting directly or opens the modal that collects
 * its input. All modal construction moved to {@link AdminComponents}; this class
 * decides <em>which</em> modal, not what is inside it.
 *
 * <p>Modals that edit roles are built from the live config so their pickers open
 * pre-selected with what is currently stored.
 */
public class AdminLegButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return AdminComponents.BUTTON_PREFIX;
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

        AdminAction action = AdminAction.from(args[1]);
        if (action == null) {
            BunnyLog.warning("[AdminLegButton] Unknown dashboard action: " + args[1]);
            event.replyEmbeds(AdminEmbeds.error("Unknown Action",
                    "That control is no longer available. Reopen the dashboard with `/admin dashboard`."))
                    .setEphemeral(true).queue();
            return;
        }

        String guildId = event.getGuild().getId();

        try {
            RoleCategory category = action.roleCategory();
            if (category != null) {
                event.replyModal(AdminComponents.roleCategory(
                        action, category, LegConfigService.getConfig(guildId))).queue();
                return;
            }

            switch (action) {
                case TOGGLE -> toggleEconomy(event, guildId);

                case SET_WAIT -> event.replyModal(AdminComponents.waitPeriod(
                        LegConfigService.getConfig(guildId).getWaitPeriodHours())).queue();

                case BAN_USER -> event.replyModal(AdminComponents.userTarget(
                        action, "Ban User", "This member will be barred from the economy.")).queue();
                case UNBAN_USER -> event.replyModal(AdminComponents.userTarget(
                        action, "Unban User", "This member will be allowed back into the economy.")).queue();
                case RESET_USER -> event.replyModal(AdminComponents.departedUserTarget(
                        action, "Reset User Profile",
                        "This one member's statistics will be zeroed. The rest of the economy is untouched.")).queue();
                case DELETE_USER -> event.replyModal(AdminComponents.departedUserTarget(
                        action, "Delete User Profile",
                        "This one member's profile is erased. The rest of the economy is untouched.")).queue();

                case EDIT_USER -> event.replyModal(AdminComponents.editStats()).queue();

                case NUKE -> event.replyModal(AdminComponents.nukeConfirmation()).queue();

                case LB_HIDDEN_ROLES -> event.replyModal(AdminComponents.leaderboardHiddenRoles(
                        LegConfigService.getConfig(guildId).getLeaderboardHiddenRoles())).queue();

                case LB_TOGGLE_BANNED -> toggleLeaderboard(event, guildId, true);
                case LB_TOGGLE_DEPARTED -> toggleLeaderboard(event, guildId, false);

                // ADMIN_ROLES is deliberately absent: it is reachable only from
                // `/admin roles`, which demands native ADMINISTRATOR. Exposing it as a
                // dashboard button would let a Bot Admin edit the Bot Admin list.
                default -> event.replyEmbeds(AdminEmbeds.error("Unknown Action",
                        "That control is not wired to anything.")).setEphemeral(true).queue();
            }
        } catch (Exception ex) {
            BunnyLog.error("[AdminLegButton] Failed to handle action " + action.id(), ex);
            if (!event.isAcknowledged())
                event.replyEmbeds(AdminEmbeds.systemError()).setEphemeral(true).queue(null, e -> {});
        }
    }

    /**
     * Flips one of the leaderboard visibility switches and stays on that page.
     *
     * @param banned true for the banned-members switch, false for the departed one
     */
    private static void toggleLeaderboard(ButtonInteractionEvent event, String guildId, boolean banned) {
        event.deferEdit().queue();

        LegConfigData current = LegConfigService.getConfig(guildId);
        boolean nowHidden = banned
                ? !current.isHideBannedOnLeaderboard()
                : !current.isHideDepartedOnLeaderboard();

        LegConfigData updated = banned
                ? LegConfigService.setHideBannedOnLeaderboard(guildId, nowHidden)
                : LegConfigService.setHideDepartedOnLeaderboard(guildId, nowHidden);

        if (updated == null) {
            event.getHook().sendMessageEmbeds(AdminEmbeds.error("Update Failed",
                    "The leaderboard setting could not be saved. Please try again."))
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        String subject = banned ? "banned members" : "members who left";
        AuditService.record(event.getGuild(), event.getUser(), event.getChannel(),
                "Leaderboard Visibility", AuditService.AuditTarget.none(),
                "The leaderboard now **" + (nowHidden ? "hides" : "shows") + "** " + subject + ".");

        event.getHook().editOriginalEmbeds(
                        AdminEmbeds.leaderboardSettings(updated, event.getGuild().isLoaded()))
                .setComponents(AdminComponents.leaderboardPage(updated))
                .queue(null, e -> {});
    }

    /**
     * Flips the economy on or off and redraws the dashboard in place.
     *
     * <p>One database call: {@code findOneAndUpdate} returns the post-image, which
     * both refreshes the cache and feeds the redraw, so the panel always shows the
     * value that was actually stored.
     */
    private static void toggleEconomy(ButtonInteractionEvent event, String guildId) {
        event.deferEdit().queue();

        boolean nowEnabled = !LegConfigService.getConfig(guildId).isEnabled();
        LegConfigData updated = LegConfigService.setEnabled(guildId, nowEnabled);

        if (updated == null) {
            event.getHook().sendMessageEmbeds(AdminEmbeds.error("Update Failed",
                    "The economy state could not be saved. Please try again."))
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        AuditService.record(event.getGuild(), event.getUser(), event.getChannel(),
                nowEnabled ? "Enable Leg Economy" : "Disable Leg Economy", AuditService.AuditTarget.none(),
                "The Leg economy is now **" + (nowEnabled ? "enabled" : "disabled") + "**.");

        // Stays on the economy page rather than bouncing back to the hub.
        event.getHook().editOriginalEmbeds(AdminEmbeds.dashboard(updated))
                .setComponents(AdminComponents.economyPage(updated))
                .queue(null, e -> {});
    }
}
