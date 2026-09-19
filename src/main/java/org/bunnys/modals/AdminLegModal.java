package org.bunnys.modals;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.admin.AdminComponents;
import org.bunnys.beastars.commands.admin.AdminComponents.AdminAction;
import org.bunnys.beastars.commands.admin.AdminEmbeds;
import org.bunnys.beastars.commands.admin.AdminService;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.leg.LegConfigService;
import org.bunnys.beastars.commands.leg.LegConfigService.RoleCategory;
import org.bunnys.beastars.commands.leg.LegService;
import org.bunnys.beastars.database.LegConfigData;
import org.bunnys.beastars.database.LegData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;
import org.bunnys.utils.BunnyLog;

import java.util.List;

/**
 * Applies whatever an admin selected or typed in a dashboard modal.
 *
 * <p>Pure orchestration: authorise, read the selection, validate, delegate, audit,
 * reply. No queries, no embed construction - writes go through {@link LegService},
 * {@link LegConfigService} or {@link AdminService}, embeds come from
 * {@link AdminEmbeds}.
 *
 * <h2>Confirmations show live state</h2>
 * Every mutating call returns the post-image straight from MongoDB and seeds the
 * Caffeine entry with it. The confirmation embed is then rendered from that return
 * value, not from a re-read and not from what the admin typed - so what the embed
 * says is, by construction, what is now in the database and in the cache.
 */
public class AdminLegModal extends BunnyModal {

    @Override
    public String getPrefix() {
        return AdminComponents.MODAL_PREFIX;
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

        AdminAction action = AdminAction.from(args[1]);
        if (action == null) {
            BunnyLog.warning("[AdminLegModal] Unknown modal action: " + args[1]);
            event.replyEmbeds(AdminEmbeds.error("Unknown Action",
                    "That form is no longer available. Reopen the dashboard with `/admin dashboard`."))
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();

        Guild guild = event.getGuild();
        String guildId = guild.getId();
        String adminId = event.getUser().getId();

        try {
            RoleCategory category = action.roleCategory();
            if (category != null) {
                setEconomyRoles(event, guild, category);
                return;
            }

            switch (action) {
                case NUKE -> nuke(event, guildId, adminId);
                case LB_HIDDEN_ROLES -> setLeaderboardHiddenRoles(event, guild);
                case SET_WAIT -> setWait(event, guildId);
                case ADMIN_ROLES -> setAdminRoles(event, guild);
                case BAN_USER, UNBAN_USER, RESET_USER, DELETE_USER, EDIT_USER ->
                        userAction(event, action, guildId, adminId);
                case TOGGLE -> reply(event, AdminEmbeds.error("Wrong Channel",
                        "The economy toggle is a button, not a form."));
                default -> reply(event, AdminEmbeds.systemError());
            }
        } catch (Exception ex) {
            BunnyLog.error("[AdminLegModal] Failed to apply action " + action.id() + " in guild " + guildId, ex);
            reply(event, AdminEmbeds.systemError());
        }
    }

    // ------------------------------------------------------------------
    // Guild-wide actions
    // ------------------------------------------------------------------

    private static void nuke(ModalInteractionEvent event, String guildId, String adminId) {
        String confirmation = text(event, AdminComponents.INPUT_CONFIRM);

        if (!AdminComponents.NUKE_CONFIRMATION.equalsIgnoreCase(confirmation)) {
            reply(event, AdminEmbeds.error("Nuke Aborted",
                    "Invalid confirmation code. Nothing was deleted."));
            return;
        }

        long removed;
        try {
            removed = LegService.nukeGuild(guildId);
        } catch (Exception e) {
            BunnyLog.error("[AdminLegModal] Nuke failed for guild " + guildId, e);
            reply(event, AdminEmbeds.error("Nuke Failed",
                    "The database wipe did not complete. No further action was taken."));
            return;
        }

        audit(event, AuditService.AuditTarget.global(), "Nuke Economy",
                "Admin bypassed safety and wiped the entire server economy.");

        reply(event, AdminEmbeds.success("Economy Nuked", BeastarsEmoji.NUKE,
                "**" + removed + "** profile(s) for this server have been permanently erased.\n"
                        + "*This action has been logged against your account.*"));
    }

    private static void setWait(ModalInteractionEvent event, String guildId) {
        Integer hours = parseInt(text(event, AdminComponents.INPUT_WAIT_HOURS));

        if (hours == null || hours < 0) {
            reply(event, AdminEmbeds.error("Invalid Wait Period",
                    "Enter a whole number of hours, zero or greater."));
            return;
        }

        LegConfigData updated = LegConfigService.setWaitPeriod(guildId, hours);
        if (updated == null) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The wait period could not be saved. Please try again."));
            return;
        }

        audit(event, AuditService.AuditTarget.none(), "Set Wait Period",
                "New members must now wait **" + updated.getWaitPeriodHours() + " hour(s)**.");

        refreshEconomyPanel(event, updated);

        // Rendered from the post-image, so this number is the stored number.
        reply(event, AdminEmbeds.success("Wait Period Updated", BeastarsEmoji.TIMER,
                "New members must now wait **" + updated.getWaitPeriodHours()
                        + " hour(s)** before participating."));
    }

