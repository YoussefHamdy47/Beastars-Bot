package org.bunnys.commands.info;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.bunnys.beastars.commands.info.InfoEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;

/**
 * {@code /uptime} - how long this process has been alive.
 *
 * <p>Measured from {@link BunnyHub#getStartTime()}, which is stamped in the constructor,
 * so it counts from process start rather than from the gateway connecting.
 */
public class Uptime extends BunnyCommand {

    public Uptime(BunnyHub client) {
        super(client);
        setName("uptime");
        setDescription("Show how long the bot has been online");
        addAliases("up");
        setCategory("Information");
        setExample("/uptime");
        setCooldown(5);
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        var self = ctx.getJDA().getSelfUser();

        var embed = InfoEmbeds.uptime(
                self.getName(),
                self.getEffectiveAvatarUrl(),
                client.getStartTime(),
                ctx.getJDA().getGuilds().size(),
                client.getVersion(),
                client.getDeveloperName(),
                client.getPrimaryDeveloperId());

        // The source link is a button rather than another field: it is the one thing on
        // this card somebody might actually want to click through to.
        String source = client.getDeveloperUrl();
        if (source == null || source.isBlank()) {
            ctx.reply(embed);
            return;
        }

        ctx.reply(embed, List.of(ActionRow.of(Button.link(source, "Source"))));
    }
}
