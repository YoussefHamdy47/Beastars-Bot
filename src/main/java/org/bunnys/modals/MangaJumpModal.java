package org.bunnys.modals;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bunnys.beastars.commands.manga.MangaComponents;
import org.bunnys.beastars.commands.manga.MangaComponents.MangaJumpFields;
import org.bunnys.beastars.commands.manga.MangaComponents.Session;
import org.bunnys.beastars.commands.manga.MangaEmbeds;
import org.bunnys.beastars.commands.manga.MangaService;
import org.bunnys.beastars.commands.manga.MangaService.PageResult;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.modals.BunnyModal;

/** Applies a "jump to page" submission to an open reader. */
public class MangaJumpModal extends BunnyModal {

    @Override
    public String getPrefix() {
        return MangaJumpFields.MODAL_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ModalInteractionEvent event, String[] args) {
        Session session = MangaComponents.parseJump(args);
        if (session == null) {
            event.replyEmbeds(MangaEmbeds.error("Broken Control",
                    "This reader's controls are malformed. Run `/manga` to start again."))
                    .setEphemeral(true).queue();
            return;
        }

        if (!session.ownedBy(event.getUser().getId())) {
            event.replyEmbeds(MangaEmbeds.notYourSession()).setEphemeral(true).queue();
            return;
        }

        Integer page = parsePage(event);
        if (page == null || page < 1) {
            event.replyEmbeds(MangaEmbeds.error("Invalid Page",
                    "Enter a whole page number of 1 or greater.")).setEphemeral(true).queue();
            return;
        }

        event.deferEdit().queue();

        PageResult result = MangaService.fetchPage(session.ref(), page);

        if (result.failed()) {
            event.getHook().sendMessageEmbeds(MangaEmbeds.error("Page Unavailable", result.error()))
                    .setEphemeral(true).queue(null, e -> {});
            return;
        }

        event.getHook()
                .editOriginalEmbeds(MangaEmbeds.page(result, "Jump", event.getUser().getEffectiveName()))
                .setComponents(MangaComponents.navigation(session.ref(), result.page(), result.maxPages(),
                        System.currentTimeMillis(), session.userId()))
                .setFiles(FileUpload.fromData(result.image(), MangaEmbeds.PAGE_ATTACHMENT))
                .queue(null, e -> {});
    }

    private static Integer parsePage(ModalInteractionEvent event) {
        var mapping = event.getValue(MangaJumpFields.INPUT_PAGE);
        if (mapping == null)
            return null;
        try {
            return Integer.valueOf(mapping.getAsString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
