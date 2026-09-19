package org.bunnys.commands.admin;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.admin.AuditService;
import org.bunnys.beastars.commands.presence.PresenceEmbeds;
import org.bunnys.beastars.commands.presence.PresenceService;
import org.bunnys.beastars.commands.presence.PresenceService.Kind;
import org.bunnys.beastars.commands.presence.PresenceService.Parsed;
import org.bunnys.beastars.commands.presence.PresenceService.Setting;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /presence} - what the bot appears to be doing, under its name.
 *
 * <p>Routing only: gate, validate, hand to {@link PresenceService}, render. Every rule
 * about what Discord will accept lives in the service, so the slash path and the
 * {@code @BotName presence} path are governed by the same checks.
 *
 * <h2>Why this is not under {@code /admin}</h2>
 * {@code /admin} configures one server. This configures the bot itself: Discord gives a
 * bot a single presence for the whole process, so a change made in one server is
 * immediately visible in every other server the bot is in. Filing it under a per-guild
 * administration panel would imply a scope it does not have.
 *
 * <p>Gating follows that reasoning: <b>developers only</b>. A server administrator is
 * trusted with their own server, and this is not their own server - one admin could
 * change what every other community sees, with no way for those communities to object or
 * even to know where it came from. The people who own the bot account are the only ones
 * whose authority actually matches the reach of the setting.
 *
 * <p>Deliberately {@code setDeveloperOnly} rather than {@code setDeveloperBypass}: bypass
 * would also skip the guild, cooldown and access checks, and there is no reason for a
 * developer to be exempt from those. Set at the command level so no subcommand can drift
 * from it, and <em>not</em> combined with {@code setAdminOnly} - the gate runs both, so a
 * developer who happens not to be an administrator would be refused by the second one.
 */
public class Presence extends BunnyCommand {

    public Presence(BunnyHub client) {
        super(client);
        setName("presence");
        setDescription("Change what the bot appears to be doing.");
        addAliases("status", "activity");
        setCategory("Developer");
        setCooldown(5);
        setDmEnabled(false);
        setDeveloperOnly(true);
        setExample("/presence set activity:watching text:Beastars");

        addSubcommand(new BunnySubcommand() {
            {
                setName("set");
                setDescription("Set what the bot appears to be doing.");
                addAliases("change");

                // Choices make the slash path unable to send anything invalid. The
                // mention path has no such protection, which is why the service
                // re-resolves this rather than trusting it.
                OptionData activity = new OptionData(OptionType.STRING, "activity",
                        "What kind of activity to show.", true);
                for (Kind kind : Kind.values())
                    activity.addChoice(kind.label(), kind.code());
                addOption(activity);

                addOption(new OptionData(OptionType.STRING, "text",
                        "The line to show. Unicode emoji are fine; custom emoji will not render.", true)
                        .setMaxLength(PresenceService.MAX_TEXT_LENGTH));

                addOption(new OptionData(OptionType.STRING, "url",
                        "Twitch or YouTube link. Required for streaming, unused otherwise.", false));
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                Parsed parsed = PresenceService.parse(
                        ctx.getString("activity"),
                        ctx.getString("text"),
                        ctx.getString("url"),
                        ctx.getUser());

                // Validation runs before defer so a refusal stays ephemeral, and before
                // the write so nothing invalid ever reaches the database or the gateway.
                if (parsed.failed()) {
                    ctx.reply(PresenceEmbeds.error(parsed.errorTitle(), parsed.errorMessage()), true);
                    return;
                }

                ctx.defer(true);

                Setting stored = PresenceService.apply(client.getJDA(), parsed.setting());
                if (stored == null) {
                    ctx.reply(PresenceEmbeds.error("Update Failed",
                            "The presence could not be saved, so nothing was changed."
                                    + " Please try again."), true);
                    return;
                }

                // Global change, guild-scoped record: the audit entry lands in whichever
                // server it was run from, which is where anyone asking "who changed this"
                // will be looking.
                AuditService.record(ctx.getGuild(), ctx.getUser(), ctx.getChannel(),
                        "Change Bot Presence", AuditService.AuditTarget.none(),
                        "The bot now shows **" + stored.preview() + "** in every server.");

                ctx.reply(PresenceEmbeds.updated(stored), true);
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("show");
                setDescription("Show the current presence and who set it.");
                addAliases("view", "current");
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                ctx.defer(true);
                ctx.reply(PresenceEmbeds.overview(PresenceService.current()), true);
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("reset");
                setDescription("Return the presence to the default.");
                addAliases("clear", "default");
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                ctx.defer(true);

                Setting stored = PresenceService.reset(client.getJDA());
                if (stored == null) {
                    ctx.reply(PresenceEmbeds.error("Reset Failed",
                            "The stored presence could not be cleared. Please try again."), true);
                    return;
                }

                AuditService.record(ctx.getGuild(), ctx.getUser(), ctx.getChannel(),
                        "Reset Bot Presence", AuditService.AuditTarget.none(),
                        "The bot is back on its default presence, **" + stored.preview() + "**.");

                ctx.reply(PresenceEmbeds.reset(stored), true);
            }
        });
    }
}