    /** Replaces one economy role category from the modal's role picker. */
    private static void setEconomyRoles(ModalInteractionEvent event, Guild guild, RoleCategory category) {
        List<String> roleIds = selectedRoles(event, guild);

        LegConfigData updated = LegConfigService.setRoles(guild.getId(), category, roleIds);
        if (updated == null) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The role configuration could not be saved. Please try again."));
            return;
        }

        audit(event, AuditService.AuditTarget.none(), "Update Economy Roles",
                "**" + category.label() + "** now has **" + roleIds.size() + "** role(s).");

        refreshEconomyPanel(event, updated);

        reply(event, AdminEmbeds.roleCategoryUpdated(category, updated));
    }

    /**
     * Replaces the Bot Admin role list from the modal's role picker.
     *
     * <p>Re-checks native {@link Permission#ADMINISTRATOR} rather than relying on the
     * gate at the top of {@code execute}. This form is the one privilege-escalation
     * path in the feature - a Bot Admin who reached it could grant themselves more
     * roles - so it verifies at the point of the write, not only at the point of entry.
     */
    private static void setAdminRoles(ModalInteractionEvent event, Guild guild) {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            reply(event, AdminEmbeds.denied(
                    "You must be a native Server Administrator to manage Bot Admin roles."));
            return;
        }

        List<String> roleIds = selectedRoles(event, guild);

        List<String> stored = AdminService.setAdminRoles(guild.getId(), roleIds);
        if (stored == null) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The Bot Admin roles could not be saved. Please try again."));
            return;
        }

        audit(event, AuditService.AuditTarget.none(), "Update Bot Admin Roles",
                "**" + stored.size() + "** role(s) now hold Bot Admin privileges.");

        reply(event, AdminEmbeds.adminRolesUpdated(stored));
    }

    // ------------------------------------------------------------------
    // Per-user actions
    // ------------------------------------------------------------------

    private static void userAction(ModalInteractionEvent event, AdminAction action,
                                   String guildId, String adminId) {
        String targetId = targetUser(event);

        if (targetId == null) {
            reply(event, AdminEmbeds.error("No Member Selected",
                    "Pick a member from the dropdown, or paste a user ID if they have left"
                            + " the server, and submit again."));
            return;
        }

        String mention = "<@" + targetId + ">";

        switch (action) {
            case BAN_USER -> {
                if (targetId.equals(adminId)) {
                    reply(event, AdminEmbeds.error("Self-Ban Refused",
                            "You cannot ban yourself from the economy."));
                    return;
                }
                switch (LegService.setBanned(guildId, targetId, true)) {
                    case UNCHANGED -> reply(event, AdminEmbeds.error("Already Banned",
                            "User " + mention + " is already banned."));
                    case FAILED -> reply(event, AdminEmbeds.error("Ban Failed",
                            "User " + mention + " could not be banned. Please try again."));
                    case APPLIED -> {
                        audit(event, AuditService.AuditTarget.user(targetId), "Ban User",
                                "Banned from the economy.");
                        reply(event, AdminEmbeds.success("User Banned", BeastarsEmoji.BANNED,
                                "User " + mention + " has been banned from the Leg economy."));
                    }
                }
            }

            case UNBAN_USER -> {
                switch (LegService.setBanned(guildId, targetId, false)) {
                    case UNCHANGED -> reply(event, AdminEmbeds.error("Not Banned",
                            "User " + mention + " is not currently banned."));
                    case FAILED -> reply(event, AdminEmbeds.error("Unban Failed",
                            "User " + mention + " could not be unbanned. Please try again."));
                    case APPLIED -> {
                        audit(event, AuditService.AuditTarget.user(targetId), "Unban User",
                                "Unbanned from the economy.");
                        reply(event, AdminEmbeds.success("User Unbanned", BeastarsEmoji.UNBANNED,
                                "User " + mention + " is no longer banned."));
                    }
                }
            }

            case RESET_USER -> {
                if (!LegService.resetUser(guildId, targetId)) {
                    reply(event, AdminEmbeds.error("Reset Failed",
                            "The profile for " + mention + " could not be reset."));
                    return;
                }
                audit(event, AuditService.AuditTarget.user(targetId), "Reset Profile",
                        "Economy statistics completely zeroed out.");
                reply(event, AdminEmbeds.success("Profile Reset", BeastarsEmoji.RESET,
                        "Economy statistics for " + mention + " have been reset to zero."));
            }

            case DELETE_USER -> {
                switch (LegService.deleteUser(guildId, targetId)) {
                    case UNCHANGED -> reply(event, AdminEmbeds.error("Nothing to Delete",
                            "User " + mention + " has no Leg profile in this server."));
                    case FAILED -> reply(event, AdminEmbeds.error("Delete Failed",
                            "The profile for " + mention + " could not be deleted."));
                    case APPLIED -> {
                        audit(event, AuditService.AuditTarget.user(targetId), "Delete Profile",
                                "Leg profile deleted from database.");
                        reply(event, AdminEmbeds.success("Profile Deleted", BeastarsEmoji.DELETE,
                                "Leg profile for " + mention + " has been completely deleted."));
                    }
                }
            }

            case EDIT_USER -> editStats(event, guildId, adminId, targetId, mention);

            default -> reply(event, AdminEmbeds.systemError());
        }
    }

    private static void editStats(ModalInteractionEvent event, String guildId, String adminId,
                                  String targetId, String mention) {
        Integer given = parseInt(text(event, AdminComponents.INPUT_LEGS_GIVEN));
        Integer received = parseInt(text(event, AdminComponents.INPUT_LEGS_RECEIVED));

        if (given == null || received == null || given < 0 || received < 0) {
            reply(event, AdminEmbeds.error("Invalid Stats",
                    "Both values must be whole numbers, zero or greater."));
            return;
        }

        LegData before = LegService.getUser(guildId, targetId);

        if (!LegService.setStats(guildId, targetId, given, received)) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The statistics for " + mention + " could not be saved."));
            return;
        }

        audit(event, AuditService.AuditTarget.user(targetId), "Edit Stats", String.format(
                "Changed stats from [Given: %d, Received: %d] to [Given: %d, Received: %d]",
                before.getLegsGiven(), before.getLegsReceived(), given, received));

        reply(event, AdminEmbeds.success("Stats Updated", BeastarsEmoji.EDIT,
                "Stats updated for " + mention + ".\n\n"
                        + "**Given:** " + given + "\n**Received:** " + received + "\n\n"
                        + "*Padded entries are tagged `[ADMIN ADJUSTMENT]` in the interaction history.*"));
    }

    // ------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------

    private static void reply(ModalInteractionEvent event, MessageEmbed embed) {
        event.getHook().sendMessageEmbeds(embed).queue(null, e -> {});
    }

    /**
     * Records an administrative action.
     *
     * <p>Passes the invoking channel as the fallback, so if the guild's log channel has
     * been deleted the admin is told about it right where they are working rather than
     * silently losing their Discord audit trail.
     */
    private static void audit(ModalInteractionEvent event, AuditService.AuditTarget target, String action, String details) {
        AuditService.record(event.getGuild(), event.getUser(), event.getChannel(), action, target, details);
    }

    /**
     * Redraws the Economy panel the modal was opened from.
     *
     * <p>Rendered from the post-image the write returned, so the panel behind the
     * confirmation can never disagree with it. {@code getMessage()} is non-null only for
     * a modal opened from a component, so slash-invoked forms skip this.
     */
    private static void refreshEconomyPanel(ModalInteractionEvent event, LegConfigData config) {
        if (event.getMessage() == null)
            return;

        event.getMessage()
                .editMessageEmbeds(AdminEmbeds.dashboard(config))
                .setComponents(AdminComponents.economyPage(config))
                .queue(null, e -> {});
    }

    /**
     * Role IDs from the modal's picker, filtered to roles that still exist.
     *
     * <p>Discord only offers real roles, but one can be deleted between the menu
     * rendering and the modal being submitted, so the guild is the final authority
     * on what reaches MongoDB.
     */
    private static List<String> selectedRoles(ModalInteractionEvent event, Guild guild) {
        var mapping = event.getValue(AdminComponents.INPUT_ROLES);
        if (mapping == null)
            return List.of();

        return AdminComponents.validateRoles(guild, mapping.getAsStringList());
    }

    /** The single member ID from the modal's user picker, or null if none came back. */

    /**
     * Stores the roles hidden from the leaderboard.
     *
     * <p>Re-validated against the guild for the same reason every other role write here
     * is: a role can be deleted between the picker rendering and the form being
     * submitted, and a dangling id would sit in the config hiding nobody forever.
     */
    private static void setLeaderboardHiddenRoles(ModalInteractionEvent event, Guild guild) {
        List<String> roleIds = selectedRoles(event, guild);

        LegConfigData updated = LegConfigService.setLeaderboardHiddenRoles(guild.getId(), roleIds);

        if (updated == null) {
            reply(event, AdminEmbeds.error("Update Failed",
                    "The hidden roles could not be saved. Please try again."));
            return;
        }

        audit(event, AuditService.AuditTarget.none(), "Leaderboard Hidden Roles",
                roleIds.isEmpty()
                        ? "Cleared; nobody is hidden by role any more."
                        : roleIds.size() + " role(s) are now hidden from the leaderboard.");

        reply(event, AdminEmbeds.leaderboardSettings(updated, guild.isLoaded()));
    }
    private static String selectedUser(ModalInteractionEvent event) {
        var mapping = event.getValue(AdminComponents.INPUT_USER);
        if (mapping == null)
            return null;

        List<String> selected = mapping.getAsStringList();
        return selected.isEmpty() ? null : selected.get(0);
    }

    /**
     * The picker's answer, or the typed id when the target has left the server.
     *
     * <p>The picker wins when both are filled: it is the field that cannot be mistyped,
     * so an admin who used it meant it. The id box only exists because an
     * {@code EntitySelectMenu} cannot offer somebody who is no longer a member, and a
     * departed member's leftover profile is exactly what an admin wants to reset or
     * delete.
     *
     * @return the target id, or null when neither field was usable
     */
    private static String targetUser(ModalInteractionEvent event) {
        String picked = selectedUser(event);
        if (picked != null)
            return picked;

        var typed = event.getValue(AdminComponents.INPUT_USER_ID);
        if (typed == null || typed.getAsString() == null)
            return null;

        // Whatever was pasted, reduced to digits: people paste `<@123>` as often as `123`.
        String raw = typed.getAsString().trim().replaceAll("[^0-9]", "");
        return raw.matches("\\d{17,20}") ? raw : null;
    }

    /** Reads a text field, tolerating an absent one rather than throwing on null. */
    private static String text(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString().trim();
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
