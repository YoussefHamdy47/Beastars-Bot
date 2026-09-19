package org.bunnys.commands.info;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.info.InfoEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;

/**
 * {@code /avatar} - someone's avatar, at full size.
 *
 * <p>A member can have a per-server avatar on top of their global one. The server one
 * is shown by default, because that is the face other people in the channel actually
 * see; {@code global:true} asks for the account-wide image instead.
 */
public class Avatar extends BunnyCommand {

    /** Discord serves powers of two from 16 to 4096; 1024 fills the embed without waste. */
    private static final int AVATAR_SIZE = 1024;

    public Avatar(BunnyHub client) {
        super(client);
        setName("avatar");
        setDescription("Show a user's avatar at full size");
        addAliases("av", "pfp");
        setCategory("Information");
        setExample("/avatar user:@Legoshi global:true");
        setCooldown(5);
        addOption(new OptionData(OptionType.USER, "user", "Whose avatar to show. Defaults to you", false));
        addOption(new OptionData(OptionType.BOOLEAN, "global",
                "Show the global avatar instead of the server one", false));
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        User target = ctx.getUserOption("user");

        // A raw value that resolved to nobody means the user named someone the mention
        // parser could not find. Silently answering about the caller instead would be
        // worse than saying so.
        if (target == null && ctx.getString("user") != null) {
            ctx.reply(InfoEmbeds.unknownUser(), true);
            return;
        }

        if (target == null)
            target = ctx.getUser();

        Member member = ctx.getMemberOption("user");
        if (member == null && ctx.getUserOption("user") == null)
            member = ctx.getMember();

        // Both avatars are rendered when they differ, so `global` only decides which of
        // the two gets the large slot rather than hiding the other one entirely.
        boolean preferGlobal = ctx.getBool("global", false);

        ctx.reply(InfoEmbeds.avatar(target, member, preferGlobal),
                List.of(InfoEmbeds.avatarButtons(target, member)));
    }
}
