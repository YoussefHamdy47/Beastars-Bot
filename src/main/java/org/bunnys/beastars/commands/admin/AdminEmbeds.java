package org.bunnys.beastars.commands.admin;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.admin.AdminComponents.CommandScope;
import org.bunnys.beastars.commands.leg.LegConfigService.RoleCategory;
import org.bunnys.beastars.commands.ooc.OocService;
import org.bunnys.beastars.database.LegConfigData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.metrics.CacheRegistry;
import org.bunnys.utils.AppDesign;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every embed the admin surface can produce.
 *
 * <p>Same house style as the rest of the bot: plain-text titles, field names and
 * footers; Beastars emoji leading descriptions and field values; {@code DEFAULT}
 * colour except on failures.
 */
public final class AdminEmbeds {

    /** Discord's hard limit for a single embed field value. */
    private static final int FIELD_LIMIT = 1024;

    private AdminEmbeds() {}

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    /**
     * A successful administrative action.
     *
     * @param emoji the action's own Beastars emoji - {@link BeastarsEmoji#SUCCESS} for a
     *              plain success, or a flavoured one for bans, resets, nukes and the
     *              like. Required rather than defaulted, so a new admin action has to
     *              make a deliberate choice.
     */
    public static MessageEmbed success(String title, String emoji, String description) {
        return build(title, emoji, description, AppDesign.ColorCodes.DEFAULT);
    }

    /** Bad input or a rejected action. Not a crash. */
    public static MessageEmbed error(String title, String description) {
        return build(title, BeastarsEmoji.FAILURE, description, AppDesign.ColorCodes.ERROR_RED);
    }

    /** The caller is not allowed to do this. */
    public static MessageEmbed denied(String description) {
        return build("Permission Denied", BeastarsEmoji.DENIED, description, AppDesign.ColorCodes.ERROR_RED);
    }

    /** Something in the plumbing broke. */
    public static MessageEmbed systemError() {
        return build("System Error", BeastarsEmoji.FAILURE,
                "An unexpected error occurred. The incident has been logged.",
                AppDesign.ColorCodes.ERROR_RED);
    }

    private static MessageEmbed build(String title, String emoji, String description, Color color) {
        return new EmbedBuilder()
                .setTitle(title)
                .setDescription(emoji + " " + description)
                .setColor(color)
                .setTimestamp(Instant.now())
                .build();
    }

    // ------------------------------------------------------------------
    // Dashboards
    // ------------------------------------------------------------------

    public static MessageEmbed dashboard(LegConfigData config) {
        return new EmbedBuilder()
                .setTitle("Beastars Economy Administration")
                .setDescription(BeastarsEmoji.PANEL
                        + " Manage the flesh economy settings, roles, and user data."
                        + "\n*All destructive actions are permanently logged.*")
                .addField("Server Status", config.isEnabled()
                        ? BeastarsEmoji.ENABLED + " **Enabled**"
                        : BeastarsEmoji.DISABLED + " **Disabled**", true)
                .addField("New Member Wait",
                        BeastarsEmoji.TIMER + " " + config.getWaitPeriodHours() + " Hours", true)
                .addBlankField(true)
                .addField("Allowed Roles",
                        roleList(config.getAllowedRoles(), BeastarsEmoji.ROLE_ALLOWED, "*Everyone*"), false)
                .addField("Banned Roles",
                        roleList(config.getBannedRoles(), BeastarsEmoji.ROLE_BANNED, "*None*"), true)
                .addField("Bypass Roles",
                        roleList(config.getBypassWaitRoles(), BeastarsEmoji.ROLE_BYPASS, "*None*"), true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Actions are audited")
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Confirms a Bot Admin role change.
     *
     * @param roleIds the list <b>as stored</b>, returned by the write itself - never a
     *                re-read and never what the admin selected, so this embed cannot
     *                disagree with the database.
     */
    public static MessageEmbed adminRolesUpdated(List<String> roleIds) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Bot Admin Roles Updated")
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now());

        if (roleIds.isEmpty())
            embed.setDescription(BeastarsEmoji.EMPTY
                    + " The Bot Admin list is now empty.\n"
                    + "Only native Server Administrators can manage the bot.");
        else
            embed.setDescription(BeastarsEmoji.ROLES
                    + " **" + roleIds.size() + "** role(s) now have Bot Admin privileges:\n\n"
                    + String.join("\n", roleIds.stream().map(id -> "<@&" + id + ">").toList()));

        return embed.build();
    }

