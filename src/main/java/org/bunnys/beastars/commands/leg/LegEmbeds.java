package org.bunnys.beastars.commands.leg;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.beastars.database.LegData;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Timestamps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every embed the Leg feature can produce.
 *
 * <h2>House style</h2>
 * <ul>
 *   <li><b>Titles, field names, footers</b> - plain text. No Unicode emoji (design
 *       system) and no custom emoji (Discord will not render them there).</li>
 *   <li><b>Descriptions and field values</b> - lead with a {@link BeastarsEmoji}.</li>
 *   <li><b>Colour</b> - {@link AppDesign.ColorCodes#DEFAULT} throughout, with
 *       {@link AppDesign.ColorCodes#ERROR_RED} reserved for genuine failures and
 *       denials so red still means something.</li>
 * </ul>
 */
public final class LegEmbeds {

    /** Tag appended to log lines that came from an admin editing counts directly. */
    public static final String ADMIN_TAG = "`[ADMIN ADJUSTMENT]`";

    private LegEmbeds() {}

    private static EmbedBuilder base(String title) {
        return new EmbedBuilder()
                .setTitle(title)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now());
    }

    private static EmbedBuilder failure(String title) {
        return new EmbedBuilder()
                .setTitle(title)
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now());
    }

    /** The one error-embed shape for the whole feature. */
    public static MessageEmbed error(String title, String description) {
        return failure(title).setDescription(BeastarsEmoji.FAILURE + " " + description).build();
    }

    /** A rule said no. Distinct from {@link #error} so denials don't read as crashes. */
    public static MessageEmbed denied(String title, String description) {
        return failure(title).setDescription(BeastarsEmoji.DENIED + " " + description).build();
    }

    // ------------------------------------------------------------------
    // Offering
    // ------------------------------------------------------------------

    /**
     * Names the sender in the title of every stage of an offer.
     *
     * <p>These sit in a public channel, often several at once, and "Confirm Your
     * Sacrifice" says nothing about whose sacrifice it is - a reader scrolling back
     * could not tell two concurrent offers apart, and neither could the person being
     * asked to confirm. A title is plain text, so the name goes in unformatted.
     */
    private static String by(User sender) {
        return " by " + sender.getEffectiveName();
    }

    public static MessageEmbed offerConfirmation(User sender, User receiver) {
        return base("Sacrifice Offer" + by(sender))
                .setDescription(BeastarsEmoji.CONFIRM + " " + sender.getAsMention()
                        + ", are you sure you want to surrender a leg to "
                        + receiver.getAsMention() + "?\n*This action is permanent.*")
                .build();
    }

    public static MessageEmbed offerCancelled(User sender) {
        return failure("Sacrifice Cancelled" + by(sender))
                .setDescription(BeastarsEmoji.CANCELLED + " " + sender.getAsMention()
                        + " kept their leg. This time.")
                .build();
    }

    /**
     * Both parties are mentioned rather than named.
     *
     * <p>A mention renders as the member's current server nickname and stays a live link
     * to them, where a copied display name is a snapshot that goes stale the moment
     * somebody changes it. Mentions inside an embed do not ping - Discord only notifies
     * on message content - so this reads better without pestering anyone.
     */
    public static MessageEmbed offerAccepted(User sender, User receiver, int legsRemaining) {
        return base("A Sacrifice Made" + by(sender))
                .setDescription(BeastarsEmoji.SACRIFICE + " " + sender.getAsMention()
                        + " has surrendered a leg to " + receiver.getAsMention() + ".\n\n"
                        + BeastarsEmoji.APPETITE + " *" + sender.getAsMention() + " now has **"
                        + Math.max(0, legsRemaining) + "** leg(s) remaining.*")
                .build();
    }

    /** Maps a service outcome onto the right embed, so no call site re-derives the copy. */
    public static MessageEmbed offerFailure(LegService.OfferStatus status) {
        return switch (status) {
            case SELF_TARGET -> failure("Instinct Suppressed")
                    .setDescription(BeastarsEmoji.SELF_TARGET + " You cannot offer a leg to yourself.")
                    .build();
            case BOT_TARGET -> failure("Inorganic Target")
                    .setDescription(BeastarsEmoji.BOT_TARGET + " Machines have no taste for flesh.")
                    .build();
            case BANNED -> denied("Blacklisted",
                    "Gouhin has banned you from the Black Market. You cannot participate in the flesh economy.");
            case EXHAUSTED -> failure("Flesh Exhausted")
                    .setDescription(BeastarsEmoji.EXHAUSTED
                            + " You have already sacrificed both of your legs. You have no more flesh to give.")
                    .build();
            case DB_ERROR -> error("Black Market Unreachable",
                    "The ledger could not be updated. Please try again in a moment.");
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS is not a failure state");
        };
    }

    // ------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------

    public static MessageEmbed statsOverview(User target, LegData data) {
        int legsLeft = Math.max(0, LegService.MAX_LEGS - data.getLegsGiven());

        return base(target.getEffectiveName() + "'s Anatomy")
                .setDescription(BeastarsEmoji.ANATOMY + " Status: **" + rank(data) + "**")
                .addField("Current Legs", "**" + legsLeft + " / " + LegService.MAX_LEGS + "**", true)
                .addField("Flesh Surrendered", "**" + data.getLegsGiven() + "**", true)
                .addField("Flesh Consumed", "**" + data.getLegsReceived() + "**", true)
                .setThumbnail(target.getEffectiveAvatarUrl())
                .build();
    }

    /**
     * The flavour title shown on a profile.
     *
     * <p>Consumption outranks sacrifice - a carnivore who has eaten is defined by that,
     * not by what they gave away. Preserved verbatim from the original cascade so no
     * member's title changed under the refactor.
     */
    private static String rank(LegData data) {
        if (data.getLegsReceived() >= 5) return "Apex Predator";
        if (data.getLegsReceived() >= 1) return "The Awakened Carnivore";
        if (data.getLegsGiven() > 2) return "The Amputee";
        if (data.getLegsGiven() == 2) return "The Red Deer";
        if (data.getLegsGiven() == 1) return "The Noble Herbivore";
        return "The Innocent";
    }

    /**
     * The interaction log.
     *
     * <p>Manual admin adjustments sit in the log alongside real trades, tagged
     * {@value #ADMIN_TAG}, so a reader following it top to bottom sees the full account
     * of a profile and exactly which parts a human typed in.
     *
     * @param page requested page; clamped to the available range.
     */
    public static HistoryPage statsHistory(User target, LegData data, int page) {
        Map<String, Long> given = tally(data.getGivenTo());
        Map<String, Long> received = tally(data.getReceivedFrom());

        long adminGiven = given.getOrDefault(LegService.ADMIN_MARKER, 0L);
        long adminReceived = received.getOrDefault(LegService.ADMIN_MARKER, 0L);

        // Counted off the history arrays, not the counters, so legacy documents whose
        // two never quite agreed still render a coherent log.
        long realGiven = data.getGivenTo().size() - adminGiven;
        long realReceived = data.getReceivedFrom().size() - adminReceived;

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> entry : given.entrySet())
            if (!LegService.ADMIN_MARKER.equals(entry.getKey()))
                lines.add("• **Gave** " + entry.getValue() + "x to <@" + entry.getKey() + ">");

        for (Map.Entry<String, Long> entry : received.entrySet())
            if (!LegService.ADMIN_MARKER.equals(entry.getKey()))
                lines.add("• **Received** " + entry.getValue() + "x from <@" + entry.getKey() + ">");

        // Tagged, and last, so a page of real interactions is never pushed off by an edit.
        if (adminGiven > 0)
            lines.add("• **Gave** " + adminGiven + "x " + ADMIN_TAG);
        if (adminReceived > 0)
            lines.add("• **Received** " + adminReceived + "x " + ADMIN_TAG);

        int maxPages = Math.max(1, (int) Math.ceil((double) lines.size() / LegService.HISTORY_PAGE_SIZE));
        int currentPage = Math.max(1, Math.min(page, maxPages));
        int from = (currentPage - 1) * LegService.HISTORY_PAGE_SIZE;
        int to = Math.min(from + LegService.HISTORY_PAGE_SIZE, lines.size());

        StringBuilder description = new StringBuilder()
                .append(BeastarsEmoji.HISTORY).append(" ").append(target.getAsMention()).append(" ")
                .append(realGiven <= 0 ? "has not given any legs" : "has given " + realGiven + " leg(s)")
                .append(" and ")
                .append(realReceived <= 0 ? "has not received any legs" : "has received " + realReceived + " leg(s)")
                .append(".\n\n").append(BeastarsEmoji.LOG).append(" **Interaction Log**\n");

        if (lines.isEmpty())
            description.append(BeastarsEmoji.EMPTY).append(" *No interactions recorded yet.*\n");
        else
            for (int i = from; i < to; i++)
                description.append(lines.get(i)).append("\n");

        MessageEmbed embed = base("Interaction History: " + target.getEffectiveName())
                .setDescription(description.toString())
                .setFooter("Page " + currentPage + " of " + maxPages)
                .setThumbnail(target.getEffectiveAvatarUrl())
                .build();

        return new HistoryPage(embed, currentPage, maxPages);
    }

    public record HistoryPage(MessageEmbed embed, int page, int maxPages) {}

    /** Counts occurrences, preserving insertion order so the log reads chronologically. */
    private static Map<String, Long> tally(List<String> ids) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String id : ids)
            counts.merge(id, 1L, Long::sum);
        return counts;
    }

    // ------------------------------------------------------------------
    // Leaderboard
    // ------------------------------------------------------------------

    /**
     * The leaderboard, optionally narrowed to a set of roles.
     *
     * <p>Ranks are the ones from the whole board rather than renumbered 1..N, so a
     * filtered view still tells you where somebody stands in the guild. The header names
     * the filter so nobody mistakes a partial list for the full one.
     *
     * <p>Says plainly whether it is a pinned snapshot or a live read. The freshness line
     * is Discord timestamp markup rather than a rendered duration, so a board left open
     * for an hour reports an hour rather than whatever it said when it was posted. It goes
     * in the description because a footer will not render markup.
     *
     * @param filterRoleIds the roles in force, empty when showing everyone
     * @param snapshotAtEpochSeconds when the pinned ranking was taken; ignored when live
     */
    public static MessageEmbed leaderboard(LegService.LeaderboardPage page, List<String> filterRoleIds,
                                           boolean frozen, long snapshotAtEpochSeconds) {
        return render(page, filterRoleIds, frozen, snapshotAtEpochSeconds);
    }

    private static MessageEmbed render(LegService.LeaderboardPage page, List<String> filterRoleIds,
                                       boolean frozen, long snapshotAtEpochSeconds) {
        boolean filtered = filterRoleIds != null && !filterRoleIds.isEmpty();

        if (page.entries().isEmpty())
            return base("Black Market Apex Predators")
                    .setDescription(filtered
                            ? BeastarsEmoji.EMPTY + " Nobody with " + roleMentions(filterRoleIds)
                                    + " has eaten yet."
                            : BeastarsEmoji.EMPTY + " No flesh has been consumed yet.")
                    .build();

        StringBuilder body = new StringBuilder();

        if (filtered)
            body.append(BeastarsEmoji.ROLES).append(" *Showing only ")
                    .append(roleMentions(filterRoleIds)).append(".*\n")
                    .append(BeastarsEmoji.APEX).append(" *Ranks are their standing across the whole server.*\n\n");
        else if (page.highlightUserId() != null && !page.highlightPresent())
            body.append(BeastarsEmoji.EMPTY)
                    .append(" *You are not on the Black Market leaderboard yet!*\n\n");
        else
            body.append(BeastarsEmoji.APEX).append(" *The ones who ate well.*\n\n");

        for (LegService.RankedEntry entry : page.entries()) {
            body.append(medal(entry.rank())).append("**#").append(entry.rank())
                    .append("** - <@").append(entry.userId()).append("> : ")
                    .append(entry.legsReceived()).append(" Legs");
            if (entry.userId().equals(page.highlightUserId()))
                body.append(" ").append(BeastarsEmoji.YOU).append(" **(You)**");
            body.append("\n");
        }

        body.append("\n").append(freshness(frozen, snapshotAtEpochSeconds));

        return base("Black Market Apex Predators")
                .setDescription(body.toString())
                .setFooter("Page " + page.page() + " of " + page.maxPages())
                .build();
    }

    /**
     * Tells the reader whether the numbers in front of them can move.
     *
     * <p>Worth a line of its own. A frozen board that says nothing looks like a live board
     * that has stopped working, and a live board that says nothing gives no warning that
     * the row you were about to click may have moved.
     */
    private static String freshness(boolean frozen, long snapshotAtEpochSeconds) {
        if (!frozen)
            return BeastarsEmoji.APEX + " *Updating live. Ranks may shift between pages.*";

        return BeastarsEmoji.EXPIRED + " *Frozen as of "
                + Timestamps.relative(snapshotAtEpochSeconds)
                + ". Press Update for the latest.*";
    }

    private static String roleMentions(List<String> roleIds) {
        StringBuilder text = new StringBuilder();
        for (String id : roleIds) {
            if (!text.isEmpty())
                text.append(" or ");
            text.append("<@&").append(id).append(">");
        }
        return text.toString();
    }

    /**
     * The role filter needs a member list this bot does not keep.
     *
     * <p>Says what is missing and who can fix it, rather than showing a list that quietly
     * omits everyone Discord has not mentioned to us recently.
     */
    public static MessageEmbed filterUnavailable(Collection<String> developerIds) {
        return failure("Cannot Filter Yet")
                .setDescription(BeastarsEmoji.FAILURE
                        + " I cannot see the full member list, so filtering by role would"
                        + " leave people out without saying so.\n\n"
                        + "The bot needs the **Server Members Intent** enabled in the Discord"
                        + " Developer Portal.\n" + notifyDevelopers(developerIds))
                .build();
    }

    /**
     * Names the people who can actually fix this.
     *
     * <p>"Ask whoever runs the bot" is advice nobody can act on - a member reading it has
     * no way to know who that is. Mentioning them turns the message into the report.
     */
    static String notifyDevelopers(Collection<String> developerIds) {
        if (developerIds == null || developerIds.isEmpty())
            return "Please report this to the bot's developers.";

        StringBuilder mentions = new StringBuilder();
        for (String id : developerIds) {
            if (!mentions.isEmpty())
                mentions.append(" ");
            mentions.append("<@").append(id).append(">");
        }

        return "Please let " + mentions + " know.";
    }

    private static String medal(int rank) {
        return switch (rank) {
            case 1 -> BeastarsEmoji.RANK_FIRST + " ";
            case 2 -> BeastarsEmoji.RANK_SECOND + " ";
            case 3 -> BeastarsEmoji.RANK_THIRD + " ";
            default -> "";
        };
    }
}
