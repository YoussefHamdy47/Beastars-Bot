package org.bunnys.commands.images;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.beastars.commands.manga.MangaCatalog;
import org.bunnys.beastars.commands.manga.MangaComponents;
import org.bunnys.beastars.commands.manga.MangaEmbeds;
import org.bunnys.beastars.commands.manga.MangaService;
import org.bunnys.beastars.commands.manga.MangaService.MangaRef;
import org.bunnys.beastars.commands.manga.MangaService.PageResult;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

/**
 * {@code /manga} - opens a paginated reader.
 * Also {@code @BotName manga series:BST source:MD chapter:5 page:1}.
 *
 * <p>Routing only. Fetching and caching belong to {@link MangaService}, the embed to
 * {@link MangaEmbeds}, the controls to {@link MangaComponents}.
 *
 * <h2>Option order is load-bearing</h2>
 * Discord requires every required option to be declared before the optional ones, and
 * the mention parser fills positional arguments in declared order. Declaring
 * {@code series source chapter page group} therefore does two jobs at once: it satisfies Discord,
 * and it makes {@code @BotName manga BST MD 5 1} read the way someone would say it out
 * loud, with page next to the chapter it belongs to. Reordering these silently changes
 * what a positional mention invocation means.
 */
public class Manga extends BunnyCommand {

    /** Pages are 1-indexed. */
    private static final int FIRST_PAGE = 1;

    /** Named so the required-option errors can all point at the same worked example. */
    private static final String EXAMPLE = "series:BST source:MD chapter:5 page:1";

    public Manga(BunnyHub client) {
        super(client);
        setName("manga");
        setDescription("Read Beastars and Beast Complex manga directly in Discord");
        addAliases("m", "read", "chapter");
        setCategory("Manga");
        setCooldown(5);
        setExample("/manga " + EXAMPLE);

        // --- required, in reading order: which comic, from where, how far in ---
        addOption(new OptionData(OptionType.STRING, "series", "The series to read", true)
                .addChoice("Beastars", "BST")
                .addChoice("Beast Complex", "BC")
                .addChoice("Original Beast Complex", "OBC")
                .addChoice("Paru Graffiti", "PG"));
        addOption(new OptionData(OptionType.STRING, "source", "The source to read from", true)
                .addChoice("MangaDex", "MD")
                .addChoice("Google Drive", "G")
                .addChoice("Viz (Official)", "V")
                .addChoice("Raw (Japanese)", "R"));
        addOption(new OptionData(OptionType.INTEGER, "chapter", "The chapter number", true)
                .setMinValue(1));
        // Required, and declared last among the required options so it stays next to the
        // chapter it indexes into. Discord only demands required-before-optional, which
        // this satisfies - `group` is the sole optional and stays at the end.
        addOption(new OptionData(OptionType.INTEGER, "page", "The page to open on", true)
                .setMinValue(FIRST_PAGE));

        addOption(new OptionData(OptionType.STRING, "group", "The translation group", false)
                .addChoice("Hot Chocolate Scans", "HCS")
                .addChoice("Hybridgumi", "HG"));
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        // Slash enforces the required options for us; a mention invocation can omit or
        // mistype any of them, so all four are re-checked here rather than trusted.
        String requestedSeries = ctx.getString("series");
        String series = MangaCatalog.resolveSeries(requestedSeries);

        if (series == null) {
            boolean omitted = requestedSeries == null || requestedSeries.isBlank();

            // Naming what they typed matters here: an unquoted multi-word name like
            // `Beast Complex MD 5` splits across positional arguments, so the series
            // arrives as "Beast". Echoing it back is what makes that legible.
            ctx.reply(MangaEmbeds.error(
                    omitted ? "Series Required" : "Unknown Series",
                    (omitted
                            ? "Tell me which series to open, one of "
                            : "I do not know the series `" + requestedSeries + "`. Pick one of ")
                            + inlineCodes(MangaCatalog.seriesCodes())
                            + ".\nFor example: `" + EXAMPLE + "`"
                            + "\n*Multi-word names need quoting: `series:\"Beast Complex\"`.*"), true);
            return;
        }

        // Resolved against the allow-list rather than merely checked for emptiness. An
        // unrecognised code used to fall through to the Drive path and come back as "No
        // Drive folder configured for that series and source" - a deployment problem the
        // reader cannot act on, describing what was actually a typo.
        String requestedSource = ctx.getString("source");
        String source = MangaCatalog.resolveSource(requestedSource);

        if (source == null) {
            boolean omitted = requestedSource == null || requestedSource.isBlank();

            ctx.reply(MangaEmbeds.error(
                    omitted ? "Source Required" : "Unknown Source",
                    (omitted
                            ? "Tell me where to read from, one of "
                            : "I cannot read from `" + requestedSource + "`. Pick one of ")
                            + inlineCodes(MangaCatalog.sourceCodes())
                            + ".\nFor example: `" + EXAMPLE + "`"), true);
            return;
        }

        Integer chapter = ctx.getInt("chapter");
        if (chapter == null) {
            ctx.reply(MangaEmbeds.error("Chapter Required",
                    "Tell me which chapter to open."
                            + "\nFor example: `" + EXAMPLE + "`"), true);
            return;
        }

        // Required on both paths now, so there is no default to fall back to. Discord
        // enforces presence and setMinValue on the slash path; a mention invocation can
        // still omit it, or type something that is not a whole number, and getInt reports
        // both as null. All of it is one answer to the reader - "name a page" - so it is
        // one branch rather than a taxonomy of ways to have not named one.
        Integer page = ctx.getInt("page");
        if (page == null || page < FIRST_PAGE) {
            ctx.reply(MangaEmbeds.error("Page Required",
                    "Tell me which page to open on. Use a whole number of "
                            + FIRST_PAGE + " or greater."
                            + "\nFor example: `" + EXAMPLE + "`"), true);
            return;
        }

        // `series` and `source` are already canonical; only the group still needs folding.
        MangaRef ref = new MangaRef(
                series,
                source,
                upper(ctx.getString("group", MangaCatalog.DEFAULT_GROUP)),
                chapter);

        ctx.defer();

        PageResult result = MangaService.fetchPage(ref, page);

        if (result.failed()) {
            ctx.reply(MangaEmbeds.error("Chapter Unavailable", result.error()), true);
            return;
        }

        ctx.replyWithFile(
                MangaEmbeds.page(result, "Start", ctx.getUser().getEffectiveName()),
                MangaComponents.navigation(ref, result.page(), result.maxPages(),
                        System.currentTimeMillis(), ctx.getUser().getId()),
                FileUpload.fromData(result.image(), MangaEmbeds.PAGE_ATTACHMENT));
    }

    private static String inlineCodes(java.util.List<String> codes) {
        return String.join(", ", codes.stream().map(code -> "`" + code + "`").toList());
    }

    static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
