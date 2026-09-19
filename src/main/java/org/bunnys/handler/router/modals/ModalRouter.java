package org.bunnys.handler.router.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import org.bunnys.handler.BunnyHub;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.EmojiRegistry;
import org.bunnys.utils.ErrorReporter;
import org.bunnys.utils.SystemEmbeds;
import org.reflections.Reflections;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@SuppressWarnings("unused")
public class ModalRouter {
    // Upgraded to ConcurrentHashMap for absolute thread safety
    private static final Map<String, BunnyModal> MODALS = new ConcurrentHashMap<>();

    private ModalRouter() {}

    public static void loadModals(String packageName) {
        try {
            Reflections reflections = new Reflections(packageName);
            Set<Class<? extends BunnyModal>> classes = reflections.getSubTypesOf(BunnyModal.class);

            for (Class<? extends BunnyModal> clazz : classes) {
                try {
                    BunnyModal modal = clazz.getDeclaredConstructor().newInstance();
                    MODALS.put(modal.getPrefix().toLowerCase(), modal);
                } catch (Exception e) {
                    BunnyLog.error("[ModalRouter] Failed to instantiate modal: " + clazz.getName(), e);
                }
            }
            BunnyLog.info("[ModalRouter] Successfully loaded " + MODALS.size() + " modals.");
        } catch (Exception e) {
            BunnyLog.error("[ModalRouter] Critical failure loading modals from package: " + packageName, e);
        }
    }

    /** Registered handler count, for the startup summary. */
    public static int count() {
        return MODALS.size();
    }

    public static void handle(BunnyHub client, ModalInteractionEvent event) {
        // Publication fence for the rebound emoji constants; see EmojiRegistry.fence().
        // Must precede the executor submit so workers inherit the edge via the queue.
        EmojiRegistry.fence();

        try {
            String[] args = event.getModalId().split(":");
            String targetModal = args[0].toLowerCase();
            BunnyModal modal = MODALS.get(targetModal);

            if (modal != null) {
                // Offload to bounded pool
                client.getCommandExecutor().submit(() -> {
                    try {
                        modal.execute(client, event, args);
                    } catch (Throwable err) {
                        ErrorReporter.reportAndReply("modal " + targetModal, err, event);
                    }
                });
            } else {
                BunnyLog.error("[ModalRouter] Unregistered modal triggered: " + targetModal);
                event.replyEmbeds(SystemEmbeds.error("Unknown Form", "That form is no longer available. Reopen it from the command that created it."))
                        .setEphemeral(true)
                        .queue(null, e -> {});
            }
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[ModalRouter] Modal interaction rejected under load: " + event.getModalId());
            if (!event.isAcknowledged()) {
                event.replyEmbeds(SystemEmbeds.busy())
                        .setEphemeral(true).queue(null, e -> {});
            }
        } catch (Throwable e) {
            // Routing itself failed, before any handler ran. Same reporting path as a
            // handler crash: this is exactly as unhandled, and just as invisible without it.
            ErrorReporter.reportAndReply("modal routing " + event.getModalId(), e, event);
        }
    }
}