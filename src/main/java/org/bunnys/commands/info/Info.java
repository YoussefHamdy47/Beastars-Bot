package org.bunnys.commands.info;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.info.InfoEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /info} - lookups for the two things a member usually wants to check: a person,
 * or the server they are both standing in.
 *
 * <p>Kept as one command with two branches rather than two commands. They answer the
 * same question about different subjects, and pairing them means the help menu carries
 * one entry a reader has to find instead of two.
 */
public class Info extends BunnyCommand {

    public Info(BunnyHub client) {
        super(client);
        setName("info");
        setDescription("Look up a user or this server");
        addAliases("whois", "about");
        setCategory("Information");
        setCooldown(5);

        addSubcommand(new BunnySubcommand() {
            {
                setName("user");
                setDescription("Show account and membership details for a user");
                addAliases("member");
                setExample("/info user user:@Legoshi");
                addOption(new OptionData(OptionType.USER, "user", "Who to look up. Defaults to you", false));
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                User target = ctx.getUserOption("user");

                // A raw value that resolved to nobody means the mention parser could not
                // find who was named. Silently answering about the caller would be worse.
                if (target == null && ctx.getString("user") != null) {
                    ctx.reply(InfoEmbeds.unknownUser(), true);
                    return;
                }

                Member member;
                if (target == null) {
                    target = ctx.getUser();
                    member = ctx.getMember();
                } else {
                    member = ctx.getMemberOption("user");
                }

                // Looking up the bot is the moment somebody is asking what this thing is
                // and who made it, so its own card carries the credit. Every other
                // profile gets the plain overload with no credit at all.
                boolean isSelf = target.getId().equals(ctx.getJDA().getSelfUser().getId());

                ctx.reply(isSelf
                        ? InfoEmbeds.userInfo(target, member, client.getVersion(),
                                client.getDeveloperName(), client.getPrimaryDeveloperId())
                        : InfoEmbeds.userInfo(target, member));
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("server");
                setDescription("Show details about this server");
                addAliases("guild");
                setExample("/info server");
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                // CommandGate gates whole commands, so this branch guards itself rather
                // than forcing /info user to be guild-only alongside it.
                if (!ctx.isFromGuild()) {
                    ctx.reply(InfoEmbeds.guildOnly(), true);
                    return;
                }

                // The owner renders as a raw mention rather than through getOwner(),
                // which needs a member cache this bot deliberately does not keep.
                ctx.reply(InfoEmbeds.serverInfo(ctx.getGuild()));
            }
        });
    }
}
