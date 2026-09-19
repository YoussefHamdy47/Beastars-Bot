package org.bunnys.beastars.commands.manga;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.commands.manga.MangaService.MangaRef;

import java.util.List;

/**
 * The reader's navigation controls and the custom-id grammar behind them.
 *
 * <p>This nine-segment ID was previously assembled by hand with {@code String.format}
 * in four separate places - the slash command, the pagination button, the jump modal
 * and {@code /randompage} - each with its own copy of the disable rules. They had
 * already drifted: only one of the four attached the End Session control. Building and
 * parsing now happen here, so a reader started from any entry point behaves the same.
 *
 * <pre>
 *   manga:&lt;source&gt;:&lt;group&gt;:&lt;series&gt;:&lt;chapter&gt;:&lt;page&gt;:&lt;action&gt;:&lt;timestamp&gt;:&lt;userId&gt;
 *   manga_jump:&lt;source&gt;:&lt;group&gt;:&lt;series&gt;:&lt;chapter&gt;:&lt;timestamp&gt;:&lt;userId&gt;
 * </pre>
 */
public final class MangaComponents {

    public static final String NAV_PREFIX = "manga";
    public static final String JUMP_PREFIX = "manga_jump";

    public static final String ACTION_END = "End";

    /** A reader goes stale after five minutes of inactivity. */
    public static final long SESSION_TTL_MS = 300_000L;

    private static final int NAV_SEGMENTS = 9;
    private static final int JUMP_SEGMENTS = 7;

    private MangaComponents() {}

    /** Who owns a reader, and when it was last touched. */
    public record Session(MangaRef ref, int page, String action, long timestamp, String userId) {

        public boolean ownedBy(String candidateId) {
            return userId.equals(candidateId);
        }

        public boolean expired() {
            return System.currentTimeMillis() - timestamp > SESSION_TTL_MS;
        }
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * Navigation row plus the End Session control.
     *
     * @param timestamp stamp for the refreshed session; callers pass "now" so each
     *                  interaction renews the five-minute window.
     */
    public static List<ActionRow> navigation(MangaRef ref, int page, int maxPages,
                                             long timestamp, String userId) {
        String base = NAV_PREFIX + ":" + ref.source() + ":" + ref.group() + ":" + ref.series()
                + ":" + ref.chapter() + ":";
        String tail = ":" + timestamp + ":" + userId;

        boolean atStart = page <= 1;
        boolean atEnd = page >= maxPages;

        ActionRow navigation = ActionRow.of(
                Button.secondary(base + 1 + ":First" + tail, "<<").withDisabled(atStart),
                Button.secondary(base + Math.max(1, page - 1) + ":Prev" + tail, "<").withDisabled(atStart),
                Button.secondary(jumpId(ref, timestamp, userId), "Jump"),
                Button.secondary(base + (page + 1) + ":Next" + tail, ">").withDisabled(atEnd),
                Button.secondary(base + Math.max(1, maxPages) + ":Last" + tail, ">>").withDisabled(atEnd));

        ActionRow controls = ActionRow.of(
                Button.danger(base + page + ":" + ACTION_END + tail, "End Session")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.SESSION_END)));

        return List.of(navigation, controls);
    }

    private static String jumpId(MangaRef ref, long timestamp, String userId) {
        return JUMP_PREFIX + ":" + ref.source() + ":" + ref.group() + ":" + ref.series()
                + ":" + ref.chapter() + ":" + timestamp + ":" + userId;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Parses a navigation button id, or null if it is malformed. */
    public static Session parseNavigation(String[] args) {
        if (args.length < NAV_SEGMENTS)
            return null;
        try {
            MangaRef ref = new MangaRef(args[3], args[1], args[2], Integer.parseInt(args[4]));
            return new Session(ref, Integer.parseInt(args[5]), args[6], Long.parseLong(args[7]), args[8]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses a jump button or jump modal id, or null if it is malformed. */
    public static Session parseJump(String[] args) {
        if (args.length < JUMP_SEGMENTS)
            return null;
        try {
            MangaRef ref = new MangaRef(args[3], args[1], args[2], Integer.parseInt(args[4]));
            return new Session(ref, 1, "Jump", Long.parseLong(args[5]), args[6]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The modal id the Jump button opens, carrying the session forward. */
    public static String jumpModalId(MangaRef ref, long timestamp, String userId) {
        return MangaJumpFields.MODAL_PREFIX + ":" + ref.source() + ":" + ref.group() + ":"
                + ref.series() + ":" + ref.chapter() + ":" + timestamp + ":" + userId;
    }

    /** Field and prefix names for the jump modal, shared with its handler. */
    public static final class MangaJumpFields {
        public static final String MODAL_PREFIX = "manga_jump_modal";
        public static final String INPUT_PAGE = "page_number";

        private MangaJumpFields() {}
    }
}
