package org.bunnys.buttons;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.bunnys.beastars.commands.leg.LegComponents;
import org.bunnys.beastars.commands.leg.LegComponents.Session;
import org.bunnys.beastars.commands.leg.LegEmbeds;
import org.bunnys.beastars.commands.leg.LegLeaderboardFilters;
import org.bunnys.beastars.commands.leg.LegLeaderboardPolicy;
import org.bunnys.beastars.commands.leg.LegLeaderboardSnapshots;
import org.bunnys.beastars.commands.leg.LegService;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.ComponentCooldowns;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.SystemEmbeds;

import java.util.List;

/**
 * Pagination, "Find Me", filtering and refreshing on the leg leaderboard.
 *
 * <p>Id format: {@code leg_lb:<action>:<page>:<callerId>:<mode>:<openedAt>}, built by
 * {@link LegComponents#leaderboard}. {@code callerId} is carried through unchanged so the
 * rebuilt row keeps the original ids; "Find Me" deliberately locates whoever
 * <em>clicked</em>, not whoever opened the board.
 *
 * <h2>Frozen by default</h2>
 * A board pins the ranking it opened with and pages through that, so the rows cannot
 * reshuffle underneath a reader mid-browse. Update takes a fresh ranking on request. A
 * board opened with {@code live:true} skips the pin and reads current data on every click,
 * which is the older behaviour and still occasionally what somebody wants.
 *
 * <p>Each click used to pull every leg document in the guild out of Mongo and sort it in
 * memory. {@link LegService} now serves a cached, index-backed, projected snapshot, so a
 * page turn is a list slice.
 */
public class LegLeaderboardButton extends BunnyButton {

    /**
     * Update is the one action here that can force a database read, so it is the one
     * action that is throttled. Paging stays free, which is the whole reason the router's
     * blanket cooldown was removed in the first place.
     */
    private static final long UPDATE_COOLDOWN_MILLIS = 10_000L;

    private static final String UPDATE_COOLDOWN_KEY = "leg_lb_update";

    @Override
    public String getPrefix() {
        return LegComponents.LEADERBOARD_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        Session session = LegComponents.parseSession(args);
        if (session == null)
            return;

        if (event.getGuild() == null) {
            event.replyEmbeds(LegEmbeds.denied("Server Only",
                    "The flesh economy only exists inside a server.")).setEphemeral(true).queue();
            return;
        }

        String action = args[1];
        int requestedPage = parsePage(args[2]);
        String messageId = event.getMessageId();
        String guildId = event.getGuild().getId();

        // The picker opens a modal, which is a reply to this interaction rather than an
        // edit of it, so it has to return before anything defers below.
        if (LegComponents.ACTION_FILTER.equals(action)) {
            if (!event.getGuild().isLoaded()) {
                event.replyEmbeds(LegEmbeds.filterUnavailable(
                        client.getCommandRegistry().getDeveloperIds())).setEphemeral(true).queue();
                return;
            }
            event.replyModal(LegComponents.roleFilter(LegLeaderboardFilters.get(messageId)))
                    .queue(null, e -> {});
            return;
        }

        if (LegComponents.ACTION_UPDATE.equals(action)) {
            // Defensive: the control is rendered disabled on a live board, but a stale
            // message could still carry an enabled one.
            if (!session.frozen()) {
                event.replyEmbeds(LegEmbeds.error("Already Live",
                        "This board already reads the latest ranking on every click."))
                        .setEphemeral(true).queue(null, e -> {});
                return;
            }

            long remaining = ComponentCooldowns.claim(UPDATE_COOLDOWN_KEY,
                    guildId + ":" + event.getUser().getId(), UPDATE_COOLDOWN_MILLIS);

            if (remaining > 0) {
                event.replyEmbeds(SystemEmbeds.buttonCooldown(remaining))
                        .setEphemeral(true).queue(null, e -> {});
                return;
            }

            // Drop the guild's cached ranking so this genuinely re-reads. Without it an
            // Update inside the cache window would return the same object and the reader
            // would be told nothing changed when something had.
            LegService.invalidateLeaderboard(guildId);
            LegLeaderboardSnapshots.clear(session.snapshotKey());
        }

        if (LegComponents.ACTION_ALL.equals(action))
            LegLeaderboardFilters.clear(messageId);

        event.deferEdit().queue();

        String highlight = LegComponents.ACTION_FIND.equals(action) ? event.getUser().getId() : null;

        render(event.getHook(), event.getGuild(), messageId, requestedPage, highlight, session);
    }

    /**
     * Re-renders the board under whatever filter the message currently carries.
     *
     * <p>Shared with the modal handler so a filtered board pages correctly: the filter has
     * to survive a Next click, and it does because it is keyed by the message rather than
     * encoded in the button.
     */
    public static void render(InteractionHook hook, Guild guild, String messageId,
                              int requestedPage, String highlight, Session session) {

        List<String> roleIds = LegLeaderboardFilters.get(messageId);
        LegLeaderboardPolicy.Result policy = LegLeaderboardPolicy.build(guild, roleIds);

        // The member list went away between choosing a filter and using it. Drop the
        // viewer's filter rather than leaving a board that claims to be filtered and
        // is not; the embed explains the rest.
        if (policy.membersUnavailable() && !roleIds.isEmpty()) {
            LegLeaderboardFilters.clear(messageId);
            roleIds = List.of();
        }

        LegLeaderboardSnapshots.View view = LegLeaderboardSnapshots.view(
                session, guild.getId(), requestedPage, highlight, policy.filter());

        if (view.page().failed()) {
            hook.sendMessageEmbeds(LegEmbeds.error("Records Unreachable",
                    "Failed to retrieve the Black Market records.")).setEphemeral(true).queue(null, e -> {});
            return;
        }

        hook.editOriginalEmbeds(LegEmbeds.leaderboard(view.page(), roleIds,
                        session.frozen(), view.snapshotAtEpochSeconds()))
                .setComponents(LegComponents.leaderboard(view.page().page(), view.page().maxPages(),
                        session, !roleIds.isEmpty(), session.frozen()))
                .queue(null, e -> {});
    }

    /** Malformed ids come from stale messages, not from us, so fall back rather than throw. */
    private static int parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
