package org.bunnys.commands.admin;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.admin.AdminComponents;
import org.bunnys.beastars.commands.admin.AdminEmbeds;
import org.bunnys.beastars.commands.admin.AdminService;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.admin.GuildSettingsService;
import org.bunnys.handler.metrics.CacheRegistry;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.BunnySubcommandGroup;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.SystemEmbeds;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code /admin} - the single administration entry point.
 *
 * <ul>
 *   <li>{@code dashboard} - the master hub, from which every other panel is reached.</li>
 *   <li>{@code logging} - view or set the audit log channel.</li>
 *   <li>{@code errorlog} - view, set or clear the crash report channel. Developers only.</li>
 *   <li>{@code roles} - Bot Admin role configuration. Slash-only; it opens a modal.</li>
 * </ul>
 *
 * <p>Gating is deliberately asymmetric: {@code dashboard} and {@code logging} accept Bot
 * Admins, while {@code roles} demands native {@link Permission#ADMINISTRATOR} - a Bot
 * Admin editing the Bot Admin list could entrench or extend their own access.
 */
public class Admin extends BunnyCommand {

    public Admin(BunnyHub client) {
        super(client);
        setName("admin");
        setDescription("Administration panel for BeastarsBot.");
        addAliases("config", "settings");
        setCategory("Admin");
        setDmEnabled(false);

        addSubcommand(new BunnySubcommand() {
            {
                setName("dashboard");
                setDescription("Open the administration hub.");
                addAliases("hub", "panel");
                addOption(new OptionData(OptionType.BOOLEAN, "ephemeral",
                        "Show the panel only to you (default: true).", false));
                setAdminOnly(true);
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!inGuild(ctx))
                    return;

                // Defence in depth: the hub leads to destructive controls, so it
                // re-checks rather than trusting only the framework gate.
                if (!AdminService.isAdmin(ctx.getMember())) {
                    ctx.reply(AdminEmbeds.denied("You do not have permission to use this command."), true);
                    return;
                }

                // Private by default. A public panel is occasionally wanted - walking a
                // co-admin through a setting - but should never be the accident.
                boolean ephemeral = ctx.getBool("ephemeral", true);

                ctx.reply(AdminEmbeds.hub(ctx.getGuild().getName()),
                        AdminComponents.hub(), ephemeral);
            }
        });

        addSubcommandGroup(new BunnySubcommandGroup("logging",
                "Configure and inspect the audit trail.")

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("view");
                        setDescription("Show the current audit logging configuration.");
                addAliases("show");
                        setAdminOnly(true);
                    }

                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        if (!inGuild(ctx))
                            return;

                        String current = GuildSettingsService.getLogChannelId(ctx.getGuild().getId());
                        ctx.reply(AdminEmbeds.loggingOverview(current),
                                AdminComponents.loggingPage(current != null), true);
                    }
                })

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("set");
                        setDescription("Designate the channel that receives the audit trail.");
                addAliases("channel");
                        addOption(new OptionData(OptionType.CHANNEL, "channel",
                                "The text channel to log to.", true));
                        setAdminOnly(true);
                    }

                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        if (!inGuild(ctx))
                            return;

                        GuildChannel target = ctx.getChannelOption("channel");
                        if (target == null) {
                            ctx.reply(AdminEmbeds.error("No Channel Given",
                                    "Name the channel to log to, for example `channel:#audit-log`."), true);
                            return;
                        }

                        if (target.getType() != ChannelType.TEXT) {
                            ctx.reply(AdminEmbeds.error("Not a Text Channel",
                                    "Audit logs can only be posted to a normal text channel."), true);
                            return;
                        }

                        if (!ctx.getGuild().getSelfMember().hasPermission(target,
                                Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
                            ctx.reply(AdminEmbeds.error("Missing Permission",
                                    "I cannot post embeds in " + target.getAsMention()
                                            + ". Grant Send Messages and Embed Links there, then try again."), true);
                            return;
                        }

                        if (!GuildSettingsService.setLogChannel(ctx.getGuild().getId(), target.getId())) {
                            ctx.reply(AdminEmbeds.error("Update Failed",
                                    "The log channel could not be saved. Please try again."), true);
                            return;
                        }

                        AuditService.record(ctx.getGuild(), ctx.getUser(), ctx.getChannel(),
                                "Set Audit Log Channel", AuditService.AuditTarget.channel(target.getId()),
                                "Audit logs will now be posted to " + target.getAsMention() + ".");

                        ctx.reply(AdminEmbeds.success("Logging Enabled", BeastarsEmoji.EDIT,
                                "Admin actions will now be posted to " + target.getAsMention() + "."
                                        + "\n*Entries are always written to the database as well.*"), true);
                    }
                })

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("export");
                        setDescription("Download this server's audit trail as a text file.");
                        addAliases("download");
                        setAdminOnly(true);
                        // The trail carries member leg transfers as well as moderation,
                        // and there are far more of the former. Without a way to narrow
                        // it, a busy server's export is all economy and the moderation
                        // history an admin came looking for is off the end of the file.
                        addOption(new OptionData(OptionType.STRING, "type",
                                "Which entries to include (defaults to all).", false)
                                .addChoice("Everything", TYPE_ALL)
                                .addChoice("Admin actions only", AuditService.Category.ADMIN.name())
                                .addChoice("Leg transactions only", AuditService.Category.ECONOMY.name()));
                    }

                    /**
                     * Builds the file in memory and uploads the bytes directly.
                     *
                     * <p>Nothing is written to disk: the host runs on flash storage, and
                     * a export that touches the filesystem on every invocation is exactly
                     * the wear pattern to avoid.
                     */
                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        if (!inGuild(ctx))
                            return;

                        ctx.defer(true);

                        String rendered = AuditService.export(
                                ctx.getGuild().getId(), ctx.getGuild().getName(),
                                exportCategory(ctx.getString("type")));

                        if (rendered == null) {
                            ctx.reply(AdminEmbeds.error("Export Failed",
                                    "The audit trail could not be read. Please try again."), true);
                            return;
                        }

                        ctx.replyWithFile(
                                AdminEmbeds.exportReady(rendered),
                                List.of(),
                                FileUpload.fromData(
                                        rendered.getBytes(StandardCharsets.UTF_8), "audit_logs.txt"));
                    }
                }));

        // Developer-only, unlike everything else on this command. A crash report carries
        // stack traces and internal identifiers, and deciding where those get published
        // is a bot-operator question rather than a server-administration one - a Bot
        // Admin can already read the audit trail, but that is their own guild's activity,
        // not the bot's internals.
        addSubcommandGroup(new BunnySubcommandGroup("errorlog",
                "Choose where this server's crash reports are posted (developers only).")

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("health");
                        setDescription("Show pool, cache and connection health.");
                addAliases("status");
                        setExample("/admin errorlog health");
                        setDeveloperOnly(true);
                    }

                    /**
                     * Ephemeral and developer-only: these numbers are operational detail,
                     * not something a guild's members have any use for.
                     */
                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        ctx.reply(AdminEmbeds.health(
                                client.poolReport(),
                                CacheRegistry.report(),
                                ctx.getJDA().getGatewayPing(),
                                client.getMongoManager().isConnected()), true);
                    }
                })

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("view");
                        setDescription("Show where this server's crash reports currently go.");
                        addAliases("show");
                        setDeveloperOnly(true);
                    }

                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        if (!inGuild(ctx))
                            return;

                        ctx.reply(AdminEmbeds.errorLogOverview(
                                GuildSettingsService.getErrorChannelId(ctx.getGuild().getId())), true);
                    }
                })

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("set");
                        setDescription("Designate the channel that receives this server's crash reports.");
                        addAliases("channel");
                        setExample("/admin errorlog set channel:#bot-errors");
                        addOption(new OptionData(OptionType.CHANNEL, "channel",
                                "The text channel to post crash reports to.", true));
                        setDeveloperOnly(true);
                    }

                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        if (!inGuild(ctx))
                            return;

                        GuildChannel target = ctx.getChannelOption("channel");
                        if (target == null) {
                            ctx.reply(AdminEmbeds.error("No Channel Given",
                                    "Name the channel to report to, for example `channel:#bot-errors`."), true);
                            return;
                        }

                        if (target.getType() != ChannelType.TEXT) {
                            ctx.reply(AdminEmbeds.error("Not a Text Channel",
                                    "Crash reports can only be posted to a normal text channel."), true);
                            return;
                        }

                        // Checked now rather than discovered later: a crash reporter that
                        // silently cannot post is worse than no crash reporter, because
                        // nobody finds out until they go looking for a report that was
                        // never sent.
                        if (!ctx.getGuild().getSelfMember().hasPermission(target,
                                Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
                            ctx.reply(AdminEmbeds.error("Missing Permission",
                                    "I cannot post embeds in " + target.getAsMention()
                                            + ". Grant Send Messages and Embed Links there, then try again."), true);
                            return;
                        }

                        if (!GuildSettingsService.setErrorChannel(ctx.getGuild().getId(), target.getId())) {
                            ctx.reply(AdminEmbeds.error("Update Failed",
                                    "The error channel could not be saved. Please try again."), true);
                            return;
                        }

                        AuditService.record(ctx.getGuild(), ctx.getUser(), ctx.getChannel(),
                                "Set Error Log Channel", AuditService.AuditTarget.channel(target.getId()),
                                "Crash reports will now be posted to " + target.getAsMention() + ".");

                        ctx.reply(AdminEmbeds.success("Error Reporting Enabled", BeastarsEmoji.EDIT,
                                "Crashes that happen in this server will now be posted to "
                                        + target.getAsMention() + "."
                                        + "\n*The developers receive a copy regardless.*"), true);
                    }
                })

                .addSubcommand(new BunnySubcommand() {
                    {
                        setName("clear");
                        setDescription("Stop posting this server's crash reports to a channel.");
                        addAliases("unset", "disable");
                        setDeveloperOnly(true);
                    }

                    @Override
                    public void execute(BunnyHub client, CommandContext ctx) {
                        if (!inGuild(ctx))
                            return;

                        if (!GuildSettingsService.setErrorChannel(ctx.getGuild().getId(), null)) {
                            ctx.reply(AdminEmbeds.error("Update Failed",
                                    "The error channel could not be cleared. Please try again."), true);
                            return;
                        }

                        AuditService.record(ctx.getGuild(), ctx.getUser(), ctx.getChannel(),
                                "Cleared Error Log Channel", AuditService.AuditTarget.global(),
                                "Crash reports are no longer posted to this server.");

                        ctx.reply(AdminEmbeds.success("Error Reporting Disabled", BeastarsEmoji.RESET,
                                "Crashes in this server will no longer be posted here."
                                        + "\n*The developers still receive every report.*"), true);
                    }
                }));

        addSubcommand(new BunnySubcommand() {
            {
                setName("roles");
                setDescription("Choose which roles have Bot Admin permissions (Server Admin only).");
                addAliases("admins");
                setMentionEnabled(false); // Opens a modal; unreachable from a message.
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!inGuild(ctx))
                    return;

                if (!ctx.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                    ctx.reply(AdminEmbeds.denied(
                            "You must be a native Server Administrator to manage Bot Admin roles."), true);
                    return;
                }

                // Opens pre-populated with the current list, so the picker doubles as
                // the answer to "which roles are admins right now?".
                if (!ctx.replyModal(AdminComponents.adminRoles(
                        AdminService.getAdminRoles(ctx.getGuild().getId()))))
                    ctx.reply(SystemEmbeds.slashOnly("admin roles"), true);
            }
        });
    }

    private static boolean inGuild(CommandContext ctx) {
        if (ctx.getGuild() != null && ctx.getMember() != null)
            return true;

        ctx.reply(AdminEmbeds.denied("This command is only available inside a server."), true);
        return false;
    }

    /** The "no filter" choice value, distinct from the option simply being absent. */
    private static final String TYPE_ALL = "ALL";

    /**
     * Resolves the export's {@code type} choice.
     *
     * <p>Anything that is not a category name - omitted, {@link #TYPE_ALL}, or a stale
     * value typed on the mention path - means everything. An unrecognised filter must
     * widen the export rather than silently empty it: a log that came back blank because
     * of a typo reads as a log with nothing in it.
     */
    private static AuditService.Category exportCategory(String choice) {
        if (choice == null || choice.isBlank() || TYPE_ALL.equalsIgnoreCase(choice))
            return null;

        for (AuditService.Category category : AuditService.Category.values())
            if (category.name().equalsIgnoreCase(choice))
                return category;

        return null;
    }
}
