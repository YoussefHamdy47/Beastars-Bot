package org.bunnys.handler.router.selects;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
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
 * Routes string select-menu choices to their handler.
 *
 * <p>Deliberately the same shape as {@code ButtonRouter}, down to the backpressure and
 * the crash reply, because a select menu is the same kind of thing as a button: a
 * component id, a prefix, a bounded pool, and a handler that may fail.
 */
public final class SelectRouter {

    private static final Map<String, BunnySelect> HANDLERS = new ConcurrentHashMap<>();

    private SelectRouter() {}

    public static void loadSelects(String packageName) {
        BunnyLog.info("[SelectRouter] Scanning " + packageName + "...");

        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .forPackage(packageName)
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setScanners(Scanners.SubTypes));

        int loaded = 0;

        for (Class<? extends BunnySelect> clazz : reflections.getSubTypesOf(BunnySelect.class)) {
            try {
                BunnySelect handler = clazz.getDeclaredConstructor().newInstance();
                HANDLERS.put(handler.getPrefix(), handler);
                loaded++;
            } catch (Exception e) {
                BunnyLog.error("[SelectRouter] Failed to load " + clazz.getSimpleName(), e);
            }
        }

        BunnyLog.success("[SelectRouter] Loaded " + loaded + " select handlers.");
    }

    /** Registered handler count, for the startup summary. */
    public static int count() {
        return HANDLERS.size();
    }

    public static void handle(BunnyHub client, StringSelectInteractionEvent event) {
        // Publication fence for the rebound emoji constants; see EmojiRegistry.fence().
        // Must precede the executor submit so workers inherit the edge via the queue.
        EmojiRegistry.fence();

        String componentId = event.getComponentId();
        if (componentId.isBlank())
            return;

        String[] parts = componentId.split(":");
        BunnySelect handler = HANDLERS.get(parts[0]);

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
                    ErrorReporter.reportAndReply("select " + componentId, err, event);
                }
            });
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[SelectRouter] " + componentId + " rejected under load");
            // The choice never ran, so it must not hold a cooldown against the user.
            ComponentCooldowns.release(parts[0], event.getUser().getId());
            if (!event.isAcknowledged())
                event.replyEmbeds(SystemEmbeds.busy()).setEphemeral(true).queue(null, e -> {});
        }
    }
}
