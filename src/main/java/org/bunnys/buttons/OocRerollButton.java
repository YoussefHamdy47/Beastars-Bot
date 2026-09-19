package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.beastars.commands.ooc.OocComponents;
import org.bunnys.beastars.commands.ooc.OocEmbeds;
import org.bunnys.beastars.commands.ooc.OocService;
import org.bunnys.beastars.commands.ooc.OocService.RandomImage;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.ComponentCooldowns;
import org.bunnys.handler.router.buttons.BunnyButton;
import org.bunnys.utils.SystemEmbeds;

/**
 * Draws another image from the server's OOC album, in place.
 *
 * <p>Edits the existing message rather than posting a new one. A button whose whole
 * purpose is "show me another" would otherwise fill the channel with images nobody
 * scrolled back for, and the picture is the entire message, so replacing it loses
 * nothing.
 *
 * <h2>The cooldown is enforced here, not by the router</h2>
 * {@link BunnyButton#cooldownMillis()} is a constant the router reads before dispatch,
 * which is right for a policy the code owns and wrong for one an administrator sets per
 * server. Reading it in the router would also mean a database call on a gateway thread
 * whenever the cache is cold.
 *
 * <p>So this declares no router cooldown and claims one itself, on the worker thread,
 * against the guild's configured value. The claim is keyed by guild <em>and</em> member:
 * one person clicking repeatedly should not stop everyone else, which is the behaviour a
 * shared button in a busy channel needs.
 */
public class OocRerollButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return OocComponents.REROLL_PREFIX;
    }

    /** See the class note: the real cooldown is per guild and is claimed inside {@link #execute}. */
    @Override
    public long cooldownMillis() {
        return NO_COOLDOWN;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        if (event.getGuild() == null) {
            event.replyEmbeds(OocEmbeds.error("Server Only",
                    "The OOC album only exists inside a server.")).setEphemeral(true).queue();
            return;
        }

        String guildId = event.getGuild().getId();
        long cooldownMillis = OocService.rerollCooldownSeconds(guildId) * 1000L;

        long remaining = ComponentCooldowns.claim(
                getPrefix(), guildId + ":" + event.getUser().getId(), cooldownMillis);

        if (remaining > 0) {
            // Ephemeral, so a throttled click does not add noise to the channel the
            // button is trying to keep tidy.
            event.replyEmbeds(SystemEmbeds.buttonCooldown(remaining)).setEphemeral(true)
                    .queue(null, e -> {});
            return;
        }

        event.deferEdit().queue();

        RandomImage result = OocService.randomImage(guildId);

        if (!result.ok()) {
            // The message on screen is still a perfectly good image, so it is left alone
            // and the failure goes only to whoever clicked. Giving the claim back means a
            // transient Imgur outage does not also lock them out for the cooldown.
            ComponentCooldowns.release(getPrefix(), guildId + ":" + event.getUser().getId());

            event.getHook().sendMessageEmbeds(OocEmbeds.failure(result.outcome()))
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        event.getHook().editOriginal(result.url())
                .setComponents(OocComponents.imageControls(result.url()))
                .queue(null, e -> {});
    }
}
