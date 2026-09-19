package org.bunnys.handler;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.handler.commands.CommandLoader;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.database.MongoManager;
import org.bunnys.handler.events.EventLoader;
import org.bunnys.handler.router.buttons.ButtonRouter;
import org.bunnys.handler.router.modals.ModalRouter;
import org.bunnys.handler.router.selects.SelectRouter;
import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;

import java.util.EnumSet;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicInteger;

public class BunnyHub {

    /**
     * Must match {@code <version>} in pom.xml.
     *
     * <p>Shown on the ready banner and the /uptime card, so a stale value here is a
     * value users are told. Kept as a plain constant rather than read from the jar
     * manifest because the manifest is absent when running from an IDE, and a version
     * that disappears outside a packaged build is worse than one edited by hand.
     */
    private static final String VERSION = "2.0";

    private JDA jda;
    private final BunnyHubBuilder config;
    private final long startTime;
    private volatile boolean isRunning = true;
    private final CommandRegistry commandRegistry;
    private final MongoManager mongoManager;

    /**
     * Idle workers are reclaimed after this, so a quiet bot costs no threads.
     *
     * <p>Must be non-zero: {@code allowCoreThreadTimeOut} below refuses a zero keep-alive.
     */
    private static final long COMMAND_POOL_KEEP_ALIVE_SECONDS = 60L;

    /** Mongo connections the bot needs beyond the command workers: JDA callbacks, crash reporting. */
    private static final int MONGO_POOL_HEADROOM = 8;

    /** Cumulative rejections, for the health report. */
    private final LongAdder rejectedCommands = new LongAdder();

    private final ThreadPoolExecutor commandExecutor;

    BunnyHub(BunnyHubBuilder config) {
        this.config = config;
        this.startTime = System.currentTimeMillis();
        this.commandRegistry = new CommandRegistry(this, config.getDeveloperIds(), config.getTestServerIds());

        BunnyLog.setLogActions(config.isLogActions());
        verifyEnvironment(config);

        int workers = config.getCommandPoolSize();

        /*
         * Core size equals max size, deliberately.
         *
         * ThreadPoolExecutor only grows past the core size once the queue is FULL. With
         * the previous core=4, max=16, queue=200 that meant four concurrent commands
         * until two hundred were already waiting - the other twelve threads were
         * unreachable in practice, and the "max" was decoration. Four workers is not a
         * lot when a single /manga page can hold one for seconds on an upstream fetch.
         *
         * Setting core = max gives the full width immediately, and
         * allowCoreThreadTimeOut hands the threads back when the bot goes quiet, so the
         * idle cost is the same as a small pool.
         */
        this.commandExecutor = new ThreadPoolExecutor(
                workers,
                workers,
                COMMAND_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.getCommandQueueCapacity()),
                new NamedThreadFactory("BunnyCommand-Worker"),
                // Bounded on purpose: a traffic spike must degrade (reject + tell the
                // user) rather than queue unbounded work or spawn unbounded threads.
                // Counted as well as thrown: each rejection was logged and forgotten, so
                // there was no way to ask whether the pool is occasionally tight or
                // permanently undersized - which is the only question that matters when
                // deciding whether to widen it.
                (task, executor) -> {
                    rejectedCommands.increment();
                    throw new RejectedExecutionException(
                            "Command pool saturated: " + workers + " workers, queue full.");
                });

        this.commandExecutor.allowCoreThreadTimeOut(true);

        String mongoURI = TokenLoader.require(config.getMongoUriKey());

        // Sized from the worker count rather than independently: fewer connections than
        // workers means workers queueing for a connection, which is the same stall as a
        // slow query but harder to see.
        this.mongoManager = new MongoManager(
                mongoURI, config.getDatabaseName(), workers + MONGO_POOL_HEADROOM);

        DB.init(this.mongoManager);

        if (!this.mongoManager.isConnected()) {
            throw new IllegalStateException("Aborting core startup loop: Database state offline.");
        }

        // Idempotent: creates the capped audit collection on first boot, verifies it
        // afterwards. Runs here rather than lazily so a misconfigured audit store is
        // reported at startup instead of on the first admin action.
        AuditService.initialise();

        if (config.getDeveloperIds().isEmpty())
            BunnyLog.warning("No Developer IDs provided.");

