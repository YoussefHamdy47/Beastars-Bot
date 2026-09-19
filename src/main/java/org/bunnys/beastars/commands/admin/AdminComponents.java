package org.bunnys.beastars.commands.admin;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.leg.LegConfigService.RoleCategory;
import org.bunnys.beastars.commands.ooc.OocService;
import org.bunnys.beastars.database.LegConfigData;

import java.util.ArrayList;
import java.util.List;

/**
 * The admin dashboard's buttons and the modals they open.
 *
 * <h2>Native pickers, not typed IDs</h2>
 * Every role and user field is an {@link EntitySelectMenu}. Pasting snowflakes was
 * the single worst part of this feature: an admin had to enable Developer Mode,
 * copy IDs one at a time, and comma-join them, with a typo silently writing a
 * dangling ID into MongoDB that no validation ever caught.
 *
 * <p>JDA 6 allows a select menu as a {@code Label} child, so these live inside the
 * existing modals rather than needing a whole new interaction surface - the button
 * and modal routers are untouched. Role menus are pre-populated with the current
 * configuration via {@code setDefaultValues}, so the modal doubles as a view of
 * what is set right now.
 *
 * <p>Discord only ever returns IDs of entities that exist in the guild, which
 * removes the dangling-ID class of bug at the source; {@code AdminLegModal} still
 * re-validates each ID against the guild before writing, because an ID can be
 * deleted between the menu rendering and the modal being submitted.
 *
 * <pre>
 *   admin_leg:&lt;action&gt;            button
 *   admin_leg_modal:&lt;action&gt;      the modal that button opens
 * </pre>
 */
public final class AdminComponents {

    public static final String BUTTON_PREFIX = "admin_leg";
    public static final String MODAL_PREFIX = "admin_leg_modal";

    // Modal input ids, shared between the builders here and the modal handler.
    public static final String INPUT_WAIT_HOURS = "wait_hours";
    public static final String INPUT_ROLES = "role_select";
    public static final String INPUT_USER = "user_select";
    public static final String INPUT_LEGS_GIVEN = "legs_given";
    public static final String INPUT_LEGS_RECEIVED = "legs_received";
    public static final String INPUT_CONFIRM = "confirm_text";

    /** Free-text user id, for a target the picker cannot offer. See {@link #departedUserTarget}. */
    public static final String INPUT_USER_ID = "user_id_text";

    /** The literal an admin must type to authorise a guild-wide wipe. */
    public static final String NUKE_CONFIRMATION = "CONFIRM";

    /** Discord's ceiling for select-menu values. */
    private static final int MAX_SELECTIONS = 25;

    // Hub navigation and the pages it reaches.
    public static final String HUB_PREFIX = "admin_hub";
    public static final String ACCESS_MODAL_PREFIX = "admin_access_modal";

    public static final String INPUT_COMMAND = "command_select";
    public static final String INPUT_MODE = "mode_select";
    public static final String INPUT_CHANNELS = "channel_select";
    public static final String INPUT_LOG_CHANNEL = "log_channel_select";
    public static final String INPUT_OOC_COOLDOWN = "ooc_cooldown_seconds";

    private AdminComponents() {}

    /**
     * Discord's hard ceiling: a message may carry at most five {@link ActionRow}s, each
     * holding at most five buttons.
     *
     * <p>Exceeding the row limit throws {@code IllegalStateException} at build time
     * rather than failing at the API, which is how the Economy page - five rows of
     * controls plus a Back row - crashed on open. Rows here are packed by function
     * rather than one-concept-per-row, and {@link #guardRows} enforces the ceiling.
     */
    public static final int MAX_ROWS = 5;
    public static final int MAX_BUTTONS_PER_ROW = 5;

    /**
     * Which commands a configuration action applies to.
     *
     * <p>The Manga page and the Access Control page open the same modals, so without a
     * scope an admin editing "Manga Settings" was shown every command in the bot and
     * could disable {@code /leg} from inside the manga menu. The scope travels in the
     * button and modal ids, so the picker can only ever offer what the page claims to
     * govern.
     */
    public enum CommandScope {
        ALL("all", "All Commands", List.of()),
        MANGA("manga", "Manga Reader", List.of("manga", "randompage")),
        ECONOMY("economy", "Leg Economy", List.of("leg")),
        OOC("ooc", "OOC Album", List.of("ooc"));