    /**
     * Confirms an economy role-category change, echoing all three categories.
     *
     * <p>Built from the config the write returned, so the cross-category cleanup is
     * visible as fact rather than promised in prose.
     */
    public static MessageEmbed roleCategoryUpdated(RoleCategory category, LegConfigData config) {
        return new EmbedBuilder()
                .setTitle(category.label() + " Updated")
                .setDescription(BeastarsEmoji.ROLES
                        + " Saved. Roles are exclusive across categories, so any overlaps were removed.")
                .addField("Allowed Roles",
                        roleList(config.getAllowedRoles(), BeastarsEmoji.ROLE_ALLOWED, "*Everyone*"), false)
                .addField("Banned Roles",
                        roleList(config.getBannedRoles(), BeastarsEmoji.ROLE_BANNED, "*None*"), true)
                .addField("Bypass Roles",
                        roleList(config.getBypassWaitRoles(), BeastarsEmoji.ROLE_BYPASS, "*None*"), true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now())
                .build();
    }

    // ------------------------------------------------------------------
    // Master hub pages
    // ------------------------------------------------------------------

    public static MessageEmbed hub(String guildName) {
        return new EmbedBuilder()
                .setTitle("Administration")
                .setDescription(BeastarsEmoji.PANEL + " Everything configurable for **" + guildName + "**."
                        + "\nPick a section below.")
                .addField("Access Control", BeastarsEmoji.DENIED
                        + " Enable or disable commands, and lock them to roles or channels.", false)
                .addField("Manga Settings", BeastarsEmoji.PAGE
                        + " Where the manga reader may be used.", false)
                .addField("Economy", BeastarsEmoji.SACRIFICE
                        + " The Leg economy: toggles, roles, wait time, user moderation.", false)
                .addField("Logging Setup", BeastarsEmoji.EDIT
                        + " Choose where the audit trail is posted.", false)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Actions are audited")
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * The unscoped Access Control page.
     *
     * <p>Grouped by feature rather than listed flat, so it is obvious which controls
     * govern the manga reader and which govern the economy. The Manga and Economy pages
     * each edit only their own group; this page is the one place that edits anything.
     *
     * @param commands every registered command name, so the page shows what can be locked.
     */
    public static MessageEmbed accessOverview(AccessService.GuildAccess access, List<String> commands) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Access Control")
                .setDescription(BeastarsEmoji.PANEL
                        + " Every command in the bot, grouped by feature."
                        + "\n*Server managers always bypass these rules, so a misconfiguration"
                        + " can never lock you out of fixing it.*")
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Access Control")
                .setTimestamp(Instant.now());

        List<String> manga = CommandScope.MANGA.filter(commands);
        List<String> economy = CommandScope.ECONOMY.filter(commands);

        List<String> other = commands.stream()
                .filter(name -> !manga.contains(name) && !economy.contains(name))
                .toList();

        embed.addField(CommandScope.MANGA.label(), stateBlock(access, manga), false);
        embed.addField(CommandScope.ECONOMY.label(), stateBlock(access, economy), false);

        if (!other.isEmpty())
            embed.addField("Everything Else", stateBlock(access, other), false);

        return embed.build();
    }

    /** One line per command: enabled/disabled, plus any rules in force. */
    private static String stateBlock(AccessService.GuildAccess access, List<String> commands) {
        if (commands.isEmpty())
            return BeastarsEmoji.EMPTY + " *No commands in this group.*";

        StringBuilder block = new StringBuilder();
        for (String command : commands) {
            AccessService.CommandRules rules = access.rulesFor(command);

            block.append(access.isDisabled(command) ? BeastarsEmoji.DISABLED : BeastarsEmoji.ENABLED)
                    .append(" `/").append(command).append("`");

            if (!rules.isEmpty())
                block.append(": ").append(describe(rules));

            block.append("\n");
        }

        return trim(block.toString());
    }

    public static MessageEmbed mangaOverview(AccessService.GuildAccess access, List<String> mangaCommands) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Manga Settings")
                .setDescription(BeastarsEmoji.PAGE
                        + " The manga reader has no stored options of its own. Chapter data comes"
                        + " straight from the source. What you can configure is where it may be used.")
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Manga")
                .setTimestamp(Instant.now());

