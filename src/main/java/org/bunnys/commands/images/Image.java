package org.bunnys.commands.images;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.image.ImageEmbeds;
import org.bunnys.beastars.commands.image.ImageService;
import org.bunnys.beastars.database.ImageData;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;
import java.util.Optional;

/**
 * {@code /image} - custom per-guild image shortcuts.
 *
 * <p>Routing only; storage lives in {@link ImageService} and wording in
 * {@link ImageEmbeds}. Admin subcommands use the framework's {@code setAdminOnly}, so
 * configured Bot Admin roles work here exactly as they do everywhere else.
 */
public class Image extends BunnyCommand {

    public Image(BunnyHub client) {
        super(client);
        setName("image");
        setDescription("View, add, remove, or list custom server image shortcuts");
        addAliases("img", "pic");
        setCategory("General");
        setCooldown(3);
        setDmEnabled(false);

        addSubcommand(new BunnySubcommand() {
            {
                setName("get");
                setDescription("View a custom image shortcut");
                addAliases("show");
                addOption(new OptionData(OptionType.STRING, "name",
                        "The shortcut name of the image", true).setAutoComplete(true));
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!inGuild(ctx))
                    return;

                String name = ctx.getString("name");
                if (name == null) {
                    ctx.reply(ImageEmbeds.error("No Name Given",
                            "Tell me which shortcut to show, for example `name:legoshi`."), true);
                    return;
                }

                ctx.defer();

                Optional<ImageData.ImageEntry> entry = ImageService.find(ctx.getGuild().getId(), name);

                if (entry.isEmpty()) {
                    ctx.reply(ImageEmbeds.error("Image Not Found",
                            "No image shortcut named `" + name + "` exists in this server."), true);
                    return;
                }

                ctx.reply(ImageEmbeds.image(entry.get(), ctx.getUser().getEffectiveName()));
            }

            @Override
            public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
                return suggest(event);
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("add");
                setDescription("Add or update a custom image shortcut (Admin only)");
                addAliases("save");
                addOption(new OptionData(OptionType.STRING, "name", "The shortcut name of the image", true));
                addOption(new OptionData(OptionType.STRING, "url",
                        "The direct image URL or Discord CDN link", true));
                setAdminOnly(true);
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!inGuild(ctx))
                    return;

                String name = ctx.getString("name");
                String url = ctx.getString("url");

                if (name == null || url == null) {
                    ctx.reply(ImageEmbeds.error("Missing Details",
                            "Give both a name and a link, for example `name:legoshi url:https://...png`."), true);
                    return;
                }

                ctx.defer();

                ctx.reply(switch (ImageService.save(ctx.getGuild().getId(), name, url)) {
                    case ADDED -> ImageEmbeds.success("Image Added",
                            "Saved the shortcut `" + name.trim() + "`.");
                    case UPDATED -> ImageEmbeds.success("Image Updated",
                            "Repointed the shortcut `" + name.trim() + "` at the new link.");
                    case INVALID_URL -> ImageEmbeds.error("Invalid Image URL",
                            "The link must be a direct image URL or a Discord CDN attachment.");
                    default -> ImageEmbeds.error("Save Failed",
                            "The shortcut could not be saved. Please try again.");
                });
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("remove");
                setDescription("Remove a custom image shortcut (Admin only)");
                addAliases("delete", "rm");
                addOption(new OptionData(OptionType.STRING, "name",
                        "The shortcut name of the image", true).setAutoComplete(true));
                setAdminOnly(true);
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!inGuild(ctx))
                    return;

                String name = ctx.getString("name");
                if (name == null) {
                    ctx.reply(ImageEmbeds.error("No Name Given",
                            "Tell me which shortcut to remove, for example `name:legoshi`."), true);
                    return;
                }

                ctx.defer();

                ctx.reply(switch (ImageService.remove(ctx.getGuild().getId(), name)) {
                    case REMOVED -> ImageEmbeds.success("Image Removed",
                            "Removed the shortcut `" + name.trim() + "`.");
                    case NOT_FOUND -> ImageEmbeds.error("Image Not Found",
                            "No image shortcut named `" + name.trim() + "` exists in this server.");
                    default -> ImageEmbeds.error("Remove Failed",
                            "The shortcut could not be removed. Please try again.");
                });
            }

            @Override
            public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
                return suggest(event);
            }
        });

        addSubcommand(new BunnySubcommand() {
            {
                setName("list");
                setDescription("List all custom image shortcuts for this server");
                addAliases("all");
            }

            @Override
            public void execute(BunnyHub client, CommandContext ctx) {
                if (!inGuild(ctx))
                    return;

                ctx.defer();
                ctx.reply(ImageEmbeds.catalogue(ImageService.list(ctx.getGuild().getId())));
            }
        });
    }

    private static List<String> suggest(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null || !event.getFocusedOption().getName().equals("name"))
            return List.of();

        return ImageService.suggest(event.getGuild().getId(), event.getFocusedOption().getValue());
    }

    private static boolean inGuild(CommandContext ctx) {
        if (ctx.getGuild() != null)
            return true;

        ctx.reply(ImageEmbeds.denied("Image shortcuts are per-server, so this only works in a server."), true);
        return false;
    }
}