        if (config.getTestServerIds().isEmpty())
            BunnyLog.warning("No Test Server IDs provided.");

        registerShutdownHook();

        if (config.isAutoLogin()) {
            login();
            startConsoleListener();
        }
    }

    public void login() {
        if (this.jda != null)
            return;

        try {
            String token = TokenLoader.getToken(config.getTokenKey());
            verifyIntents();

            // Not an oversight to be warned about: commands arrive as slash interactions
            // or as @mentions, and Discord supplies content for mentions without the
            // privileged intent. verifyIntents() above covers the case that genuinely
            // does break things, so this warning is pure startup noise.
            Message.suppressContentIntentWarning();

            /*
             * Nothing this bot sends may ever ping @everyone, @here, or a role.
             *
             * JDA allows every mention by default, and "we never put user text in message
             * content" is a convention rather than a guarantee - replyContent takes an
             * arbitrary string, and the next person to call it will not be thinking about
             * this. A global floor makes the guarantee structural: even a message built
             * entirely from attacker-chosen text cannot mass-ping a server.
             *
             * USER stays allowed because it is load-bearing: the audit warning pings the
             * admin whose action could not be logged, and a notice nobody sees is not a
             * notice. Embeds never ping regardless of this setting, so role mentions in
             * leaderboards and admin panels still render exactly as before.
             */
            MessageRequest.setDefaultMentions(EnumSet.of(Message.MentionType.USER));

            JDABuilder jdaBuilder = JDABuilder.createLight(token, config.getIntents());

            /*
             * createLight sets MemberCachePolicy.NONE and disables chunking, so the
             * GUILD_MEMBERS intent on its own changes nothing: guild.isLoaded() stays
             * false and every member-dependent leaderboard rule silently no-ops. Both
             * have to be switched back on for the intent to mean anything.
             *
             * Gated on the intent actually being requested - asking Discord to chunk
             * without it gets the request ignored and leaves the guild never marked
             * loaded, which looks identical to the bug this fixes.
             *
             * ALL rather than ONLINE: the leaderboard resolves arbitrary stored user ids,
             * and an offline member is still a member. ONLINE would also need the
             * presences intent. The cost is real on constrained hardware - roughly the
             * member count times a few hundred bytes - so it is paid only when the
             * features that need it are actually reachable.
             */
            if (config.getIntents().contains(GatewayIntent.GUILD_MEMBERS)) {
                jdaBuilder.setMemberCachePolicy(MemberCachePolicy.ALL);
                jdaBuilder.setChunkingFilter(ChunkingFilter.ALL);
            }

            if (config.getButtonPackage() != null)
                ButtonRouter.loadButtons(config.getButtonPackage());

            if (config.getModalPackage() != null)
                ModalRouter.loadModals(config.getModalPackage());

            if (config.getSelectPackage() != null)
                SelectRouter.loadSelects(config.getSelectPackage());

            if (config.getEventPackage() != null)
                EventLoader.loadEvents(this, jdaBuilder, config.getEventPackage());

            if (config.getCommandPackage() != null)
                CommandLoader.loadCommands(this, this.commandRegistry, config.getCommandPackage());

            this.jda = jdaBuilder.build();


        } catch (Exception e) {
            // Rethrown rather than logged and shrugged off. Swallowing it left the
            // process alive with a null JDA: no gateway, no commands, no ready event, and
            // a console that had already printed one line about it and moved on. The
            // database constructor above refuses to leave a zombie behind and so does
            // this - a bot that cannot log in has nothing left to do.
            BunnyLog.error("Critical failure during BunnyHub login process", e);
            throw new IllegalStateException("Failed to log in to Discord", e);
        }
    }

    /**
     * Checks the environment before anything tries to use it.
     *
     * <p>Reports <em>every</em> missing required key at once. Discovering them one restart
     * at a time is the usual experience of setting up a project from a fresh clone, and it
     * is entirely avoidable - the list is known up front.
     *
     * <p>Optional keys only produce a note. A fork with no Google Drive folders and no
     * Imgur album is a perfectly valid deployment; it simply has fewer sources, and the
     * features concerned say so themselves rather than failing at startup.
     */
    private void verifyEnvironment(BunnyHubBuilder config) {
        var missing = TokenLoader.missing(config.getTokenKey(), config.getMongoUriKey());

        if (!missing.isEmpty()) {
            BunnyLog.error("[BunnyHub] Cannot start. These settings are missing from .env:");
            missing.forEach(key -> BunnyLog.error("    " + key));
            BunnyLog.error("[BunnyHub] Copy .env.example to .env and fill it in.");

            throw new IllegalStateException(
                    "Missing required environment settings: " + String.join(", ", missing));
        }

        var optional = TokenLoader.missing("DEVELOPER_CHANNEL_ID", "DEVELOPERS",
                "MANGADEX_BEASTARS", "IMGUR_DEFAULT_ALBUM", "DriveKey", "ImgurKey");

        if (!optional.isEmpty())
            BunnyLog.warning("[BunnyHub] Optional settings not configured (features degrade cleanly): "
                    + String.join(", ", optional));
    }

    /**
     * Warns about intent gaps that would otherwise fail silently.
     *
     * <p>Without {@code GUILD_MESSAGES} the gateway never sends MESSAGE_CREATE, so
     * {@code @mention} commands do nothing at all: no error, no log line, nothing in
     * Discord. Slash commands keep working, because interactions arrive over HTTP and
     * need no intent - so the bot looks healthy while half its command surface is dead.
     * That combination is nearly impossible to diagnose from behaviour alone, which is
     * why it is worth one explicit check at startup.
     */
    private void verifyIntents() {
        // Only GUILD_MESSAGES is reported. It is not privileged, so its absence is
        // always a mistake somebody can fix immediately.
        //
        // GUILD_MEMBERS is deliberately silent: it is privileged and may legitimately be
        // pending Discord's approval for weeks. A warning nobody can act on, printed on
        // every restart, trains people to ignore the warnings that matter. The features
        // that need it say so at the point of use instead - ephemerally, to the person
        // who just tried to use one.
        if (config.getIntents().contains(GatewayIntent.GUILD_MESSAGES))
            return;

        BunnyLog.warning("[BunnyHub] GUILD_MESSAGES intent is not enabled.");
        BunnyLog.warning("[BunnyHub] @mention commands will NOT work. Slash commands are unaffected.");
        BunnyLog.warning("[BunnyHub] Add GatewayIntent.GUILD_MESSAGES in Main to fix. It is not privileged.");
    }

    public void shutdown() {
        performShutdown(false);
        System.exit(0);
    }

    private synchronized void performShutdown(boolean emergency) {
        if (!isRunning)
            return;
        isRunning = false;

        String mode = emergency ? "emergency fallback" : "graceful";
        BunnyLog.info("[BunnyHub] Initiating " + mode + " shutdown...");

        try {
            int clearedCmds = this.commandRegistry.clearCommands();
            BunnyLog.info("[CommandRegistry] Cleared " + clearedCmds + " commands.");

            int clearedEvents = EventLoader.clearEvents(this.jda);
            BunnyLog.info("[EventRegistry] Unregistered " + clearedEvents + " active events.");

            // Drain the command executor BEFORE shutting down JDA, since in-flight
            // command tasks still need a live JDA instance to send their replies.
            shutdownCommandExecutor(emergency);

            if (this.jda != null) {
                if (emergency)
                    this.jda.shutdownNow();
                else
                    this.jda.shutdown();
                BunnyLog.info("[BunnyHub] ShardManager shutdown complete.");
            }

            if (this.mongoManager != null)
                this.mongoManager.disconnect();

            BunnyLog.info("[BunnyHub] Offline");

        } catch (Exception e) {
            BunnyLog.error("[ERROR] Error during " + mode + " shutdown: " + e.getMessage());
        } finally {
            System.out.flush();
            System.err.flush();
        }
    }

    private void shutdownCommandExecutor(boolean emergency) {
        if (emergency) {
            int abandoned = commandExecutor.shutdownNow().size();
            BunnyLog.info("[CommandExecutor] Emergency stop. Abandoned " + abandoned + " queued command tasks.");
            return;
        }

        commandExecutor.shutdown();
        try {
            if (!commandExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                int abandoned = commandExecutor.shutdownNow().size();
                BunnyLog.warning("[CommandExecutor] Did not drain within 10s. Force-stopped, abandoned "
                        + abandoned + " queued command tasks.");
            } else {
                BunnyLog.info("[CommandExecutor] Drained cleanly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            commandExecutor.shutdownNow();
            BunnyLog.warning("[CommandExecutor] Interrupted while draining. Force-stopped.");
        }
    }

    /**
     * Watches stdin for {@code stop} / {@code exit}.
     *
     * <h2>Why the EOF check is not optional</h2>
     * {@code hasNextLine()} blocks while a terminal is attached, but returns
     * <em>immediately and forever</em> once the stream is exhausted - which is exactly
     * what stdin looks like under systemd, Docker, or {@code nohup ... &}. The previous
     * loop had no exit, so every headless deployment span one core at roughly twenty-four
     * million iterations a second for the entire life of the process: no error, no log
     * line, a bot that works perfectly and permanently burns a quarter of a Pi's CPU.
     *
     * <p>An exhausted stdin means there is no console to listen to, so the thread's job
     * is over and it ends. Shutdown still works through the signal handler.
     */
    private void startConsoleListener() {
        Thread consoleThread = new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (isRunning) {
                    if (!scanner.hasNextLine()) {
                        BunnyLog.info("[BunnyHub] No console attached; stop/exit unavailable. "
                                + "Use a signal to shut down.");
                        return;
                    }

                    String line = scanner.nextLine().trim().toLowerCase();
                    if (line.equals("stop") || line.equals("exit"))
                        shutdown();
                }
            }
        }, "BunnyConsoleListener");

        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> performShutdown(true), "BunnyEmergencyShutdownHook"));
    }

    public JDA getJDA() {
        return this.jda;
    }

    public long getStartTime() {
        return this.startTime;
    }

    public String getVersion() {
        return VERSION;
    }

    /** Who to credit on /uptime, or null when the deployment sets no credit. */
    public String getDeveloperName() {
        return this.config.getDeveloperName();
    }

    /** Where to find them, or null. */
    public String getDeveloperUrl() {
        return this.config.getDeveloperUrl();
    }

    /** The first configured developer id, used for the profile link. Null when none is set. */
    public String getPrimaryDeveloperId() {
        var ids = this.config.getDeveloperIds();
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** See {@link BunnyHubBuilder#setReloadEmojis(boolean)}. */
    public boolean isReloadEmojis() {
        return this.config.isReloadEmojis();
    }

    public CommandRegistry getCommandRegistry() {
        return this.commandRegistry;
    }

    public MongoManager getMongoManager() {
        return this.mongoManager;
    }

    /**
     * Bounded executor for slash/message command execution and autocomplete.
     * JDA event threads must never run command logic directly - see BunnyCommand
     * for why. Submitting here can throw RejectedExecutionException if the pool
     * and its queue are both saturated; callers must handle that explicitly
     * rather than letting it propagate as an unhandled exception.
     */
    public ExecutorService getCommandExecutor() {
        return this.commandExecutor;
    }

    /**
     * A point-in-time view of the command pool.
     *
     * <p>The one report that says whether the worker count is right. Sustained
     * { active == workers} with a non-empty queue means commands are waiting on each
     * other; any rejections at all mean users were turned away.
     */
    public PoolReport poolReport() {
        return new PoolReport(
                commandExecutor.getActiveCount(),
                commandExecutor.getPoolSize(),
                commandExecutor.getMaximumPoolSize(),
                commandExecutor.getLargestPoolSize(),
                commandExecutor.getQueue().size(),
                commandExecutor.getQueue().size() + commandExecutor.getQueue().remainingCapacity(),
                commandExecutor.getCompletedTaskCount(),
                rejectedCommands.sum());
    }

    /**
     *  largest  the high-water mark of threads ever alive; below { workers}
     *                 means the pool has never been fully stretched
     *  rejected commands refused outright because the queue was also full
     */
    public record PoolReport(int active, int alive, int workers, int largest,
                             int queued, int queueCapacity,
                             long completed, long rejected) {}

    public static BunnyHubBuilder create() {
        return new BunnyHubBuilder();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            // Not daemon: we want performShutdown()'s drain logic to be meaningful.
            // If these were daemon threads, the JVM could exit mid-command.
            t.setDaemon(false);
            return t;
        }
    }
}