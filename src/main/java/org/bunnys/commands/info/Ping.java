package org.bunnys.commands.info;

import net.dv8tion.jda.api.JDA;
import org.bunnys.beastars.commands.info.InfoEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /ping} - the bot's two latencies, which measure different things.
 *
 * <p>The gateway figure is the websocket heartbeat JDA already tracks, so it costs
 * nothing to read. The REST figure is a live round trip to Discord's HTTP API, and it
 * is the one that actually predicts how quickly commands will answer.
 */
public class Ping extends BunnyCommand {

    public Ping(BunnyHub client) {
        super(client);
        setName("ping");
        setDescription("Check the bot's gateway and API latency");
        addAliases("latency");
        setCategory("Information");
        setExample("/ping");
        setCooldown(5);
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        ctx.defer();

        JDA jda = ctx.getJDA();
        long gateway = jda.getGatewayPing();

        // Measured through the callback rather than complete(). complete() blocks the
        // calling thread with no timeout, and the calling thread here belongs to a
        // four-thread bounded pool - four calls that never come back and the bot stops
        // answering anything, with no error anywhere to say why. The reply lands from
        // JDA's own callback pool, so the worker is released immediately.
        jda.getRestPing().queue(
                rest -> ctx.reply(InfoEmbeds.ping(gateway, rest)),
                // A failed probe is still worth reporting: the gateway figure alone
                // tells the reader the bot is connected.
                error -> ctx.reply(InfoEmbeds.ping(gateway, -1L)));
    }
}
