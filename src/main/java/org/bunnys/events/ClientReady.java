package org.bunnys.events;

import com.github.lalyos.jfiglet.FigletFont;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import org.bunnys.beastars.commands.presence.PresenceService;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.events.BunnyEvent;
import org.bunnys.handler.router.buttons.ButtonRouter;
import org.bunnys.handler.router.modals.ModalRouter;
import org.bunnys.handler.router.selects.SelectRouter;
import org.bunnys.utils.ApplicationEmojiExporter;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.EmojiRegistry;
import org.bunnys.utils.ErrorReporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;

/**
 * Startup: deploy commands, bind emoji to the running application, then report.
 *
 * <p>The emoji sync happens before the banner is printed, and the banner is printed from
 * its completion callback - on both the success and failure paths - so the summary
 * reports what actually happened rather than racing an in-flight REST call.
 */
@SuppressWarnings("unused")
public class ClientReady extends BunnyEvent {

    /** Where the generated emoji block lives, for the optional dev-time regeneration. */
    private static final Path APP_DESIGN = Path.of("src/main/java/org/bunnys/utils/AppDesign.java");

    private static final int LABEL_WIDTH = 14;
    private static final int RULE_WIDTH = 52;

    public ClientReady(BunnyHub client) {
        super(client);
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();

        // The reporter can only send crash reports once there is a gateway to send them
        // over; until now it has been console-only.
        ErrorReporter.attach(jda);

        client.getCommandRegistry().deployCommands();

        // Application emoji IDs differ per application; rebind before anything renders.
        EmojiRegistry.sync(jda, () -> {
            if (client.isReloadEmojis())
                regenerateEmojiConstants(jda);

            printBanner(jda);
        });

        restorePresence(jda);
    }

    /**
     * Puts back whatever presence was last set, or the Beastars default if none was.
     *
     * <p>This replaced a hardcoded activity, which could only be changed by rebuilding and
     * redeploying - on a host with no remote access, that is not a setting anybody can
     * actually use. {@link PresenceService} owns the default now, so there is one answer
     * to "what does it show when nothing is configured".
     *
     * <p>Dispatched to a worker rather than run here: reading the stored presence is a
     * database call and this method runs on the thread that delivered the ready event. If
     * the pool were somehow already saturated, the bot starts with no presence rather than
     * failing to start.
     */
    private void restorePresence(JDA jda) {
        try {
            client.getCommandExecutor().submit(() -> PresenceService.applyStored(jda));
        } catch (RejectedExecutionException rex) {
            BunnyLog.warning("[ClientReady] Could not restore the saved presence; the command pool was full.");
        }
    }

    // ------------------------------------------------------------------
    // Banner
    // ------------------------------------------------------------------

    private void printBanner(JDA jda) {
        String name = jda.getSelfUser().getName();
        double seconds = (System.currentTimeMillis() - client.getStartTime()) / 1000.0;

        int guilds = jda.getGuilds().size();
        long members = jda.getGuilds().stream().mapToLong(Guild::getMemberCount).sum();

        BunnyLog.raw(BunnyLog.CYAN, "\n" + renderAscii(name));
        rule();
        BunnyLog.raw(BunnyLog.GREEN, "  " + name + "  online  ·  v" + client.getVersion()
                + "  ·  ready in " + String.format("%.2fs", seconds));
        rule();

        row("Servers", String.format("%,d", guilds));
        row("Members", String.format("%,d", members));
        row("Commands", commandSummary());
        row("Components", ButtonRouter.count() + " buttons  ·  " + SelectRouter.count()
                + " menus  ·  " + ModalRouter.count() + " modals");
        row("Emojis", emojiSummary());
        row("Gateway", gatewayPing(jda));
        row("Memory", memory());
        row("Crash log", ErrorReporter.destination());
        rule();
    }

    /** Counts the branches too - a bot with 8 commands may expose far more entry points. */
    private String commandSummary() {
        int commands = 0;
        int actions = 0;

        for (BunnyCommand command : client.getCommandRegistry().getCommands().values()) {
            commands++;
            actions += command.getSubcommands().size();
            for (var group : command.getSubcommandGroups().values())
                actions += group.getSubcommands().size();
        }

        return actions == 0
                ? String.valueOf(commands)
                : commands + " top-level  ·  " + actions + " subcommands";
    }

    private static String emojiSummary() {
        int live = EmojiRegistry.liveCount();
        if (live == 0)
            return "using compiled-in IDs";

        int rebound = EmojiRegistry.reboundCount();
        return live + " available  ·  " + (rebound == 0 ? "all already matched" : rebound + " re-pointed");
    }

    /** JDA reports -1 until the first heartbeat completes, which can be after ready. */
    private static String gatewayPing(JDA jda) {
        long ping = jda.getGatewayPing();
        return ping < 0 ? "measuring..." : ping + " ms";
    }

    private static String memory() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long max = runtime.maxMemory() / (1024 * 1024);
        return used + " MB / " + max + " MB";
    }

    private static void row(String label, String value) {
        BunnyLog.raw(BunnyLog.CYAN, "  " + pad(label) + BunnyLog.RESET + value);
    }

    private static String pad(String label) {
        return label + " ".repeat(Math.max(1, LABEL_WIDTH - label.length()));
    }

    private static void rule() {
        BunnyLog.raw(BunnyLog.CYAN, "  " + "-".repeat(RULE_WIDTH));
    }

    private static String renderAscii(String text) {
        try {
            // Trailing blank lines from figlet push the banner apart; drop them.
            return Arrays.stream(FigletFont.convertOneLine(text).split("\n"))
                    .filter(line -> !line.isBlank())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse(text);
        } catch (IOException e) {
            return text;
        }
    }

    // ------------------------------------------------------------------
    // Optional dev-time regeneration
    // ------------------------------------------------------------------

    /**
     * Rewrites the generated constants in {@code AppDesign.java} from this application.
     *
     * <p>Only useful in a checkout: from a packaged jar the source file does not exist,
     * so this reports why rather than failing. Runtime rebinding has already happened by
     * this point either way, so skipping it costs nothing at runtime.
     */
    private void regenerateEmojiConstants(JDA jda) {
        if (!Files.exists(APP_DESIGN)) {
            BunnyLog.warning("[EmojiReload] setReloadEmojis(true) but " + APP_DESIGN
                    + " was not found. This only works from a source checkout. Skipped.");
            return;
        }

        BunnyLog.info("[EmojiReload] Regenerating emoji constants from this application...");

        try {
            int count = ApplicationEmojiExporter.export(jda, APP_DESIGN).join();
            BunnyLog.success("[EmojiReload] Wrote " + count + " constant(s) to AppDesign.java.");
            BunnyLog.warning("[EmojiReload] Rebuild to pick them up, then set setReloadEmojis(false).");
        } catch (Exception e) {
            BunnyLog.error("[EmojiReload] Regeneration failed; the source file was left untouched", e);
        }
    }
}
