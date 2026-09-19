package org.bunnys.commands;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.beastars.commands.wiki.WikiEmbeds;
import org.bunnys.beastars.commands.wiki.WikiService;
import org.bunnys.beastars.commands.wiki.WikiService.Article;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.util.List;

/**
 * {@code /wiki} - searches the Beastars Fandom wiki.
 *
 * <p>Because {@code query} is the trailing string option, the mention form accepts a
 * bare phrase: {@code @BotName wiki Legoshi and Haru} searches the whole thing rather
 * than dropping everything after the first word.
 */
public class Wiki extends BunnyCommand {

    public Wiki(BunnyHub client) {
        super(client);
        setName("wiki");
        setDescription("Search the Beastars Fandom Wiki");
        addAliases("lookup", "w");
        setCategory("General");
        setCooldown(5);
        setExample("/wiki query:Legoshi");
        addOption(new OptionData(OptionType.STRING, "query", "The article to search for", true, true));
    }

    @Override
    public List<String> autocomplete(BunnyHub client, CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("query"))
            return List.of();

        return WikiService.suggest(event.getFocusedOption().getValue());
    }

    @Override
    public void execute(BunnyHub client, CommandContext ctx) {
        String query = ctx.getString("query");

        if (query == null || query.isBlank()) {
            ctx.reply(WikiEmbeds.noQuery(), true);
            return;
        }

        ctx.defer();

        Article article = WikiService.lookup(query);

        if (article == null) {
            ctx.reply(WikiEmbeds.notFound(query), true);
            return;
        }

        ctx.reply(WikiEmbeds.article(article), List.of(ActionRow.of(WikiEmbeds.readButton(article))));
    }
}