        private final String id;
        private final String label;
        private final List<String> commands;

        CommandScope(String id, String label, List<String> commands) {
            this.id = id;
            this.label = label;
            this.commands = commands;
        }

        public String id() { return id; }
        public String label() { return label; }

        /** The commands this scope governs; {@link #ALL} defers to the live registry. */
        public List<String> filter(List<String> registered) {
            return this == ALL ? registered : registered.stream().filter(commands::contains).toList();
        }

        public static CommandScope from(String raw) {
            for (CommandScope scope : values())
                if (scope.id.equals(raw))
                    return scope;
            return ALL;
        }
    }

    /** The pages of the master dashboard, and the actions reachable from them. */
    public enum HubPage {
        HOME("home"),
        ACCESS("access"),
        MANGA("manga"),
        ECONOMY("economy"),
        LEADERBOARD("leaderboard"),
        LOGGING("logging"),
        OOC("ooc"),
        // Actions rather than pages, but they share the hub's routing prefix.
        ACCESS_TOGGLE("access_toggle"),
        ACCESS_ROLES("access_roles"),
        ACCESS_CHANNELS("access_channels"),
        LOG_SET("log_set"),
        LOG_CLEAR("log_clear"),
        OOC_COOLDOWN("ooc_cooldown");

        private final String id;

        HubPage(String id) {
            this.id = id;
        }

        public String id() { return id; }
        public String buttonId() { return HUB_PREFIX + ":" + id; }

        /** Scoped ids, so a page's controls can only configure what that page shows. */
        public String buttonId(CommandScope scope) { return HUB_PREFIX + ":" + id + ":" + scope.id(); }
        public String modalId(CommandScope scope) { return ACCESS_MODAL_PREFIX + ":" + id + ":" + scope.id(); }

