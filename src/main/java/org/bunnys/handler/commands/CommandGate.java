package org.bunnys.handler.commands;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel;
import org.bunnys.beastars.commands.admin.AccessService;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.AdminUtils;
import org.bunnys.utils.EmojiRegistry;
import org.bunnys.utils.SystemEmbeds;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Every pre-execution check, in one place, for both entry points.
 *
 * <p>These rules - developer-only, admin-only, DM, NSFW, permissions, cooldown - used to
 * live inline in {@code InteractionListener}. Adding mention routing would have meant a
 * second copy, and a second copy is how a permission check quietly stops matching its
 * twin. The gate runs against a {@link CommandContext}, so slash and mention are
 * governed by literally the same code.
 *
 * <p>Returns the denial embed to show, or null to proceed.
 */
public final class CommandGate {

    /**
     * Cooldown deadlines, keyed {@code command.subcommand:userId}.
     *
     * <p>Previously a nested {@code ConcurrentHashMap} that was swept by hand on every
     * invocation and emergency-cleared past 5000 entries - an eviction strategy that
     * dropped live cooldowns for everyone. Caffeine expires entries individually.
     */
    private static final Cache<String, Long> COOLDOWNS = CacheRegistry.register("gate.cooldowns", Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()
            .build());

    private CommandGate() {}

    /**
     * @param command    the resolved command
     * @param subcommand the resolved subcommand, or null
     * @return an embed to reply with, or null when the command may run
     */
    public static MessageEmbed check(CommandContext ctx, CommandRegistry registry,
                                     BunnyCommand command, BunnySubcommand subcommand) {
        // Publication fence for the rebound emoji constants; see EmojiRegistry.fence().
        // Must precede the executor submit so workers inherit the edge via the queue.
        EmojiRegistry.fence();

        boolean isDeveloper = registry.getDeveloperIds().contains(ctx.getUser().getId());
        if (command.isDeveloperBypass() && isDeveloper)
            return null;

        // 1. Developer gate
        boolean devOnly = (subcommand != null && subcommand.isDeveloperOnly()) || command.isDeveloperOnly();
        if (devOnly && !isDeveloper)
            return SystemEmbeds.denied("Access Denied",
                    "This command is restricted to the bot's developers.");

        // 2. Admin gate (native Administrator or a configured Bot Admin role)
        boolean adminOnly = (subcommand != null && subcommand.isAdminOnly()) || command.isAdminOnly();
        if (adminOnly && (ctx.getMember() == null || !AdminUtils.hasBotAdmin(ctx.getMember())))
            return SystemEmbeds.denied("Access Denied",
                    "You must be a Server Administrator, or hold an authorised Bot Admin role, to use this.");

        // 3. Guild-only gate
        if (!command.isDmEnabled() && !ctx.isFromGuild())
            return SystemEmbeds.denied("Server Only",
                    "`" + command.getName() + "` cannot be used in direct messages.");

        if (ctx.isFromGuild()) {
            // 4. Per-guild command locks (global toggle, channel rules, role rules).
            //    Runs before the permission checks so an explicitly disabled command
            //    reports as disabled rather than as a permission problem.
            AccessService.Verdict verdict = AccessService.check(
                    ctx.getGuild().getId(), command.getName(), ctx.getMember(),
                    ctx.getChannel() == null ? null : ctx.getChannel().getId());

            if (!verdict.allowed())
                return SystemEmbeds.denied("Command Unavailable", verdict.reason());

            // 5. Age-restricted channels
            boolean nsfw = (subcommand != null && subcommand.isNsfw()) || command.isNsfw();
            if (nsfw && !(ctx.getChannel() instanceof IAgeRestrictedChannel age && age.isNSFW()))
                return SystemEmbeds.denied("Age-Restricted",
                        "This command only works in age-restricted channels.");

            // 6. Caller permissions
            for (Permission permission : command.getUserPermissions())
                if (!ctx.getMember().hasPermission(permission))
                    return SystemEmbeds.denied("Missing Permission",
                            "You need the `" + permission.getName() + "` permission to use this.");

            // 7. Bot permissions
            for (Permission permission : command.getAppPermissions())
                if (!ctx.getGuild().getSelfMember().hasPermission(permission))
                    return SystemEmbeds.error("Missing Bot Permission",
                            "I am missing the `" + permission.getName() + "` permission here.");
        }

        // 8. Cooldown
        return checkCooldown(ctx, command, subcommand);
    }

    private static MessageEmbed checkCooldown(CommandContext ctx, BunnyCommand command, BunnySubcommand subcommand) {
        int seconds = (subcommand != null && subcommand.getCooldown() > 0)
                ? subcommand.getCooldown()
                : command.getCooldown();

        if (seconds <= 0)
            return null;

        String path = subcommand != null ? command.getName() + "." + subcommand.getName() : command.getName();
        String key = path + ":" + ctx.getUser().getId();
        long now = System.currentTimeMillis();

        Long existing = COOLDOWNS.getIfPresent(key);
        if (existing != null && existing > now)
            return SystemEmbeds.rateLimited(ctx.getUser().getAsMention(), existing / 1000L);

        COOLDOWNS.put(key, now + TimeUnit.SECONDS.toMillis(seconds));
        return null;
    }

    /** Releases a cooldown claimed by an invocation that never actually ran. */
    public static void release(CommandContext ctx, BunnyCommand command, BunnySubcommand subcommand) {
        String path = subcommand != null ? command.getName() + "." + subcommand.getName() : command.getName();
        COOLDOWNS.invalidate(path + ":" + ctx.getUser().getId());
    }

    /** Resolves the options in play, which differ between a command and its subcommand. */
    public static List<net.dv8tion.jda.api.interactions.commands.build.OptionData> optionsFor(
            BunnyCommand command, BunnySubcommand subcommand) {
        return subcommand != null ? subcommand.getOptions() : command.getOptions();
    }
}