        for (String command : mangaCommands) {
            AccessService.CommandRules rules = access.rulesFor(command);
            String state = access.isDisabled(command)
                    ? BeastarsEmoji.DISABLED + " Disabled"
                    : BeastarsEmoji.ENABLED + " Enabled";

            embed.addField("/" + command, state + (rules.isEmpty() ? "" : "\n" + describe(rules)), false);
        }

        return embed.build();
    }

    /**
     * The OOC album page.
     *
     * <p>Reports the album link as configured or defaulted rather than printing the URL:
     * the panel is often open in a shared channel, and the link is one command away for
     * anyone who actually needs it.
     */
    public static MessageEmbed oocSettings(OocService.Settings settings) {
        int cooldown = settings.rerollCooldownSeconds();
        boolean usingDefaultAlbum = settings.usingDefaultAlbum();

        return new EmbedBuilder()
                .setTitle("OOC Album")
                .setDescription(BeastarsEmoji.ALBUM
                        + " `/ooc` posts a random image from this server's album, with a button to"
                        + " draw another. Members can also just say `@BeastarsBot ooc`.")
                .addField("Album", settings.albumLink() == null
                        ? BeastarsEmoji.DISABLED + " *Not configured. Set one with `/ooc setlink`.*"
                        : usingDefaultAlbum
                        ? BeastarsEmoji.ENABLED + " *Using the bot's shared default album.*"
                        : BeastarsEmoji.ENABLED + " *Configured for this server.*", false)
                .addField("Reroll Cooldown", cooldown <= 0
                        ? BeastarsEmoji.DISABLED + " *No wait. Members may reroll as fast as they like.*"
                        : BeastarsEmoji.TIMER + " **" + cooldown + "** second"
                        + (cooldown == 1 ? "" : "s") + " per member between presses.", false)
                .addField("Scope", BeastarsEmoji.PANEL
                        + " The cooldown is per member, not per channel, so one person clicking"
                        + " repeatedly never blocks anybody else.", false)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  OOC Album")
                .setTimestamp(Instant.now())
                .build();
    }

    public static MessageEmbed loggingOverview(String channelId) {
        return new EmbedBuilder()
                .setTitle("Logging Setup")
                .setDescription(BeastarsEmoji.EDIT
                        + " Every administrative action is written to the database regardless of this"
                        + " setting. A log channel adds a readable copy in Discord.")
                .addField("Audit Channel", channelId == null
                        ? BeastarsEmoji.DISABLED + " *Not configured. Database only.*"
                        : BeastarsEmoji.ENABLED + " <#" + channelId + ">", false)
                .addField("Retention", BeastarsEmoji.PANEL
                        + " Stored in a capped collection, so the audit trail can never outgrow"
                        + " its size budget. Oldest entries are evicted first.", false)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Logging")
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Where this guild's crash reports go.
     *
     * <p>Names the developer copy explicitly. A guild admin who configures a channel here
     * should not be left believing they have taken responsibility for crash reporting, or
     * that clearing it makes failures go unseen.
     */
    public static MessageEmbed errorLogOverview(String channelId) {
        return new EmbedBuilder()
                .setTitle("Error Reporting Setup")
                .setDescription(BeastarsEmoji.FAILURE
                        + " Unhandled errors are always printed to the bot's console and sent to the"
                        + " developers. A channel here adds a copy for this server's crashes only.")
                .addField("Error Channel", channelId == null
                        ? BeastarsEmoji.DISABLED + " *Not configured. Developers only.*"
                        : BeastarsEmoji.ENABLED + " <#" + channelId + ">", false)
                .addField("Scope", BeastarsEmoji.SERVER
                        + " Only crashes that happen in this server are posted here."
                        + " Other servers' failures are never published to it.", false)
                .addField("Flood Control", BeastarsEmoji.TIMER
                        + " Identical errors are reported at most once a minute, so a crash loop"
                        + " cannot bury the channel it is being reported in.", false)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Error Reporting")
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Who appears on the public ranking.
     *
     * <p>States the intent dependency out loud. Two of these three rules need the member
     * list, and an admin who sets hidden roles and sees them ignored deserves to know why
     * rather than assuming the bot is broken.
     */
    /**
     * Who appears on the public ranking, and whether those rules are actually running.
     *
     * @param membersVisible live {@code guild.isLoaded()}, not a stored setting - two of
     *                       these three rules do nothing without it, and an admin who
     *                       configures a hidden role and sees no change deserves to be
     *                       told why on the page that took the setting
     */
    public static MessageEmbed leaderboardSettings(LegConfigData config, boolean membersVisible) {
        List<String> hidden = config.getLeaderboardHiddenRoles();
        boolean roleRulesInert = !membersVisible;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Leaderboard Visibility")
                .setDescription(BeastarsEmoji.APEX
                        + " These rules only change who is listed. Nobody's legs, rank or"
                        + " history are affected, and hiding someone is reversible.")
                .addField("Hidden Roles", hidden.isEmpty()
                        ? BeastarsEmoji.DISABLED + " *Nobody is hidden by role.*"
                        : BeastarsEmoji.ROLE_BANNED + " " + mentionRoles(hidden)
                                + (roleRulesInert ? "\n" + BeastarsEmoji.FAILURE + " **Not in effect.**" : ""), false)
                .addField("Banned Members", config.isHideBannedOnLeaderboard()
                        ? BeastarsEmoji.ENABLED + " Hidden"
                        : BeastarsEmoji.DISABLED + " Shown", true)
                .addField("Members Who Left", config.isHideDepartedOnLeaderboard()
                        ? BeastarsEmoji.ENABLED + " Hidden"
                                + (roleRulesInert ? "\n" + BeastarsEmoji.FAILURE + " **Not in effect.**" : "")
                        : BeastarsEmoji.DISABLED + " Shown", true);

        if (roleRulesInert)
            embed.addField("Why Two Rules Are Inert", BeastarsEmoji.FAILURE
                    + " I cannot see this server's full member list, so I cannot tell who holds"
                    + " which role or who has left. **Hidden Roles** and **Members Who Left** are"
                    + " being skipped; the **Banned Members** rule still works, because that is"
                    + " my own record rather than Discord's.\n\n"
                    + "Fix: enable the **Server Members Intent** in the Discord Developer Portal,"
                    + " then restart the bot with that intent requested.", false);
        else
            embed.addField("Status", BeastarsEmoji.ENABLED
                    + " All three rules are in effect.", false);

        return embed
                .setColor(roleRulesInert ? AppDesign.ColorCodes.ERROR_RED : AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Leaderboard")
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Everything worth knowing about how hard the bot is working.
     *
     * <p>Exists because the caches were already recording statistics that nothing ever
     * read. Sizing decisions - how many workers, how big a cache, how long an expiry -
     * were being made from reasoning alone; this is where the measurements live.
     */
    public static MessageEmbed health(BunnyHub.PoolReport pool, List<CacheRegistry.CacheReport> caches,
                                      long gatewayPing, boolean databaseUp) {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Bot Health")
                .setDescription(BeastarsEmoji.PANEL + " " + poolVerdict(pool))
                .addField("Command Pool", BeastarsEmoji.LATENCY
                        + " " + pool.active() + " of " + pool.workers() + " busy"
                        + "\nqueue " + pool.queued() + " / " + pool.queueCapacity()
                        + "\npeak " + pool.largest() + " workers", true)
                .addField("Throughput", BeastarsEmoji.SUCCESS
                        + " " + String.format("%,d", pool.completed()) + " completed"
                        + "\n" + (pool.rejected() == 0
                                ? "no rejections"
                                : "**" + String.format("%,d", pool.rejected()) + " rejected**"), true)
                .addField("Host", BeastarsEmoji.SERVER
                        + " " + usedMb + " / " + maxMb + " MB"
                        + "\ngateway " + (gatewayPing < 0 ? "measuring" : gatewayPing + " ms")
                        + "\ndatabase " + (databaseUp ? "up" : "**unreachable**"), true);

        embed.addField("Caches [" + caches.size() + "]", cacheTable(caches), false);

        return embed
                .setColor(pool.rejected() > 0 || !databaseUp
                        ? AppDesign.ColorCodes.ERROR_RED
                        : AppDesign.ColorCodes.DEFAULT)
                .setFooter("Caches appear once their feature is first used  •  Health")
                .setTimestamp(Instant.now())
                .build();
    }

    /** The headline: is the pool comfortable, tight, or turning people away. */
    private static String poolVerdict(BunnyHub.PoolReport pool) {
        if (pool.rejected() > 0)
            return "**Commands have been rejected.** The pool is undersized for the load it has seen"
                    + ". Raise the worker count with `setCommandPool`.";
        if (pool.largest() >= pool.workers() && pool.queued() > 0)
            return "Every worker is busy and commands are queueing. Comfortable for now, but this is"
                    + " the number to raise next.";
        if (pool.largest() >= pool.workers())
            return "The pool has been fully stretched at some point, without anything queueing.";

        return "The pool has never been fully stretched. Peak was " + pool.largest()
                + " of " + pool.workers() + " workers.";
    }

    /**
     * Caches as a fixed-width table, sorted worst hit rate first.
     *
     * <p>Worst-first because a healthy cache needs no attention; the interesting one is
     * whichever is doing the least good.
     */
    private static String cacheTable(List<CacheRegistry.CacheReport> caches) {
        List<CacheRegistry.CacheReport> sorted = new ArrayList<>(caches);
        sorted.sort(Comparator.comparingDouble(c -> c.hitPercent() < 0 ? 999 : c.hitPercent()));

        StringBuilder table = new StringBuilder("```\n");
        table.append(String.format("%-22s %7s %6s %8s%n", "cache", "entries", "hit%", "evicted"));

        for (CacheRegistry.CacheReport cache : sorted) {
            String rate = !cache.statsEnabled() ? "n/a"
                    : cache.hitPercent() < 0 ? "-"
                    : String.format("%.0f", cache.hitPercent());

            table.append(String.format("%-22s %7d %6s %8d%n",
                    cache.name().length() > 22 ? cache.name().substring(0, 22) : cache.name(),
                    cache.size(), rate, cache.evictions()));
        }

        table.append("```");

        String rendered = table.toString();
        return rendered.length() > MessageEmbed.VALUE_MAX_LENGTH
                ? rendered.substring(0, MessageEmbed.VALUE_MAX_LENGTH - 5) + "\n```"
                : rendered;
    }

    private static String mentionRoles(List<String> roleIds) {
        StringBuilder text = new StringBuilder();
        for (String id : roleIds) {
            if (!text.isEmpty())
                text.append(" ");
            text.append("<@&").append(id).append(">");
        }
        return text.toString();
    }

    /** Accompanies the uploaded audit file. */
    public static MessageEmbed exportReady(String rendered) {
        long lines = rendered.lines().count();

        return new EmbedBuilder()
                .setTitle("Audit Trail Export")
                .setDescription(BeastarsEmoji.EDIT
                        + " Attached as `audit_logs.txt`, built in memory and never written to disk.")
                .addField("Size", String.format("%,d characters across %,d lines",
                        rendered.length(), lines), true)
                .addField("Scope", "Newest " + AuditService.EXPORT_LIMIT + " entries for this server", true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Bunnys Admin Panel  •  Audit Trail")
                .setTimestamp(Instant.now())
                .build();
    }

    private static String describe(AccessService.CommandRules rules) {
        List<String> parts = new java.util.ArrayList<>();

        if (!rules.allowedRoles().isEmpty())
            parts.add("only roles " + mentions(rules.allowedRoles(), "&"));
        if (!rules.deniedRoles().isEmpty())
            parts.add("not roles " + mentions(rules.deniedRoles(), "&"));
        if (!rules.allowedChannels().isEmpty())
            parts.add("only in " + mentions(rules.allowedChannels(), "#"));
        if (!rules.deniedChannels().isEmpty())
            parts.add("not in " + mentions(rules.deniedChannels(), "#"));

        return String.join(", ", parts);
    }

    private static String mentions(List<String> ids, String sigil) {
        return String.join(" ", ids.stream()
                .map(id -> sigil.equals("#") ? "<#" + id + ">" : "<@&" + id + ">")
                .toList());
    }

    private static String inlineCode(List<String> values) {
        return String.join(" ", values.stream().map(v -> "`/" + v + "`").toList());
    }

    private static String trim(String value) {
        return value.length() > FIELD_LIMIT ? value.substring(0, FIELD_LIMIT - 4) + "..." : value;
    }

    /** Renders role ids as mentions, truncated to fit inside a field. */
    private static String roleList(List<String> roleIds, String emoji, String fallback) {
        if (roleIds == null || roleIds.isEmpty())
            return emoji + " " + fallback;

        String rendered = emoji + " "
                + String.join(", ", roleIds.stream().map(id -> "<@&" + id + ">").toList());

        return rendered.length() > FIELD_LIMIT ? rendered.substring(0, FIELD_LIMIT - 4) + "..." : rendered;
    }
}
