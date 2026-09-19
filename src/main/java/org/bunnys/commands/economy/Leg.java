package org.bunnys.commands.economy;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.leg.LegComponents;
import org.bunnys.beastars.commands.leg.LegEmbeds;
import org.bunnys.beastars.commands.leg.LegLeaderboardPolicy;
import org.bunnys.beastars.commands.leg.LegLeaderboardSnapshots;
import org.bunnys.beastars.commands.leg.LegRuleEngine;
import org.bunnys.beastars.commands.leg.LegService;
import org.bunnys.beastars.database.LegData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;

/**
 * {@code /leg} - the Beastars flesh economy. Also reachable as {@code @BotName leg ...}.
 *
 * <p>Routing only: gate, read, render. Persistence lives in {@link LegService}, wording
 * in {@link LegEmbeds}, buttons in {@link LegComponents}.
 */
public class Leg extends BunnyCommand {

    public Leg(BunnyHub client) {
        super(client);
        setName("leg");
        setDescription("The Beastars flesh economy. Offer legs or check stats.");
        addAliases("legs", "flesh");
        setCategory("Economy");
        setCooldown(3);
        setDmEnabled(false);

        addSubcommand(new BunnySubcommand() {
            {
                setName("offer");
                setDescription("Offer one of your natural legs to a fellow member.");
                addAliases("give", "feed");
                addOption(new OptionData(OptionType.USER, "member", "The member to feed.", true));
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!hasAccess(ctx))
                    return;

                User receiver = ctx.getUserOption("member");
                if (receiver == null) {
                    ctx.reply(LegEmbeds.error("No Member Given",
                            "Name who you are feeding, for example `member:@someone`."), true);
                    return;
                }

                User sender = ctx.getUser();

                // Cheap rejections up front so we never post a confirmation the user
                // cannot act on. The authoritative guard is in the database write.
                if (sender.getId().equals(receiver.getId())) {
                    ctx.reply(LegEmbeds.offerFailure(LegService.OfferStatus.SELF_TARGET), true);
                    return;
                }
                if (receiver.isBot()) {
                    ctx.reply(LegEmbeds.offerFailure(LegService.OfferStatus.BOT_TARGET), true);
                    return;
                }

                ctx.defer();

                LegData senderData = LegService.getUser(ctx.getGuild().getId(), sender.getId());
                if (senderData.getLegsGiven() >= LegService.MAX_LEGS) {
                    ctx.reply(LegEmbeds.offerFailure(LegService.OfferStatus.EXHAUSTED), true);
                    return;
                }

                ctx.reply(LegEmbeds.offerConfirmation(sender, receiver),
                        List.of(LegComponents.offerConfirmation(receiver, sender)));
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("stats");
                setDescription("Check the anatomy and flesh records of yourself or another member.");
                addAliases("profile", "me");
                addOption(new OptionData(OptionType.USER, "member",
                        "The member to check (leave blank for self).", false));
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!hasAccess(ctx))
                    return;

                User target = ctx.getUserOptionOrSelf("member");
                ctx.defer();

                LegData data = LegService.getUser(ctx.getGuild().getId(), target.getId());

                ctx.reply(LegEmbeds.statsOverview(target, data),
                        LegComponents.stats(target.getId(), LegComponents.PAGE_OVERVIEW, 1, 1));
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("leaderboard");
                setDescription("View the wealthiest members in the flesh economy.");
                addAliases("lb", "top");
                addOption(new OptionData(OptionType.BOOLEAN, "live",
                        "Re-read the ranking on every click instead of freezing it (default: false).",
                        false));
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!hasAccess(ctx))
                    return;

                ctx.defer();

                // Frozen unless asked otherwise. Paging a board that is being rewritten
                // underneath you shows pages that never existed together: somebody moves
                // up while you are on page one and reappears on page two, pushing whoever
                // was last off the end. See LegLeaderboardSnapshots.
                boolean frozen = !ctx.getBool("live", false);
                LegComponents.Session session =
                        LegComponents.Session.opening(ctx.getUser().getId(), frozen);

                // The guild's own visibility rules apply from the first render, not only
                // once somebody clicks something.
                LegLeaderboardPolicy.Result policy =
                        LegLeaderboardPolicy.build(ctx.getGuild(), List.of());

                LegLeaderboardSnapshots.View view = LegLeaderboardSnapshots.view(
                        session, ctx.getGuild().getId(), 1, null, policy.filter());

                if (view.page().failed()) {
                    ctx.reply(LegEmbeds.error("Records Unreachable",
                            "Failed to retrieve the Black Market records."), true);
                    return;
                }

                ctx.reply(LegEmbeds.leaderboard(view.page(), List.of(), frozen,
                                view.snapshotAtEpochSeconds()),
                        LegComponents.leaderboard(view.page().page(), view.page().maxPages(),
                                session, false, frozen));
            }
        });
    }

    /**
     * Guild + economy-rule gate shared by every subcommand.
     *
     * <p>Runs before {@code defer} so denials can stay ephemeral on the slash path.
     * Both checks are served from Caffeine, so this is a memory read once warm.
     */
    private static boolean hasAccess(CommandContext ctx) {
        if (ctx.getGuild() == null || ctx.getMember() == null) {
            ctx.reply(LegEmbeds.denied("Server Only",
                    "The flesh economy only exists inside a server."), true);
            return false;
        }

        LegRuleEngine.RuleResult result = LegRuleEngine.canParticipate(ctx.getMember());
        if (!result.isAllowed()) {
            ctx.reply(LegEmbeds.denied(result.getTitle(), result.getMessage()), true);
            return false;
        }
        return true;
    }
}
