package org.bunnys.buttons;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.leg.LegComponents;
import org.bunnys.beastars.commands.leg.LegEmbeds;
import org.bunnys.beastars.commands.leg.LegService;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;

/**
 * Confirm/cancel on a pending leg offer.
 *
 * <p>Id format: {@code leg_offer:<confirm|cancel>:<targetId>:<senderId>} - built by
 * {@link LegComponents#offerConfirmation}.
 *
 * <p>{@code ButtonRouter} already runs this on the bounded command executor, so the
 * old {@code CompletableFuture.runAsync} wrapper only moved work off that pool and
 * out from under its backpressure. It is gone.
 */
public class LegOfferButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return LegComponents.OFFER_PREFIX;
    }

    /**
     * Confirm and Cancel both mutate state, and Confirm spends a leg permanently.
     *
     * <p>The database guard already makes a double-spend impossible, but without this a
     * double-click still costs two round trips and shows the user a confusing second
     * "Flesh Exhausted" on their own successful offer.
     */
    @Override
    public long cooldownMillis() {
        return ECONOMY_COOLDOWN;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (args.length < 4)
            return;

        String action = args[1];
        String targetId = args[2];
        String senderId = args[3];

        if (!event.getUser().getId().equals(senderId)) {
            event.replyEmbeds(LegEmbeds.denied("Not Your Sacrifice",
                    "You cannot confirm someone else's sacrifice.")).setEphemeral(true).queue();
            return;
        }

        if (event.getGuild() == null) {
            event.replyEmbeds(LegEmbeds.denied("Server Only",
                    "The flesh economy only exists inside a server.")).setEphemeral(true).queue();
            return;
        }

        event.deferEdit().queue();

        if ("cancel".equals(action)) {
            event.getHook().editOriginalEmbeds(LegEmbeds.offerCancelled(event.getUser()))
                    .setComponents().queue(null, e -> {});
            return;
        }

        if (!"confirm".equals(action))
            return;

        String guildId = event.getGuild().getId();

        // Cache-first: the target is almost always already known to JDA, and a
        // REST fetch per confirmation is pure latency at scale.
        User cached = event.getJDA().getUserById(targetId);
        if (cached != null) {
            complete(event, guildId, cached);
            return;
        }

        event.getJDA().retrieveUserById(targetId).queue(
                target -> complete(event, guildId, target),
                error -> event.getHook().editOriginalEmbeds(LegEmbeds.error("Target Lost",
                        "Could not locate the member you were feeding.")).setComponents().queue(null, e -> {}));
    }

    private static void complete(ButtonInteractionEvent event, String guildId, User receiver) {
        LegService.OfferOutcome outcome = LegService.offerLeg(guildId, event.getUser(), receiver);

        if (outcome.status() != LegService.OfferStatus.SUCCESS) {
            event.getHook().editOriginalEmbeds(LegEmbeds.offerFailure(outcome.status()))
                    .setComponents().queue(null, e -> {});
            return;
        }

        // A leg is permanent and untakeable-back, so the transfer belongs in the trail
        // next to the admin edits that can undo it - otherwise the log shows a moderator
        // resetting somebody's count with no record of how they earned it.
        //
        // The channel is passed as the fallback for signature symmetry only: an economy
        // entry never posts there and never nudges about configuration. See
        // AuditService.Category - the person clicking this is a member, not an admin.
        AuditService.recordLegTransfer(event.getGuild(), event.getUser(), receiver,
                event.getChannel(), outcome.legsRemaining());

        event.getHook()
                .editOriginalEmbeds(LegEmbeds.offerAccepted(event.getUser(), receiver, outcome.legsRemaining()))
                .setComponents().queue(null, e -> {});
    }
}
