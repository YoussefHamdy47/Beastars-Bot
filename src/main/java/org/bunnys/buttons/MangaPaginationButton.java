package org.bunnys.buttons;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.beastars.commands.manga.MangaComponents;
import org.bunnys.beastars.commands.manga.MangaComponents.Session;
import org.bunnys.beastars.commands.manga.MangaEmbeds;
import org.bunnys.beastars.commands.manga.MangaService;
import org.bunnys.beastars.commands.manga.MangaService.PageResult;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;

/**
 * Page turning and session ending on an open manga reader.
 *
 * <p>ID parsing and control rebuilding both moved to {@link MangaComponents}; this is
 * now ownership check, expiry check, fetch, render.
 */
public class MangaPaginationButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return MangaComponents.NAV_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        Session session = MangaComponents.parseNavigation(args);
        if (session == null)
            return;

        if (!session.ownedBy(event.getUser().getId())) {
            event.replyEmbeds(MangaEmbeds.notYourSession()).setEphemeral(true).queue();
            return;
        }

        if (session.expired()) {
            event.deferEdit().queue();
            event.getHook().editOriginalComponents().queue(null, e -> {});
            event.getHook().sendMessageEmbeds(MangaEmbeds.sessionExpired())
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        event.deferEdit().queue();

        if (MangaComponents.ACTION_END.equalsIgnoreCase(session.action())) {
            event.getHook().editOriginalComponents().queue(null, e -> {});
            return;
        }

        PageResult result = MangaService.fetchPage(session.ref(), session.page());

        if (result.failed()) {
            event.getHook().sendMessageEmbeds(MangaEmbeds.error("Page Unavailable", result.error()))
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        // A fresh stamp on every turn, so an active reader never times out mid-read.
        event.getHook()
                .editOriginalEmbeds(MangaEmbeds.page(result, session.action(),
                        event.getUser().getEffectiveName()))
                .setComponents(MangaComponents.navigation(session.ref(), result.page(), result.maxPages(),
                        System.currentTimeMillis(), session.userId()))
                .setFiles(FileUpload.fromData(result.image(), MangaEmbeds.PAGE_ATTACHMENT))
                .queue(null, e -> {});
    }
}
