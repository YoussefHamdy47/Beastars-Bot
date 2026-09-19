package org.bunnys.buttons;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import org.bunnys.beastars.commands.manga.MangaComponents;
import org.bunnys.beastars.commands.manga.MangaComponents.MangaJumpFields;
import org.bunnys.beastars.commands.manga.MangaComponents.Session;
import org.bunnys.beastars.commands.manga.MangaEmbeds;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.router.buttons.BunnyButton;

/** Opens the "jump to page" form for an open reader. */
public class MangaJumpButton extends BunnyButton {

    @Override
    public String getPrefix() {
        return MangaComponents.JUMP_PREFIX;
    }

    @Override
    public void execute(BunnyHub client, ButtonInteractionEvent event, String[] args) {
        Session session = MangaComponents.parseJump(args);
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

        TextInput pageInput = TextInput.create(MangaJumpFields.INPUT_PAGE, TextInputStyle.SHORT)
                .setPlaceholder("e.g. 5")
                .setMinLength(1)
                .setMaxLength(4)
                .setRequired(true)
                .build();

        Modal modal = Modal.create(
                        MangaComponents.jumpModalId(session.ref(), System.currentTimeMillis(), session.userId()),
                        "Jump to Page")
                .addComponents(Label.of("Enter Page Number", pageInput))
                .build();

        event.replyModal(modal).queue();
    }
}
