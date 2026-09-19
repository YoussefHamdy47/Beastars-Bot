package org.bunnys.beastars;

import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.bunnys.utils.AppDesign;

/**
 * Semantic aliases over the Beastars application-emoji block in {@link AppDesign}.
 *
 * <p>Feature code never references a raw {@code <:Name:id>} string or an
 * {@code AppDesign.Emojis.*} constant directly - it asks for the <em>meaning</em>
 * ({@link #SUCCESS}, {@link #DENIED}, ...). Re-theming the whole bot is then a
 * one-file edit here.
 *
 * <p><b>Placement rule.</b> Discord renders custom emoji in embed
 * <em>descriptions</em> and <em>field values</em> only - never in titles, field
 * names, author lines or footers, where they degrade to raw {@code <:Name:id>}
 * text. Buttons render them too, via {@code withEmoji}.
 *
 * <p>Combined with the house rule that no Unicode emoji appear anywhere, that
 * gives one unambiguous layout:
 * <ul>
 *   <li><b>Titles, field names, footers</b> - plain text, no emoji of any kind.</li>
 *   <li><b>Descriptions and field values</b> - Beastars emoji, from here.</li>
 *   <li><b>Buttons</b> - Beastars emoji via {@link #button(String)}, plain label.</li>
 * </ul>
 *
 * <p><b>These fields are deliberately not {@code final}.</b> Application emoji IDs differ
 * between the test and production applications even when the names match, so
 * {@link org.bunnys.utils.EmojiRegistry} re-points every one of them at the running
 * application on startup. A {@code static final String} would be inlined into each call
 * site at compile time and could never be corrected. The values below are the fallback
 * used when that sync cannot run.
 */
public final class BeastarsEmoji {

    private BeastarsEmoji() {}

    // --- Outcomes -----------------------------------------------------------
    /** An admin/user action completed. */
    public static String SUCCESS = AppDesign.Emojis.GOUHIN_THUMBS_UP;
    /** An unexpected system or database failure. */
    public static String FAILURE = AppDesign.Emojis.LEGOSHI_DOOMED;
    /** Permission denied - Gouhin is the enforcer of the black market. */
    public static String DENIED = AppDesign.Emojis.GOUHIN_ANGRY;
    /** A destructive action is awaiting confirmation. */
    public static String CONFIRM = AppDesign.Emojis.LEGOSHI_DOUBT;
    /** The user backed out. */
    public static String CANCELLED = AppDesign.Emojis.HARU_OOPS;
    /** Nothing to show. */
    public static String EMPTY = AppDesign.Emojis.LEGOSHI_SPEECHLESS;

    // --- Leg economy --------------------------------------------------------
    /** A leg changed hands. */
    public static String SACRIFICE = AppDesign.Emojis.LOUIS_SNACK;
    /** Someone is eyeing a meal. */
    public static String APPETITE = AppDesign.Emojis.LEGOSHI_DROOL;
    /** Out of legs to give. */
    public static String EXHAUSTED = AppDesign.Emojis.LEGOSHI_A_TIRED;
    /** Anatomy / stat sheets. */
    public static String ANATOMY = AppDesign.Emojis.LEGOSHI_STUDYING;
    /** Interaction history - a record of who ate whom. */
    public static String HISTORY = AppDesign.Emojis.GOSHA_MUNCH;
    /** Leaderboard - apex predators. */
    public static String APEX = AppDesign.Emojis.LEGOSHI_ALPHA;
    /** Tried to feed yourself. */
    public static String SELF_TARGET = AppDesign.Emojis.HARU_BRUH;
    /** Tried to feed a bot. */
    public static String BOT_TARGET = AppDesign.Emojis.JACK_WAT;

    // --- Administration -----------------------------------------------------
    /** The admin dashboard itself. */
    public static String PANEL = AppDesign.Emojis.BEASTARS_B;
    /** Role configuration. */
    public static String ROLES = AppDesign.Emojis.JACK_SENSEI;
    /** New-member wait period. */
    public static String TIMER = AppDesign.Emojis.LEGOSHI_SLEEPY;
    /** A profile was edited. */
    public static String EDIT = AppDesign.Emojis.LEGOSHI_LIGHTBULB;
    /** A profile was zeroed out. */
    public static String RESET = AppDesign.Emojis.LEGOSHI_A_OOPS;
    /** A profile was deleted. */
    public static String DELETE = AppDesign.Emojis.LEGOSHI_WHAT_HAVE_IDONE;
    /** A user was banned from the economy. */
    public static String BANNED = AppDesign.Emojis.GOUHIN_DEATH_TO_ALL;
    /** A user was unbanned. */
    public static String UNBANNED = AppDesign.Emojis.HARU_A_RELIEF;
    /** The whole guild economy was wiped. */
    public static String NUKE = AppDesign.Emojis.MELON_LUNATIC;
    /** An admin bypassed a restriction by virtue of their permissions. */
    public static String OVERRIDE = AppDesign.Emojis.GOUHIN_FLEX;

    // --- Manga --------------------------------------------------------------
    /** A manga page. */
    public static String PAGE = AppDesign.Emojis.LEGOSHI_STUDYING;
    /** A randomly drawn page. */
    public static String RANDOM = AppDesign.Emojis.PARU_PLOT_TWIST;
    /** Reading session ended. */
    public static String SESSION_END = AppDesign.Emojis.LEGOSHI_BYEBYE;
    /** Someone else's reading session. */
    public static String NOT_YOURS = AppDesign.Emojis.JACK_HMPF;
    /** Session timed out. */
    public static String EXPIRED = AppDesign.Emojis.LEGOSHI_SLEEPY;

