package org.bunnys.beastars.commands.wiki;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.wiki.WikiService.Article;
import org.bunnys.utils.AppDesign;

import java.time.Instant;

/** Every embed the wiki lookup can produce. */
public final class WikiEmbeds {

    private WikiEmbeds() {}

    public static MessageEmbed article(Article article) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(article.title(), article.url())
                .setDescription(BeastarsEmoji.WIKI + " " + article.summary())
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Beastars Fandom Wiki")
                .setTimestamp(Instant.now());

        if (article.thumbnailUrl() != null)
            embed.setThumbnail(article.thumbnailUrl());

        return embed.build();
    }

    public static MessageEmbed notFound(String query) {
        return new EmbedBuilder()
                .setTitle("No Article Found")
                .setDescription(BeastarsEmoji.NOT_FOUND
                        + " Nothing on the Beastars Wiki matches `" + query + "`.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    public static MessageEmbed noQuery() {
        return new EmbedBuilder()
                .setTitle("What Are You Looking For")
                .setDescription(BeastarsEmoji.NOT_FOUND
                        + " Give me something to search for, for example `query:Legoshi`.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    public static Button readButton(Article article) {
        return Button.link(article.url(), "Read Article");
    }
}
