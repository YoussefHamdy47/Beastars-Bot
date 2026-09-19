package org.bunnys.handler.router.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.ComponentCooldowns;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.EmojiRegistry;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Routes button clicks to their handler, and enforces whatever cooldown that handler declares.
 *
 * <h2>Rate limiting</h2>
 * The router holds no cooldown policy of its own. It asks the resolved handler for
 * {@link BunnyButton#cooldownMillis()} and enforces exactly that - zero means the click
 * goes straight through with no cache read at all, which is the case for every
 * pagination control in the bot. The bookkeeping lives in {@link ComponentCooldowns},
 * shared with the select-menu router.
 *
 * <p>This replaces a single hardcoded 2-second cooldown applied indiscriminately to
 * every button in the bot - which throttled manga page-turning, where rapid clicking is
 * the entire point, while being too short to actually protect a destructive economy
 * action.
 */
public final class ButtonRouter {

    private static final Map<String, BunnyButton> HANDLERS = new ConcurrentHashMap<>();

    private ButtonRouter() {}

    public static void loadButtons(String packageName) {
        BunnyLog.info("[ButtonRouter] Scanning " + packageName + "...");

        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackage(packageName)
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setScanners(Scanners.SubTypes));

        int loaded = 0;

        for (Class<? extends BunnyButton> clazz : reflections.getSubTypesOf(BunnyButton.class)) {
            try {
                BunnyButton handler = clazz.getDeclaredConstructor().newInstance();
                HANDLERS.put(handler.getPrefix(), handler);
                loaded++;
            } catch (Exception e) {
                BunnyLog.error("[ButtonRouter] Failed to load " + clazz.getSimpleName(), e);
            }
        }

        BunnyLog.success("[ButtonRouter] Loaded " + loaded + " button handlers.");
    }

    /** Registered handler count, for the startup summary. */
    public static int count() {
        return HANDLERS.size();
    }

    public static void handle(BunnyHub client, ButtonInteractionEvent event) {
        // Publication fence for the rebound emoji constants; see EmojiRegistry.fence().
        // Must precede the executor submit so workers inherit the edge via the queue.
        EmojiRegistry.fence();

        String componentId = event.getComponentId();
        if (componentId.isBlank())
            return;

        String[] parts = componentId.split(":");
        BunnyButton handler = HANDLERS.get(parts[0]);

        if (handler == null)
            return;

        long remaining = ComponentCooldowns.claim(parts[0], event.getUser().getId(), handler.cooldownMillis());
        if (remaining > 0) {
            event.replyEmbeds(SystemEmbeds.buttonCooldown(remaining)).setEphemeral(true).queue(null, e -> {});
            return;
        }

        try {
            // Offload to the bounded pool so gateway threads never run command logic.
            client.getCommandExecutor().submit(() -> {
                try {
                    handler.execute(client, event, parts);
                } catch (Throwable err) {
                    ErrorReporter.reportAndReply("button " + componentId, err, event);
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[ButtonRouter] " + componentId + " rejected under load");
            // The click never ran, so it must not hold a cooldown against the user.
            ComponentCooldowns.release(parts[0], event.getUser().getId());
            if (!event.isAcknowledged())
                event.replyEmbeds(SystemEmbeds.busy()).setEphemeral(true).queue(null, e -> {});
        }
    }
}
