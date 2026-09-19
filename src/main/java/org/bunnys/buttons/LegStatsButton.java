package org.bunnys.buttons;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.beastars.commands.leg.LegComponents;
import org.bunnys.beastars.commands.leg.LegEmbeds;
import org.bunnys.beastars.commands.leg.LegService;
import org.bunnys.beastars.database.LegData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;

/**
 * Overview / history toggle and history pagination on a {@code /leg stats} card.
 *
 * <p>Id format: {@code leg_stats:<overview|history>:<targetId>:<page>} - built by
 * {@link LegComponents#stats}.
 */
public class LegStatsButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return LegComponents.STATS_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 3)
            return;

        if (event.getGuild() == null) {
            event.replyEmbeds(LegEmbeds.denied("Server Only",
                    "The flesh economy only exists inside a server.")).setEphemeral(true).queue();
            return;
        }

        String pageType = args[1];
        String targetId = args[2];
        int page = args.length > 3 ? parsePage(args[3]) : 1;

        event.deferEdit().queue();

        // Cache-first: page-turning must not cost a REST user fetch per click.
        User cached = event.getJDA().getUserById(targetId);
        if (cached != null) {
            render(event, cached, pageType, page);
            return;
        }

        event.getJDA().retrieveUserById(targetId).queue(
                target -> render(event, target, pageType, page),
                error -> event.getHook().sendMessageEmbeds(LegEmbeds.error("Member Not Found",
                        "Could not fetch that member's profile.")).setEphemeral(true).queue(null, e -> {}));
    }

    private static void render(ButtonInteractionEvent event, User target, String pageType, int page) {
        LegData data = LegService.getUser(event.getGuild().getId(), target.getId());

        if (LegComponents.PAGE_HISTORY.equals(pageType)) {
            LegEmbeds.HistoryPage history = LegEmbeds.statsHistory(target, data, page);
            event.getHook().editOriginalEmbeds(history.embed())
                    .setComponents(LegComponents.stats(target.getId(), LegComponents.PAGE_HISTORY,
                            history.page(), history.maxPages()))
                    .queue(null, e -> {});
            return;
        }

        event.getHook().editOriginalEmbeds(LegEmbeds.statsOverview(target, data))
                .setComponents(LegComponents.stats(target.getId(), LegComponents.PAGE_OVERVIEW, 1, 1))
                .queue(null, e -> {});
    }

    /** Malformed ids come from stale messages, not from us - fall back rather than throw. */
    private static int parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
