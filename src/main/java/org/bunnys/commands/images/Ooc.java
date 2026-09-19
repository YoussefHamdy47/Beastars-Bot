package org.bunnys.commands.images;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.ooc.OocComponents;
import org.bunnys.beastars.commands.ooc.OocEmbeds;
import org.bunnys.beastars.commands.ooc.OocService;
import org.bunnys.beastars.commands.ooc.OocService.RandomImage;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /ooc} - the server's out-of-character image album.
 *
 * <p>Renamed from {@code /imgur}, which named the host rather than the thing members
 * actually use it for. Imgur is still where the pictures live; that is now an
 * implementation detail of {@link OocService} rather than the command name.
 */
public class Ooc extends BunnyCommand {

    public Ooc(BunnyHub client) {
        super(client);
        setName("ooc");
        setDescription("Pull a random image from the server's OOC album.");
        addAliases("album", "imgur");
        setCategory("General");
        setCooldown(5);
        setDmEnabled(false);

        // `@BotName ooc` is the whole point of the command, and making people append
        // `get` every time is friction for nothing. Discord forbids the equivalent on the
        // slash path, so `/ooc get` stays explicit there.
        setDefaultSubcommand("get");

        addSubcommand(new BunnySubcommand() {
            {
                setName("get");
                setDescription("Show a random image from the server's OOC album.");
                addAliases("show", "random");
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                ctx.defer();

                RandomImage result = OocService.randomImage(ctx.getGuild().getId());

                if (!result.ok()) {
                    ctx.reply(OocEmbeds.failure(result.outcome()), true);
                    return;
                }

                // Sent as a bare URL rather than an embed. Discord unfurls it natively,
                // which avoids the flash an embedded image produces as the proxy resolves
                // and the layout settles - and here the picture is the entire message,
                // with nothing around it that needs the house embed style.
                ctx.replyContent(result.url(), OocComponents.imageControls(result.url()));
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("setlink");
                setDescription("Update the album this server pulls OOC images from.");
                addAliases("link");
                addOption(new OptionData(OptionType.STRING, "link", "The new Imgur album URL", true));
                setAdminOnly(true);
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                String link = ctx.getString("link");
                if (link == null) {
                    ctx.reply(OocEmbeds.error("No Link Given",
                            "Provide the album URL, for example `link:https://imgur.com/a/abc123`."), true);
                    return;
                }

                ctx.reply(switch (OocService.setAlbumLink(ctx.getGuild().getId(), link)) {
                    case OK -> OocEmbeds.success("Album Linked",
                            "This server's OOC album is now:\n`" + link.trim() + "`");
                    case INVALID_URL -> OocEmbeds.error("Invalid Link",
                            "That is not a valid Imgur album or gallery URL.");
                    default -> OocEmbeds.error("Save Failed",
                            "The album link could not be saved. Please try again.");
                }, true);
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("refresh");
                setDescription("Refresh the cached album contents.");
                addAliases("reload");
                setAdminOnly(true);
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                ctx.reply(switch (OocService.refresh(ctx.getGuild().getId())) {
                    case OK -> OocEmbeds.success("Cache Refreshed",
                            "The cached album contents were cleared. "
                                    + "The next `/ooc get` will pull fresh images from Imgur.");
                    case ON_COOLDOWN -> OocEmbeds.error("Refreshed Recently",
                            "This album was refreshed within the last "
                                    + OocService.refreshCooldownHours() + " hours. Try again later.");
                    case INVALID_URL -> OocEmbeds.error("Album Not Configured",
                            "The configured Imgur URL is invalid. Set a valid one with `/ooc setlink`.");
                    default -> OocEmbeds.error("Refresh Failed",
                            "The cache could not be refreshed. Please try again.");
                }, true);
            }
        });
    }
}
