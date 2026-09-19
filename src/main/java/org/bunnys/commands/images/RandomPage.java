package org.bunnys.commands.images;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.beastars.commands.manga.MangaCatalog;
import org.bunnys.beastars.commands.manga.MangaComponents;
import org.bunnys.beastars.commands.manga.MangaEmbeds;
import org.bunnys.beastars.commands.manga.MangaService.PageResult;
import org.bunnys.beastars.commands.manga.RandomPageService;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /randompage} - opens the reader on a randomly drawn page.
 *
 * <p>Renders through the same {@link MangaComponents} builder {@code /manga} uses, so
 * both readers behave identically.
 */
public class RandomPage extends BunnyCommand {

    public RandomPage(BunnyHub client) {
        super(client);
        setName("randompage");
        setDescription("Displays a completely random page from the manga catalog");
        addAliases("rp", "randpage");
        setCategory("Manga");
        setCooldown(5);
        addOption(new OptionData(OptionType.STRING, "series", "The series to pick from", false)
                .addChoice("Beastars", "BST")
                .addChoice("Beast Complex", "BC")
                .addChoice("Original Beast Complex", "OBC")
                .addChoice("Paru Graffiti", "PG"));
        addOption(new OptionData(OptionType.STRING, "source", "The source to read from", false)
                .addChoice("MangaDex", "MD")
                .addChoice("Google Drive", "G")
                .addChoice("Viz (Official)", "V")
                .addChoice("Raw (Japanese)", "R"));
        addOption(new OptionData(OptionType.STRING, "group", "The translation group", false)
                .addChoice("Hot Chocolate Scans", "HCS")
                .addChoice("Hybridgumi", "HG"));
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        ctx.defer();

        // Every option here is optional, so an unrecognised one falls back to the default
        // rather than erroring the way /manga does - "surprise me" has an obvious answer
        // when the input is unusable. Resolving rather than upper-casing is what makes
        // that fallback happen: a bare upper-case pass would send a nonsense code down to
        // the catalogue, which reports it as an unconfigured source instead.
        PageResult result = RandomPageService.fetchRandomPage(
                orDefault(MangaCatalog.resolveSeries(ctx.getString("series")), MangaCatalog.DEFAULT_SERIES),
                orDefault(MangaCatalog.resolveSource(ctx.getString("source")), MangaCatalog.DEFAULT_SOURCE),
                Manga.upper(ctx.getString("group", MangaCatalog.DEFAULT_GROUP)));

        if (result.failed()) {
            ctx.reply(MangaEmbeds.error("No Page Drawn", result.error()), true);
            return;
        }

        ctx.replyWithFile(
                MangaEmbeds.page(result, "Random", ctx.getUser().getEffectiveName()),
                MangaComponents.navigation(result.ref(), result.page(), result.maxPages(),
                        System.currentTimeMillis(), ctx.getUser().getId()),
                FileUpload.fromData(result.image(), MangaEmbeds.PAGE_ATTACHMENT));
    }

    private static String orDefault(String resolved, String fallback) {
        return resolved != null ? resolved : fallback;
    }
}
