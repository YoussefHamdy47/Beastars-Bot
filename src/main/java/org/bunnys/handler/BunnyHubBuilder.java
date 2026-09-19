package org.bunnys.handler;

import net.dv8tion.jda.api.requests.GatewayIntent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BunnyHubBuilder {
    // Default feature states
    private boolean logActions = true;
    private boolean autoLogin = true;
    private String tokenKey = "DISCORD_TOKEN";
    private boolean reloadEmojis = false;

    private String eventPackage = null;
    private String commandPackage = null;
    private String buttonPackage = null;
    private String modalPackage = null;
    private String selectPackage = null;

    /** MongoDB connection details. The database name is not this bot's to assume. */
    private String databaseName = "GBF";
    private String mongoUriKey = "MongoURI";

    /**
     * Command worker count and queue depth.
     *
     * <p>Commands are I/O-bound - Mongo, Discord REST, and upstream HTTP for manga and
     * wiki - so workers spend almost all their time blocked and can comfortably
     * outnumber cores. The default is sized for a small host serving one busy guild.
     */
    private int commandPoolSize = 24;
    private int commandQueueCapacity = 100;

    // Explicitly empty by default
    private final List<GatewayIntent> intents = new ArrayList<>();
    private final List<String> developerIds = new ArrayList<>();

    /** See {@link #setDeveloperCredit(String, String)}. Null means no credit is shown. */
    private String developerName;
    private String developerUrl;
    private final List<String> testServerIds = new ArrayList<>();

    /**
     * Rewrites the generated emoji block in {@code AppDesign.java} from the running
     * application, on the next boot.
     *
     * <p>Only needed when emoji are <b>added or renamed</b> in the Developer Portal -
     * changed IDs are handled automatically at runtime by
     * {@link org.bunnys.utils.EmojiRegistry}, so switching between the test and
     * production bots needs nothing here.
     *
     * <p>Development-only: it edits a source file, so it does nothing when running from
     * a packaged jar. Turn it on, boot once, commit the regenerated constants, turn it
     * back off.
     */
    public BunnyHubBuilder setReloadEmojis(boolean reloadEmojis) {
        this.reloadEmojis = reloadEmojis;
        return this;
    }

    public BunnyHubBuilder setLogActions(boolean logActions) {
        this.logActions = logActions;
        return this;
    }

    public BunnyHubBuilder setAutoLogin(boolean autoLogin) {
        this.autoLogin = autoLogin;
        return this;
    }

    public BunnyHubBuilder setTokenKey(String tokenKey) {
        this.tokenKey = tokenKey;
        return this;
    }

    public BunnyHubBuilder addIntents(GatewayIntent... gatewayIntents) {
        this.intents.addAll(Arrays.asList(gatewayIntents));
        return this;
    }

    public BunnyHubBuilder setEventPackage(String packageName) {
        this.eventPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setCommandPackage(String packageName) {
        this.commandPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setButtonPackage(String packageName) {
        this.buttonPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setModalPackage(String packageName) {
        this.modalPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setSelectPackage(String packageName) {
        this.selectPackage = packageName;
        return this;
    }

    /**
     * The MongoDB database name.
     *
     * <p>Defaults to {@code GBF}, which is only correct for the original cluster. Anyone
     * pointing this bot at their own MongoDB will have their own database name, and it
     * should not require editing the handler to say so.
     */
    public BunnyHubBuilder setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
        return this;
    }

    /** The environment key holding the connection string. Defaults to {@code MongoURI}. */
    public BunnyHubBuilder setMongoUriKey(String mongoUriKey) {
        this.mongoUriKey = mongoUriKey;
        return this;
    }

    /**
     * How many commands may run at once, and how many may wait.
     *
     * <p>The worker count is the real throughput ceiling: every slash command, mention,
     * button, select, modal and autocomplete runs on this pool. Raise it if commands
     * start being rejected under load - the router logs "rejected under load" when that
     * happens - and remember the Mongo connection pool is sized from this number, so the
     * two stay in step automatically.
     *
     * <p>The queue is deliberately shallow. A deep queue does not add capacity, it only
     * converts a fast rejection into a long wait for a user who has already given up.
     *
     * @param workers        concurrent commands; must be at least one
     * @param queueCapacity  how many may wait before new invocations are refused
     */
    public BunnyHubBuilder setCommandPool(int workers, int queueCapacity) {
        if (workers < 1)
            throw new IllegalArgumentException("Command pool needs at least one worker.");
        if (queueCapacity < 1)
            throw new IllegalArgumentException("Command queue needs at least one slot.");

        this.commandPoolSize = workers;
        this.commandQueueCapacity = queueCapacity;
        return this;
    }

    public BunnyHubBuilder addDeveloperIds(String... ids) {
        this.developerIds.addAll(Arrays.asList(ids));
        return this;
    }

    /**
     * Who built the bot, shown on {@code /uptime}.
     *
     * <p>Configuration rather than a string buried in a feature, for two reasons. It
     * belongs to the deployment, not to the uptime command - and whoever runs this
     * should be able to change or drop it by editing one line here, instead of going
     * looking for it. Leave it unset and the credit simply does not render.
     *
     * <p>The Discord profile link comes from the first entry in {@code addDeveloperIds}.
     *
     * @param name the name to credit
     * @param url  somewhere to find them - a repository, a portfolio - or null
     */
    public BunnyHubBuilder setDeveloperCredit(String name, String url) {
        this.developerName = name;
        this.developerUrl = url;
        return this;
    }

    public BunnyHubBuilder addTestServerIds(String... ids) {
        this.testServerIds.addAll(Arrays.asList(ids));
        return this;
    }

    // Getters so BunnyHub can read the configuration safely
    public boolean isReloadEmojis() {
        return reloadEmojis;
    }

    public boolean isLogActions() {
        return logActions;
    }

    public boolean isAutoLogin() {
        return autoLogin;
    }

    public String getTokenKey() {
        return tokenKey;
    }

    public List<GatewayIntent> getIntents() {
        return intents;
    }

    public String getEventPackage() {
        return eventPackage;
    }

    public List<String> getDeveloperIds() {
        return developerIds;
    }

    public String getDeveloperName() {
        return developerName;
    }

    public String getDeveloperUrl() {
        return developerUrl;
    }

    public List<String> getTestServerIds() {
        return testServerIds;
    }

    public String getCommandPackage() {
        return commandPackage;
    }

    public String getButtonPackage() {
        return buttonPackage;
    }

    public String getModalPackage() {
        return modalPackage;
    }

    public String getSelectPackage() {
        return selectPackage;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getMongoUriKey() {
        return mongoUriKey;
    }

    public int getCommandPoolSize() {
        return commandPoolSize;
    }

    public int getCommandQueueCapacity() {
        return commandQueueCapacity;
    }

    /**
     * Builds and returns the BunnyHub instance based on this configuration.
     */
    public BunnyHub build() {
        return new BunnyHub(this);
    }
}
