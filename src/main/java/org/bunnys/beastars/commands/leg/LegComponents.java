package org.bunnys.beastars.commands.leg;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.beastars.BeastarsEmoji;

import java.util.List;

/**
 * Every button row the Leg feature can produce, and the custom-id grammar behind them.
 *
 * <p>Custom ids are built here and parsed by the handlers in {@code org.bunnys.buttons}.
 * Keeping the format in one place means a change to the id layout is a change to
 * this file, not a hunt through three button classes for string concatenation.
 *
 * <pre>
 *   leg_offer:&lt;confirm|cancel&gt;:&lt;targetId&gt;:&lt;senderId&gt;
 *   leg_stats:&lt;overview|history&gt;:&lt;targetId&gt;:&lt;page&gt;
 *   leg_lb:&lt;first|prev|next|last|find&gt;:&lt;page&gt;:&lt;callerId&gt;
 * </pre>
 */
public final class LegComponents {

    public static final String OFFER_PREFIX = "leg_offer";
    public static final String STATS_PREFIX = "leg_stats";
    public static final String LEADERBOARD_PREFIX = "leg_lb";

    public static final String PAGE_OVERVIEW = "overview";
    public static final String PAGE_HISTORY = "history";

    private LegComponents() {}

    public static ActionRow offerConfirmation(User receiver, User sender) {
        String suffix = ":" + receiver.getId() + ":" + sender.getId();
        return ActionRow.of(
                Button.success(OFFER_PREFIX + ":confirm" + suffix, "Confirm Sacrifice")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.SACRIFICE)),
                Button.danger(OFFER_PREFIX + ":cancel" + suffix, "Cancel"));
    }

    /**
     * The stats view toggle, plus history pagination when there is more than one page.
     *
     * @param page     current history page (ignored on the overview)
     * @param maxPages total history pages
     */
    public static List<ActionRow> stats(String targetId, String pageType, int page, int maxPages) {
        boolean onHistory = PAGE_HISTORY.equals(pageType);

        ActionRow toggle = ActionRow.of(
                Button.primary(STATS_PREFIX + ":" + PAGE_OVERVIEW + ":" + targetId + ":1", "Overview")
                        .withDisabled(!onHistory),
                Button.secondary(STATS_PREFIX + ":" + PAGE_HISTORY + ":" + targetId + ":1", "Interaction History")
                        .withDisabled(onHistory));

        if (!onHistory || maxPages <= 1)
            return List.of(toggle);

        return List.of(toggle, ActionRow.of(
                historyButton(targetId, 1, "<<", page <= 1),
                historyButton(targetId, page - 1, "<", page <= 1),
                historyButton(targetId, page + 1, ">", page >= maxPages),
                historyButton(targetId, maxPages, ">>", page >= maxPages)));
    }

    private static Button historyButton(String targetId, int page, String label, boolean disabled) {
        return Button.secondary(STATS_PREFIX + ":" + PAGE_HISTORY + ":" + targetId + ":" + page, label)
                .withDisabled(disabled);
    }

    public static final String ACTION_FIRST = "first";
    public static final String ACTION_PREVIOUS = "prev";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_LAST = "last";
    public static final String ACTION_FIND = "find";
    public static final String ACTION_FILTER = "filter";
    public static final String ACTION_ALL = "all";

    /** Takes a fresh ranking and re-pins it. Only meaningful on a frozen board. */
    public static final String ACTION_UPDATE = "update";

    private static final String MODE_FROZEN = "f";
    private static final String MODE_LIVE = "l";

    /**
     * One open leaderboard: who opened it, when, and whether it is pinned.
     *
     * <p>Carried through every button on the message. {@code callerId} and
     * {@code openedAtMillis} together form the key its snapshot is stored under, so a
     * board can find its own pinned ranking without ever knowing its message id.
     */
    public record Session(String callerId, long openedAtMillis, boolean frozen) {

        /** A newly opened board. */
        public static Session opening(String callerId, boolean frozen) {
            return new Session(callerId, System.currentTimeMillis(), frozen);
        }

        /** The key {@code LegLeaderboardSnapshots} pins this board's ranking under. */
        public String snapshotKey() {
            return LegLeaderboardSnapshots.key(callerId, openedAtMillis);
        }
    }

    /**
     * Parses a leaderboard button id, or null when it is malformed.
     *
     * <p>Messages built before freezing existed carry only four segments. Those are read
     * as live boards rather than refused, so an old leaderboard still pages instead of
     * going dead the moment this deploys.
     */
    public static Session parseSession(String[] args) {
        if (args.length < 4)
            return null;

        String callerId = args[3];

        if (args.length < 6)
            return new Session(callerId, 0L, false);

        try {
            return new Session(callerId, Long.parseLong(args[5]), MODE_FROZEN.equals(args[4]));
        } catch (NumberFormatException e) {
            return new Session(callerId, 0L, false);
        }
    }

    /** Modal id for the role picker, and the id of the select inside it. */
    public static final String FILTER_MODAL = "leg_lb_filter";
    public static final String INPUT_FILTER_ROLES = "leg_filter_roles";

    /**
     * Paging on its own row; the filter and freshness controls on a second, since five is
     * Discord's limit.
     *
     * @param session  the board's identity: caller and the moment it was opened. Every
     *                 button carries it so the handler can find the pinned snapshot.
     * @param frozen   true for a pinned board, false for one that reads live
     */
    public static List<ActionRow> leaderboard(int page, int maxPages, Session session,
                                              boolean filtered, boolean frozen) {
        return List.of(
                ActionRow.of(
                        leaderboardButton(ACTION_FIRST, 1, session, "<<", page <= 1),
                        leaderboardButton(ACTION_PREVIOUS, page - 1, session, "<", page <= 1),
                        Button.success(id(ACTION_FIND, 1, session), "Find Me")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.YOU)),
                        leaderboardButton(ACTION_NEXT, page + 1, session, ">", page >= maxPages),
                        leaderboardButton(ACTION_LAST, maxPages, session, ">>", page >= maxPages)),
                ActionRow.of(
                        Button.primary(id(ACTION_FILTER, page, session), "Filter by Role")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.ROLES)),
                        // Only offered when there is something to undo, so the row reads
                        // as the current state rather than as two equal choices.
                        Button.secondary(id(ACTION_ALL, 1, session), "Show Everyone")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.APEX))
                                .withDisabled(!filtered),
                        // Disabled rather than hidden on a live board: the control still
                        // says what mode you are in, and a row that changes width between
                        // renders is harder to use than one that greys a button out.
                        Button.secondary(id(ACTION_UPDATE, page, session), frozen ? "Update" : "Live")
                                .withEmoji(BeastarsEmoji.button(BeastarsEmoji.RANDOM))
                                .withDisabled(!frozen)));
    }

    /**
     * The role picker.
     *
     * <p>An {@code EntitySelectMenu} rather than a text box, so nobody has to find a role
     * snowflake, and pre-populated with whatever is already applied so the dialog shows
     * the current state instead of resetting it.
     *
     * <p>Submitting with nothing selected clears the filter - that is why the range starts
     * at zero, and why the description says so out loud.
     */
    public static Modal roleFilter(List<String> currentRoleIds) {
        EntitySelectMenu.Builder menu = EntitySelectMenu.create(INPUT_FILTER_ROLES, SelectTarget.ROLE)
                .setPlaceholder("Choose roles")
                .setRequiredRange(0, MAX_FILTER_ROLES)
                .setRequired(false);

        if (currentRoleIds != null && !currentRoleIds.isEmpty())
            menu.setDefaultValues(currentRoleIds.stream()
                    .limit(MAX_FILTER_ROLES)
                    .map(EntitySelectMenu.DefaultValue::role)
                    .toList());

        return Modal.create(FILTER_MODAL, "Filter the Leaderboard")
                .addComponents(Label.of("Roles",
                        "Only members holding one of these are shown. Submit empty to clear.",
                        menu.build()))
                .build();
    }

    /** Discord allows 25; more than a handful of roles is not a filter any more. */
    private static final int MAX_FILTER_ROLES = 10;

    private static Button leaderboardButton(String action, int page, Session session,
                                            String label, boolean disabled) {
        return Button.secondary(id(action, page, session), label).withDisabled(disabled);
    }

    /**
     * {@code leg_lb:<action>:<page>:<callerId>:<mode>:<openedAt>}
     *
     * <p>The trailing two segments are what let a board be frozen. {@code mode} says
     * whether this board pins a snapshot, and {@code openedAt} completes the key that
     * snapshot is stored under. Both ride in the id because there is nowhere else to put
     * them: the message id does not exist yet when the first render is built.
     */
    private static String id(String action, int page, Session session) {
        return LEADERBOARD_PREFIX + ":" + action + ":" + Math.max(1, page) + ":"
                + session.callerId() + ":" + (session.frozen() ? MODE_FROZEN : MODE_LIVE)
                + ":" + session.openedAtMillis();
    }
}