    // --- Images / wiki ------------------------------------------------------
    /** A saved image shortcut. */
    public static String IMAGE = AppDesign.Emojis.HARU_PLUSHIE;
    /** The image shortcut catalogue. */
    public static String GALLERY = AppDesign.Emojis.LEGOSHI_A_HEADPHONES;
    /** A random album pull. */
    public static String ALBUM = AppDesign.Emojis.LEGOSHI_A_POG;
    /** A wiki article. */
    public static String WIKI = AppDesign.Emojis.LEGOSHI_LISTENING;
    /** Wiki search found nothing. */
    public static String NOT_FOUND = AppDesign.Emojis.LEGOSHI_CONFUSED;
    /** The help menu. */
    public static String HELP = AppDesign.Emojis.JACK_QUESTION;
    /** A command category heading in the help menu. */
    public static String CATEGORY = AppDesign.Emojis.BEANSTAR;
    /** A worked example on a help detail card. */
    public static String EXAMPLE = AppDesign.Emojis.JACK_THINK;
    /** One parameter on a help detail card. */
    public static String PARAMETER = AppDesign.Emojis.LEGOSHI_STUDYING;
    /** Slash-command usage line. */
    public static String SLASH = AppDesign.Emojis.LEGOSHI_LIGHTBULB;
    /** Mention-command usage line. */
    public static String MENTION = AppDesign.Emojis.JACK_PINGED;

    // --- Diagnostics and profiles -------------------------------------------
    /** Round-trip latency. */
    public static String LATENCY = AppDesign.Emojis.JACK_NEW_PING;
    /** How long the bot has been awake. */
    public static String UPTIME = AppDesign.Emojis.LEGOSHI_ZEN;
    /** A user profile. */
    public static String PROFILE = AppDesign.Emojis.JACK_PHONE_CHECKING;
    /** A guild profile. */
    public static String SERVER = AppDesign.Emojis.BEANSTAR;
    /** The guild owner. */
    public static String OWNER = AppDesign.Emojis.COOL_BOSS;
    /** A population count. */
    public static String MEMBERS = AppDesign.Emojis.JACK_A_SMILE;
    /** When an account or guild came into existence. */
    public static String CREATED = AppDesign.Emojis.LEGOSHI_BABY;
    /** When someone arrived in the guild. */
    public static String JOINED = AppDesign.Emojis.JACK_WAVE;
    /** Channels in a guild. */
    public static String CHANNELS = AppDesign.Emojis.LEGOSHI_LISTENING;
    /** Nitro boosts. */
    public static String BOOSTS = AppDesign.Emojis.HARU_A_3_LVLS;

    // --- Ranks and status ---------------------------------------------------
    /** First place. */
    public static String RANK_FIRST = AppDesign.Emojis.MCCOOL_LOUIS;
    /** Second place. */
    public static String RANK_SECOND = AppDesign.Emojis.MCCOOL_LEGOSHI;
    /** Third place. */
    public static String RANK_THIRD = AppDesign.Emojis.MCCOOL_HARU;
    /** Marks the reader's own row on a leaderboard. */
    public static String YOU = AppDesign.Emojis.LEGOSHI_A_POG;
    /** A setting that is switched on. */
    public static String ENABLED = AppDesign.Emojis.MICE_YES;
    /** A setting that is switched off. */
    public static String DISABLED = AppDesign.Emojis.MICE_NO;
    /** The interaction log heading. */
    public static String LOG = AppDesign.Emojis.LEGOSHI_A_CHEW;
    /** Roles permitted to take part. */
    public static String ROLE_ALLOWED = AppDesign.Emojis.HARU_A_WARD;
    /** Roles barred from taking part. */
    public static String ROLE_BANNED = AppDesign.Emojis.GOUHIN_DEATH_TO_ALL;
    /** Roles that skip the wait period. */
    public static String ROLE_BYPASS = AppDesign.Emojis.HARU_A_RUN;
    // --- Page navigation ----------------------------------------------------
    /** Jump to the first page. */
    public static String NAV_FIRST = AppDesign.Emojis.LEGOSHI_SLOWLY_BACKING_AWAY;
    /** Step back one page. */
    public static String NAV_PREVIOUS = AppDesign.Emojis.HARU_A_RUN;
    /** Step forward one page. */
    public static String NAV_NEXT = AppDesign.Emojis.LEGOSHI_A_RUN;
    /** Jump to the last page. */
    public static String NAV_LAST = AppDesign.Emojis.LEGOSHI_YAH_FAST;
    /** Open the category chooser. */
    public static String BROWSE = AppDesign.Emojis.LEGOSHI_LIGHTBULB;

    /**
     * Parses a formatted emoji for use on a {@link net.dv8tion.jda.api.components.buttons.Button}.
     *
     * <p>Returns {@code null} rather than throwing if the constant is ever
     * malformed - {@code Button.withEmoji} accepts null, so a bad emoji can
     * never take the feature down at class-init time.
     */
    public static Emoji button(String formatted) {
        try {
            return Emoji.fromFormatted(formatted);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