        public static HubPage from(String raw) {
            for (HubPage page : values())
                if (page.id.equals(raw))
                    return page;
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Master hub
    // ------------------------------------------------------------------

    /** The top-level menu. Each button swaps the panel in place rather than opening a new one. */
    public static List<ActionRow> hub() {
        return List.of(
                ActionRow.of(
                        Button.primary(HubPage.ACCESS.buttonId(), "Access Control")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.PANEL)),
                        Button.primary(HubPage.MANGA.buttonId(), "Manga Settings")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.PAGE))),
                ActionRow.of(
                        Button.primary(HubPage.ECONOMY.buttonId(), "Economy")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.SACRIFICE)),
                        Button.primary(HubPage.OOC.buttonId(), "OOC Album")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ALBUM)),
                        Button.primary(HubPage.LOGGING.buttonId(), "Logging Setup")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.EDIT))));
    }

    private static Button back() {
        return Button.secondary(HubPage.HOME.buttonId(), "Back");
    }

    /** Access-control page: a global toggle plus the two rule editors, unscoped. */
    public static List<ActionRow> accessPage() {
        return guardRows(List.of(
                ActionRow.of(
                        Button.primary(HubPage.ACCESS_TOGGLE.buttonId(CommandScope.ALL), "Enable / Disable")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ENABLED)),
                        Button.secondary(HubPage.ACCESS_ROLES.buttonId(CommandScope.ALL), "Role Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLES)),
                        Button.secondary(HubPage.ACCESS_CHANNELS.buttonId(CommandScope.ALL), "Channel Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_ALLOWED))),
                ActionRow.of(back())));
    }

    /**
     * Manga page.
     *
     * <p>Manga has no persisted settings of its own, so this is a focused view of the
     * access rules for the manga commands rather than an invented settings surface.
     * Every control carries {@link CommandScope#MANGA}, so the pickers it opens list
     * only {@code /manga} and {@code /randompage}.
     */
    public static List<ActionRow> mangaPage() {
        return guardRows(List.of(
                ActionRow.of(
                        Button.primary(HubPage.ACCESS_TOGGLE.buttonId(CommandScope.MANGA), "Enable / Disable")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ENABLED)),
                        Button.secondary(HubPage.ACCESS_ROLES.buttonId(CommandScope.MANGA), "Role Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLES)),
                        Button.secondary(HubPage.ACCESS_CHANNELS.buttonId(CommandScope.MANGA), "Channel Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_ALLOWED))),
                ActionRow.of(back())));
    }

    /**
     * OOC album page: the reroll cooldown, plus the access rules for {@code /ooc}.
     *
     * <p>The album link itself stays on {@code /ooc setlink} rather than moving here. It
     * is a URL, and a dashboard button that opens a modal to paste a URL is more steps
     * than typing the command that already does it.
     */
    public static List<ActionRow> oocPage() {
        return guardRows(List.of(
                ActionRow.of(
                        Button.primary(HubPage.OOC_COOLDOWN.buttonId(), "Reroll Cooldown")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.TIMER))),
                ActionRow.of(
                        Button.secondary(HubPage.ACCESS_TOGGLE.buttonId(CommandScope.OOC), "Enable / Disable")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ENABLED)),
                        Button.secondary(HubPage.ACCESS_ROLES.buttonId(CommandScope.OOC), "Role Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLES)),
                        Button.secondary(HubPage.ACCESS_CHANNELS.buttonId(CommandScope.OOC), "Channel Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_ALLOWED))),
                ActionRow.of(back())));
    }

    /**
     * The reroll cooldown form.
     *
     * <p>A plain text input rather than a picker: the useful values are a continuum, and
     * a select menu would have to guess which six of them a server wants. The service
     * validates and clamps whatever arrives, so a typo cannot store nonsense.
     */
    public static Modal oocCooldown(int currentSeconds) {
        return Modal.create(HubPage.OOC_COOLDOWN.modalId(CommandScope.OOC), "OOC Reroll Cooldown")
                .addComponents(Label.of("Seconds Between Rerolls",
                        "Per member, from " + OocService.MIN_REROLL_COOLDOWN_SECONDS
                                + " to " + OocService.MAX_REROLL_COOLDOWN_SECONDS
                                + ". Zero removes the wait entirely.",
                        TextInput.create(INPUT_OOC_COOLDOWN, TextInputStyle.SHORT)
                                .setValue(String.valueOf(currentSeconds))
                                .setPlaceholder(String.valueOf(OocService.DEFAULT_REROLL_COOLDOWN_SECONDS))
                                .setMinLength(1)
                                .setMaxLength(4)
                                .setRequired(true)
                                .build()))
                .build();
    }

    public static List<ActionRow> loggingPage(boolean configured) {
        return guardRows(List.of(
                ActionRow.of(
                        Button.primary(HubPage.LOG_SET.buttonId(), "Set Log Channel")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.EDIT)),
                        Button.danger(HubPage.LOG_CLEAR.buttonId(), "Disable Logging")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.DELETE))
                                .withDisabled(!configured)),
                ActionRow.of(back())));
    }

    /**
     * The Economy page: eleven economy controls, an access shortcut, and Back.
     *
     * <p>Packed to four rows. The previous layout put each concept on its own row -
     * five rows before Back was even added - which made the sixth row, and the crash,
     * inevitable. Grouping by function keeps it readable and leaves a spare row.
     */
    public static List<ActionRow> economyPage(LegConfigData config) {
        Button toggle = config.isEnabled()
                ? Button.danger(AdminAction.TOGGLE.buttonId(), "Disable Economy")
                : Button.success(AdminAction.TOGGLE.buttonId(), "Enable Economy");

        return guardRows(List.of(
                // Settings
                ActionRow.of(
                        toggle,
                        Button.secondary(AdminAction.SET_WAIT.buttonId(), "Wait Time")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.TIMER)),
                        Button.secondary(AdminAction.ALLOWED_ROLES.buttonId(), "Allowed Roles")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_ALLOWED)),
                        Button.secondary(AdminAction.BANNED_ROLES.buttonId(), "Banned Roles")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_BANNED)),
                        Button.secondary(AdminAction.BYPASS_ROLES.buttonId(), "Bypass Roles")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_BYPASS))),
                // Member moderation
                ActionRow.of(
                        Button.danger(AdminAction.BAN_USER.buttonId(), "Ban")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.BANNED)),
                        Button.success(AdminAction.UNBAN_USER.buttonId(), "Unban")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.UNBANNED)),
                        Button.primary(AdminAction.EDIT_USER.buttonId(), "Edit Stats")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.EDIT)),
                        // "Reset User" and "Delete User" rather than "Reset" and "Delete":
                        // beside "Nuke Economy" on the row below, a bare "Delete" reads as
                        // though it might delete the economy. Naming the object each one
                        // acts on is what separates a one-member action from a guild-wide
                        // one at a glance.
                        Button.danger(AdminAction.RESET_USER.buttonId(), "Reset User")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.RESET)),
                        Button.danger(AdminAction.DELETE_USER.buttonId(), "Delete User")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.DELETE))),
                // Command access, scoped to the economy so it cannot touch manga
                ActionRow.of(
                        Button.primary(HubPage.ACCESS_TOGGLE.buttonId(CommandScope.ECONOMY), "Enable / Disable")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ENABLED)),
                        Button.secondary(HubPage.ACCESS_ROLES.buttonId(CommandScope.ECONOMY), "Role Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLES)),
                        Button.secondary(HubPage.ACCESS_CHANNELS.buttonId(CommandScope.ECONOMY), "Channel Rules")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_ALLOWED))),
                // Destructive, kept on its own row so it is never mis-clicked. The
                // leaderboard link sits here as navigation rather than as a fifth
                // settings row - the page below has room to explain itself, this one
                // does not.
                ActionRow.of(
                        Button.danger(AdminAction.NUKE.buttonId(), "Nuke Economy")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.NUKE)),
                        Button.primary(HubPage.LEADERBOARD.buttonId(), "Leaderboard")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.APEX)),
                        back())));
    }

    /**
     * Who shows up on the public ranking.
     *
     * <p>Its own page rather than more buttons on the Economy one, which is already at
     * four rows - and because these three settings answer a different question from the
     * rest of that page. Nothing here changes anybody's stats; it is all display.
     */
    public static List<ActionRow> leaderboardPage(LegConfigData config) {
        Button banned = config.isHideBannedOnLeaderboard()
                ? Button.success(AdminAction.LB_TOGGLE_BANNED.buttonId(), "Banned: Hidden")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ENABLED))
                : Button.secondary(AdminAction.LB_TOGGLE_BANNED.buttonId(), "Banned: Shown")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.DISABLED));

        Button departed = config.isHideDepartedOnLeaderboard()
                ? Button.success(AdminAction.LB_TOGGLE_DEPARTED.buttonId(), "Left Server: Hidden")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ENABLED))
                : Button.secondary(AdminAction.LB_TOGGLE_DEPARTED.buttonId(), "Left Server: Shown")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.DISABLED));

        return guardRows(List.of(
                ActionRow.of(
                        Button.secondary(AdminAction.LB_HIDDEN_ROLES.buttonId(), "Hidden Roles")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLE_BANNED)),
                        banned,
                        departed),
                ActionRow.of(back())));
    }

    /** The roles whose holders never appear on the leaderboard. */
    public static Modal leaderboardHiddenRoles(List<String> current) {
        return Modal.create(AdminAction.LB_HIDDEN_ROLES.modalId(), "Hidden From Leaderboard")
                .addComponents(Label.of("Roles",
                        "Holders are hidden from the ranking. Submit with none selected to clear.",
                        roleMenu(INPUT_ROLES, current)))
                .build();
    }

    /**
     * Fails fast, and loudly, if a page ever exceeds Discord's limits.
     *
     * <p>The alternative is what happened before: a builder quietly produces six rows
     * and the panel throws on open, in production, with a stack trace that names JDA
     * rather than the layout that caused it.
     */
    private static List<ActionRow> guardRows(List<ActionRow> rows) {
        if (rows.size() > MAX_ROWS)
            throw new IllegalStateException("Admin panel built " + rows.size()
                    + " ActionRows; Discord allows " + MAX_ROWS);

        for (ActionRow row : rows)
            if (row.getComponents().size() > MAX_BUTTONS_PER_ROW)
                throw new IllegalStateException("Admin panel row holds " + row.getComponents().size()
                        + " components; Discord allows " + MAX_BUTTONS_PER_ROW);

        return rows;
    }

    // ------------------------------------------------------------------
    // Access-control modals
    // ------------------------------------------------------------------

    public static Modal toggleCommand(CommandScope scope, List<String> commandNames, List<String> disabled) {
        return Modal.create(HubPage.ACCESS_TOGGLE.modalId(scope), scope.label() + ": Enable or Disable")
                .addComponents(
                        Label.of("Command", "Which command to change.",
                                commandMenu(commandNames, disabled)),
                        Label.of("State", "Whether it should be usable in this server.",
                                StringSelectMenu.create(INPUT_MODE)
                                        .addOption("Enabled", "enabled", "Anyone allowed by the rules may use it")
                                        .addOption("Disabled", "disabled", "Nobody may use it, except server managers")
                                        .setRequiredRange(1, 1)
                                        .build()))
                .build();
    }

    public static Modal roleRule(CommandScope scope, List<String> commandNames, List<String> disabled) {
        return Modal.create(HubPage.ACCESS_ROLES.modalId(scope), scope.label() + ": Role Rules")
                .addComponents(
                        Label.of("Command", "Which command these roles apply to.",
                                commandMenu(commandNames, disabled)),
                        Label.of("Rule Type", "Allow-list replaces open access; deny-list blocks.",
                                modeMenu()),
                        Label.of("Roles", "Submit with none selected to clear this rule.",
                                EntitySelectMenu.create(INPUT_ROLES, SelectTarget.ROLE)
                                        .setPlaceholder("Choose roles")
                                        .setRequiredRange(0, MAX_SELECTIONS)
                                        .setRequired(false)
                                        .build()))
                .build();
    }

    public static Modal channelRule(CommandScope scope, List<String> commandNames, List<String> disabled) {
        return Modal.create(HubPage.ACCESS_CHANNELS.modalId(scope), scope.label() + ": Channel Rules")
                .addComponents(
                        Label.of("Command", "Which command these channels apply to.",
                                commandMenu(commandNames, disabled)),
                        Label.of("Rule Type", "Allow-list confines the command; deny-list blocks.",
                                modeMenu()),
                        Label.of("Channels", "Submit with none selected to clear this rule.",
                                EntitySelectMenu.create(INPUT_CHANNELS, SelectTarget.CHANNEL)
                                        .setPlaceholder("Choose channels")
                                        .setRequiredRange(0, MAX_SELECTIONS)
                                        .setRequired(false)
                                        .build()))
                .build();
    }

    public static Modal logChannel() {
        return Modal.create(HubPage.LOG_SET.modalId(CommandScope.ALL), "Audit Log Channel")
                .addComponents(Label.of("Log Channel",
                        "Every admin action will be posted here.",
                        EntitySelectMenu.create(INPUT_LOG_CHANNEL, SelectTarget.CHANNEL)
                                .setPlaceholder("Choose a channel")
                                .setRequiredRange(1, 1)
                                .build()))
                .build();
    }

    private static StringSelectMenu modeMenu() {
        return StringSelectMenu.create(INPUT_MODE)
                .addOption("Allow-list", "allow", "Only these may use the command")
                .addOption("Deny-list", "deny", "These may not use the command")
                .setRequiredRange(1, 1)
                .build();
    }

    /**
     * A picker of the bot's commands.
     *
     * <p>Built from the live registry rather than a hardcoded list, so a newly added
     * command is configurable the moment it exists. Discord caps a select at 25 options,
     * which is comfortably above the command count and truncates safely if that changes.
     */
    private static StringSelectMenu commandMenu(List<String> commandNames, List<String> disabled) {
        StringSelectMenu.Builder builder = StringSelectMenu.create(INPUT_COMMAND)
                .setPlaceholder("Choose a command")
                .setRequiredRange(1, 1);

        int added = 0;
        for (String name : commandNames) {
            if (added >= MAX_SELECTIONS)
                break;
            builder.addOption("/" + name, name,
                    disabled.contains(name) ? "Currently disabled" : "Currently enabled");
            added++;
        }

        return builder.build();
    }

    /** Every action the dashboard can trigger. */
    public enum AdminAction {
        TOGGLE("toggle"),
        SET_WAIT("set_wait"),
        ALLOWED_ROLES("allowed_roles"),
        BANNED_ROLES("banned_roles"),
        BYPASS_ROLES("bypass_roles"),
        BAN_USER("ban_user"),
        UNBAN_USER("unban_user"),
        RESET_USER("reset_user"),
        DELETE_USER("delete_user"),
        EDIT_USER("edit_user"),
        NUKE("nuke"),
        LB_HIDDEN_ROLES("lb_hidden_roles"),
        LB_TOGGLE_BANNED("lb_toggle_banned"),
        LB_TOGGLE_DEPARTED("lb_toggle_departed"),
        /** Bot-admin role management, absorbed from the retired {@code /adminrole}. */
        ADMIN_ROLES("admin_roles");

        private final String id;

        AdminAction(String id) {
            this.id = id;
        }

        public String id() { return id; }
        public String buttonId() { return BUTTON_PREFIX + ":" + id; }
        public String modalId() { return MODAL_PREFIX + ":" + id; }

        /** Resolves a raw action segment, or null if it names nothing. */
        public static AdminAction from(String raw) {
            for (AdminAction action : values())
                if (action.id.equals(raw))
                    return action;
            return null;
        }

        /** The economy role bucket this action edits, or null if it edits none. */
        public RoleCategory roleCategory() {
            return switch (this) {
                case ALLOWED_ROLES -> RoleCategory.ALLOWED;
                case BANNED_ROLES -> RoleCategory.BANNED;
                case BYPASS_ROLES -> RoleCategory.BYPASS;
                default -> null;
            };
        }
    }

    // ------------------------------------------------------------------
    // Modals
    // ------------------------------------------------------------------

    public static Modal waitPeriod(int currentHours) {
        return Modal.create(AdminAction.SET_WAIT.modalId(), "Set Wait Time")
                .addComponents(Label.of("Wait Period (Hours)",
                        "Currently " + currentHours + " hour(s). Enter 0 to disable the wait.",
                        TextInput.create(INPUT_WAIT_HOURS, TextInputStyle.SHORT)
                                .setPlaceholder(String.valueOf(currentHours))
                                .setValue(String.valueOf(currentHours))
                                .setMinLength(1)
                                .setMaxLength(4)
                                .setRequired(true)
                                .build()))
                .build();
    }

    /**
     * A role picker for one economy category, pre-selected with what is configured.
     *
     * <p>Submitting with nothing selected clears the category - that is the only way
     * to empty a list, and the description says so rather than leaving admins hunting
     * for a "clear" button.
     */
    public static Modal roleCategory(AdminAction action, RoleCategory category, LegConfigData config) {
        return Modal.create(action.modalId(), category.label())
                .addComponents(Label.of(category.label(),
                        "Select the roles for this category. Submit with none selected to clear it.",
                        roleMenu(INPUT_ROLES, currentRoles(category, config))))
                .build();
    }

    /** A role picker for the bot-admin list, pre-selected with what is configured. */
    public static Modal adminRoles(List<String> currentRoleIds) {
        return Modal.create(AdminAction.ADMIN_ROLES.modalId(), "Bot Admin Roles")
                .addComponents(Label.of("Bot Admin Roles",
                        "These roles may use the bot's admin panel. Submit with none selected to clear the list.",
                        roleMenu(INPUT_ROLES, currentRoleIds)))
                .build();
    }

    public static Modal userTarget(AdminAction action, String title, String prompt) {
        return Modal.create(action.modalId(), title)
                .addComponents(Label.of("Member", prompt, userMenu()))
                .build();
    }

    /**
     * A user target that can also reach somebody who is no longer in the server.
     *
     * <p>The house rule is that a snowflake never goes in a text box, and the picker
     * above is why. This is the one case the rule cannot cover: an
     * {@link EntitySelectMenu} can only offer people Discord will resolve <em>in this
     * guild</em>, so a member who left is unreachable through it - and a profile left
     * behind by somebody who left is exactly the profile an admin most often wants to
     * reset or delete.
     *
     * <p>So the picker stays the primary field and is what anybody still present should
     * use; the id box is the escape hatch beneath it, optional, and clearly labelled as
     * being for people who are gone. {@code AdminLegModal} prefers the picker when both
     * are filled, and validates the id is a snowflake before it touches anything.
     */
    public static Modal departedUserTarget(AdminAction action, String title, String prompt) {
        return Modal.create(action.modalId(), title)
                .addComponents(
                        Label.of("Member", prompt,
                                EntitySelectMenu.create(INPUT_USER, SelectTarget.USER)
                                        .setPlaceholder("Choose a member")
                                        .setRequiredRange(0, 1)
                                        .setRequired(false)
                                        .build()),
                        Label.of("Or a User ID",
                                "Only for someone who has left the server. Leave empty otherwise.",
                                TextInput.create(INPUT_USER_ID, TextInputStyle.SHORT)
                                        .setPlaceholder("e.g. 123456789012345678")
                                        .setRequiredRange(0, 20)
                                        .setRequired(false)
                                        .build()))
                .build();
    }

    public static Modal editStats() {
        return Modal.create(AdminAction.EDIT_USER.modalId(), "Edit Economy Stats")
                .addComponents(
                        Label.of("Member", "Whose statistics to overwrite.", userMenu()),
                        Label.of("Legs Given",
                                TextInput.create(INPUT_LEGS_GIVEN, TextInputStyle.SHORT)
                                        .setPlaceholder("Legs Surrendered (e.g., 0, 1, 2)")
                                        .setRequired(true)
                                        .build()),
                        Label.of("Legs Received",
                                TextInput.create(INPUT_LEGS_RECEIVED, TextInputStyle.SHORT)
                                        .setPlaceholder("Legs Consumed (e.g., 20)")
                                        .setRequired(true)
                                        .build()))
                .build();
    }

    public static Modal nukeConfirmation() {
        return Modal.create(AdminAction.NUKE.modalId(), "Nuke Economy")
                .addComponents(Label.of("Type " + NUKE_CONFIRMATION + " to wipe this server",
                        "This erases every Leg profile in this server. It cannot be undone.",
                        TextInput.create(INPUT_CONFIRM, TextInputStyle.SHORT)
                                .setPlaceholder(NUKE_CONFIRMATION)
                                .setMinLength(1)
                                .setMaxLength(16)
                                .setRequired(true)
                                .build()))
                .build();
    }

    // ------------------------------------------------------------------
    // Menu builders
    // ------------------------------------------------------------------

    private static EntitySelectMenu roleMenu(String id, List<String> preselected) {
        EntitySelectMenu.Builder builder = EntitySelectMenu.create(id, SelectTarget.ROLE)
                .setPlaceholder("Choose roles")
                .setRequiredRange(0, MAX_SELECTIONS)
                .setRequired(false);

        List<EntitySelectMenu.DefaultValue> defaults = defaultRoles(preselected);
        if (!defaults.isEmpty())
            builder.setDefaultValues(defaults);

        return builder.build();
    }

    private static EntitySelectMenu userMenu() {
        return EntitySelectMenu.create(INPUT_USER, SelectTarget.USER)
                .setPlaceholder("Choose a member")
                .setRequiredRange(1, 1)
                .build();
    }

    /**
     * Turns stored role IDs into menu defaults.
     *
     * <p>Skips anything malformed and truncates to Discord's cap: stored config can
     * outlive the roles it names, and one stale ID must not stop the modal opening -
     * which is exactly the state an admin needs the modal to fix.
     */
    private static List<EntitySelectMenu.DefaultValue> defaultRoles(List<String> roleIds) {
        List<EntitySelectMenu.DefaultValue> defaults = new ArrayList<>();
        if (roleIds == null)
            return defaults;

        for (String id : roleIds) {
            if (defaults.size() >= MAX_SELECTIONS)
                break;
            try {
                defaults.add(EntitySelectMenu.DefaultValue.role(id));
            } catch (RuntimeException ignored) {
                // Not a snowflake any more; the admin is here to replace it anyway.
            }
        }
        return defaults;
    }

    private static List<String> currentRoles(RoleCategory category, LegConfigData config) {
        return switch (category) {
            case ALLOWED -> config.getAllowedRoles();
            case BANNED -> config.getBannedRoles();
            case BYPASS -> config.getBypassWaitRoles();
        };
    }

    /** Filters selected IDs down to roles that genuinely exist in this guild. */
    public static List<String> validateRoles(Guild guild, List<String> selectedIds) {
        List<String> valid = new ArrayList<>();
        if (guild == null || selectedIds == null)
            return valid;

        for (String id : selectedIds)
            if (!valid.contains(id) && guild.getRoleById(id) != null)
                valid.add(id);

        return valid;
    }
}
