package org.bunnys.beastars.help;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.components.label.Label;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.handler.commands.BunnyCommand;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.BunnySubcommandGroup;
import org.bunnys.utils.AppDesign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the help menu.
 *
 * <p>Built around the fact that there are now two ways to call everything. Each command
 * documents <b>both</b> forms explicitly, with real argument names pulled from the same
 * {@link OptionData} the slash command registers and the mention parser reads - so the
 * printed syntax cannot drift from what actually works.
 *
 * <h2>Two levels</h2>
 * <ul>
 *   <li>The <b>category page</b> is the index: one category per page, every command in
 *       it with its usage lines. Good for browsing, necessarily terse.</li>
 *   <li>The <b>detail card</b> is one command in full: both invocation forms, a worked
 *       example, and every parameter with its type, whether it is required, and the
 *       exact values it accepts. Reached from the picker menu that sits under every
 *       page, or straight away via {@code /help command:manga}.</li>
 * </ul>
 *
 * <p>The detail card exists because the index cannot answer "what do I actually type
 * for source?" without becoming unreadable. Choices are declared on the {@link OptionData}
 * already, so listing them costs nothing and cannot go stale.
 */
public final class HelpMenuManager {

    /** Discord's ceiling on a select menu. Well above the command count, but not a law of nature. */
    private static final int MAX_PICKER_OPTIONS = 25;

    /**
     * Discord's ceilings, taken from JDA rather than copied.
     *
     * <p>These were hand-written numbers until an over-long field took the whole help
     * menu down. Reading them from {@link MessageEmbed} means the arithmetic below is
     * anchored to the same values JDA validates against, and cannot drift from them.
     */
    private static final int MAX_FIELD_VALUE = MessageEmbed.VALUE_MAX_LENGTH;
    private static final int MAX_FIELD_NAME = MessageEmbed.TITLE_MAX_LENGTH;

    /**
     * Total embed budget, held slightly under the real ceiling.
     *
     * <p>The footer and timestamp are added after the fields, so the last field cannot be
     * allowed to fill the embed exactly.
     */
    private static final int MAX_EMBED_TOTAL = MessageEmbed.EMBED_MAX_LENGTH_BOT - 200;

    private static final int MAX_OPTION_DESCRIPTION = 100;

    /** Leaves room for the usage, mention and example fields alongside the parameters. */
    private static final int MAX_DETAIL_FIELDS = 20;

    private HelpMenuManager() {}

    // ------------------------------------------------------------------
    // Category pages
    // ------------------------------------------------------------------

    /** Groups visible commands by category, alphabetically, commands sorted inside. */
    private static Map<String, List<BunnyCommand>> categorise(List<BunnyCommand> commands) {
        Map<String, List<BunnyCommand>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (BunnyCommand command : commands) {
            if (command.isDeveloperOnly())
                continue;
            grouped.computeIfAbsent(command.getCategory(), k -> new ArrayList<>()).add(command);
        }

        grouped.values().forEach(list -> list.sort(Comparator.comparing(BunnyCommand::getName)));
        return grouped;
    }

    /** Every command a normal user may see, sorted by name. The picker and lookups use this. */
    private static List<BunnyCommand> visible(List<BunnyCommand> commands) {
        List<BunnyCommand> shown = new ArrayList<>();
        for (BunnyCommand command : commands)
            if (!command.isDeveloperOnly())
                shown.add(command);

        shown.sort(Comparator.comparing(BunnyCommand::getName));
        return shown;
    }

    public static int getTotalPages(List<BunnyCommand> commands) {
        return Math.max(1, categorise(commands).size());
    }

    /**
     * @param botName   the bot's display name, used verbatim in the mention examples
     * @param pageNumber 1-indexed; clamped to the available range
     */
    public static MessageEmbed getHelpPage(String botName, String botAvatarUrl,
                                           List<BunnyCommand> commands, int pageNumber) {
        Map<String, List<BunnyCommand>> grouped = categorise(commands);
        List<String> categories = new ArrayList<>(grouped.keySet());

        int totalPages = Math.max(1, categories.size());
        int page = Math.max(1, Math.min(pageNumber, totalPages));

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now());

        if (categories.isEmpty()) {
            return embed.setTitle("Help")
                    .setDescription(BeastarsEmoji.EMPTY + " No commands are currently available.")
                    .setFooter("Page 1 of 1")
                    .build();
        }

        String category = categories.get(page - 1);
        List<BunnyCommand> inCategory = grouped.get(category);

        embed.setTitle(category + " Commands");
        if (botAvatarUrl != null)
            embed.setThumbnail(botAvatarUrl);

        // Two lines, not five. This preamble repeats on every page, so anything in it is
        // paid for once per category - the bracket legend and the mention syntax moved to
        // the detail card, which is where somebody is actually about to type something.
        embed.appendDescription(BeastarsEmoji.HELP + " `/command` or `@" + botName
                + " command`. Both work.\n"
                + BeastarsEmoji.SLASH + " Pick one below for its full usage and an example.\n\n");

        for (BunnyCommand command : inCategory)
            if (!field(embed, fieldName(command), fieldValue(command, botName), false)) {
                // The category is larger than one embed can hold. Say so rather than
                // silently listing a subset the reader has no way of knowing is partial.
                field(embed, "More in " + category, BeastarsEmoji.EMPTY
                        + " This category does not fit on one page. Use `/help command:name`"
                        + " for anything not listed.", false);
                break;
            }

        embed.setFooter("Page " + page + " of " + totalPages + "  •  " + category);
        return embed.build();
    }

    /** Field names cannot render custom emoji, so they stay plain. */
    private static String fieldName(BunnyCommand command) {
        return "/" + command.getName();
    }

    /**
     * One command, as an index entry rather than as documentation.
     *
     * <p>Two or three lines: what it does, and either how to call it or what its branches
     * are. Nothing more.
     *
     * <p>This used to print full option syntax for every branch in <em>both</em> invocation
     * forms, which put four lines on screen per subcommand - {@code /image} alone ran to
     * seventeen, and a category page to forty. That is the detail card's content, printed
     * on the index. The index only has to answer "what exists and roughly what is it for";
     * one click on the picker answers everything else, and the card has the room to do it
     * properly.
     */
    private static String fieldValue(BunnyCommand command, String botName) {
        StringBuilder body = new StringBuilder();

        if (command.getDescription() != null)
            body.append("*").append(command.getDescription()).append("*\n");

        if (command.hasBranches())
            body.append(BeastarsEmoji.CATEGORY).append(" ").append(branchNames(command));
        else
            // A command with no branches fits its whole usage on one line, so it may as
            // well carry it - that is often the only thing the reader came for.
            body.append(BeastarsEmoji.SLASH).append(" `/").append(command.getName())
                    .append(renderArgs(command.getOptions())).append("`");

        String rendered = body.toString();
        if (rendered.length() <= MAX_FIELD_VALUE)
            return rendered;

        // The budget is whatever the pointer does not consume. A hard-coded round number
        // is what broke this once: the pointer text grew, and the sum quietly crossed the
        // ceiling. Deriving it means the pointer can be reworded without doing sums.
        String pointer = "\n*...see `/help command:" + command.getName() + "`*";
        int budget = MAX_FIELD_VALUE - pointer.length();

        return budget <= 0 ? pointer : rendered.substring(0, budget) + pointer;
    }

    /**
     * The branch names on one line, as {@code get · add · remove · list}.
     *
     * <p>Enough to tell a reader whether the thing they want is in here, without spending
     * four lines each proving it. Groups contribute their own name rather than every
     * action inside them, so {@code /admin} reads as its five entry points instead of its
     * nine leaves.
     */
    private static String branchNames(BunnyCommand command) {
        List<String> names = new ArrayList<>(command.getSubcommands().keySet());
        names.addAll(command.getSubcommandGroups().keySet());

        return names.isEmpty() ? "*No branches.*" : "`" + String.join("` · `", names) + "`";
    }

    /**
     * Renders options as {@code name:<value>} / {@code name:[value]}.
     *
     * <p>Deliberately the same shape for both forms: it is exactly what the mention
     * parser accepts, and it mirrors how Discord displays slash options, so a user can
     * read one line and type either.
     */
    private static String renderArgs(List<OptionData> options) {
        if (options.isEmpty())
            return "";

        StringBuilder args = new StringBuilder();
        for (OptionData option : options)
            args.append(option.isRequired()
                    ? " " + option.getName() + ":<" + typeHint(option) + ">"
                    : " " + option.getName() + ":[" + typeHint(option) + "]");

        return args.toString();
    }

    private static String typeHint(OptionData option) {
        return switch (option.getType()) {
            case USER -> "@member";
            case ROLE -> "@role";
            case CHANNEL -> "#channel";
            case INTEGER, NUMBER -> "number";
            case BOOLEAN -> "true/false";
            default -> option.getChoices().isEmpty()
                    ? "text"
                    : option.getChoices().get(0).getAsString() + "|...";
        };
    }

    // ------------------------------------------------------------------
    // Detail card
    // ------------------------------------------------------------------

    /** The command with this name, or null when nothing visible matches. */
    public static BunnyCommand findCommand(List<BunnyCommand> commands, String name) {
        if (name == null || name.isBlank())
            return null;

        // Tolerant of what a user actually types: a stray slash, stray spaces, and the
        // full path when they paste `/manga source:MD` back in.
        String wanted = name.trim().toLowerCase();
        if (wanted.startsWith("/"))
            wanted = wanted.substring(1);

        int space = wanted.indexOf(' ');
        if (space > 0)
            wanted = wanted.substring(0, space);

        for (BunnyCommand command : visible(commands))
            if (command.getName().equalsIgnoreCase(wanted))
                return command;

        return null;
    }

    /** {@code /help command:} named something that is not a command. */
    public static MessageEmbed commandNotFound(String typed) {
        return new EmbedBuilder()
                .setTitle("No Such Command")
                .setDescription(BeastarsEmoji.NOT_FOUND + " There is no command called `" + typed
                        + "`. Run `/help` on its own and pick one from the menu.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    /** Every visible command name, for {@code /help command:} autocomplete. */
    public static List<String> suggest(List<BunnyCommand> commands) {
        return visible(commands).stream().map(BunnyCommand::getName).toList();
    }

    /**
     * One command in full: both invocation forms, a worked example, and every parameter.
     *
     * <p>A branched command documents each branch instead of a bare form, because there
     * is no bare form to document - {@code /info} on its own is not a thing you can run.
     */
    public static MessageEmbed getCommandDetail(String botName, String botAvatarUrl, BunnyCommand command) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("/" + command.getName())
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now());

        if (botAvatarUrl != null)
            embed.setThumbnail(botAvatarUrl);

        StringBuilder intro = new StringBuilder(BeastarsEmoji.HELP).append(" *")
                .append(command.getDescription() == null ? "No description." : command.getDescription())
                .append("*\n\nValues in `<angle brackets>` are required, `[square brackets]` optional.");

        if (!command.isMentionEnabled())
            intro.append("\n").append(BeastarsEmoji.MENTION)
                    .append(" This one is slash-only. It opens a form, and Discord allows those"
                            + " only from an interaction.");

        // Worth stating on the card rather than leaving people to discover it: the two
        // invocation forms are otherwise identical, and this is the one place they differ.
        BunnySubcommand fallback = command.defaultSubcommand();
        if (fallback != null)
            intro.append("\n").append(BeastarsEmoji.MENTION)
                    .append(" `@").append(botName).append(" ").append(command.getName())
                    .append("` on its own runs **").append(fallback.getName())
                    .append("**. The slash command always needs the subcommand named.");

        embed.setDescription(intro.toString());

        appendAliasField(embed, command, botName);

        if (command.hasBranches())
            appendBranchFields(embed, command, botName);
        else
            appendPlainFields(embed, command, botName);

        embed.setFooter(footer(command));
        return embed.build();
    }

    /**
     * Lists the command's shorthand names, when it has any.
     *
     * <p>Says plainly that they are mention-only. Discord has no alias for a slash
     * command, so a reader who saw `lb` listed here and then typed `/lb` would find
     * nothing, and would reasonably conclude the help menu was lying to them.
     */
    private static void appendAliasField(EmbedBuilder embed, BunnyCommand command, String botName) {
        List<String> aliases = command.getAliases();
        if (aliases.isEmpty())
            return;

        StringBuilder names = new StringBuilder(BeastarsEmoji.MENTION).append(" ");
        for (int i = 0; i < aliases.size(); i++) {
            if (i > 0)
                names.append("  ");
            names.append("`@").append(botName).append(" ").append(aliases.get(i)).append("`");
        }

        names.append("\n*Shorthand for the mention form only. The slash command is `/")
                .append(command.getName()).append("`.*");

        field(embed, "Also Known As", names.toString(), false);
    }

    private static void appendPlainFields(EmbedBuilder embed, BunnyCommand command, String botName) {
        String args = renderArgs(command.getOptions());

        field(embed, "Slash Usage",
                BeastarsEmoji.SLASH + " `/" + command.getName() + args + "`", false);

        field(embed, "Mention Usage", command.isMentionEnabled()
                ? BeastarsEmoji.MENTION + " `@" + botName + " " + command.getName() + args + "`"
                : BeastarsEmoji.MENTION + " *Not available. Use the slash command.*", false);

        field(embed, "Example", example(command.getExample(),
                command.getName(), command.getOptions(), botName, command.isMentionEnabled()), false);

        if (command.getOptions().isEmpty()) {
            field(embed, "Parameters", BeastarsEmoji.EMPTY + " This command takes no parameters.", false);
            return;
        }

        // Discord allows 25 options on a command and 25 fields on an embed, and three
        // of those fields are already spent above. Capping keeps the card renderable.
        List<OptionData> options = command.getOptions();
        int shown = Math.min(options.size(), MAX_DETAIL_FIELDS);

        for (int i = 0; i < shown; i++)
            field(embed, parameterName(options.get(i)), parameterValue(options.get(i)), false);

        int hidden = options.size() - shown;
        if (hidden > 0)
            field(embed, "More", BeastarsEmoji.EMPTY + " " + hidden
                    + " further parameters are not shown here.", false);
    }

    /**
     * One field per branch, each carrying its own usage, example and parameters.
     *
     * <p>Packed into a single field rather than spread across several, because Discord
     * allows 25 fields per embed and {@code /admin} alone would otherwise exhaust them.
     */
    private static void appendBranchFields(EmbedBuilder embed, BunnyCommand command, String botName) {
        // Flattened first so the overflow count is the real one, rather than however
        // many happened to be left in whichever loop hit the ceiling.
        List<BunnySubcommand> branches = new ArrayList<>();
        List<String> paths = new ArrayList<>();

        for (BunnySubcommand sub : command.getSubcommands().values()) {
            branches.add(sub);
            paths.add(command.getName() + " " + sub.getName());
        }

        for (BunnySubcommandGroup group : command.getSubcommandGroups().values())
            for (BunnySubcommand sub : group.getSubcommands().values()) {
                branches.add(sub);
                paths.add(command.getName() + " " + group.getName() + " " + sub.getName());
            }

        int shown = Math.min(branches.size(), MAX_DETAIL_FIELDS);
        for (int i = 0; i < shown; i++)
            addBranchField(embed, command, paths.get(i), branches.get(i), botName);

        int hidden = branches.size() - shown;
        if (hidden > 0)
            field(embed, "More", BeastarsEmoji.EMPTY + " " + hidden
                    + " further branches are not shown here. See the category page.", false);
    }

    private static void addBranchField(EmbedBuilder embed, BunnyCommand command, String path,
                                       BunnySubcommand sub, String botName) {
        boolean mentionable = command.isMentionEnabled() && sub.isMentionEnabled();
        String args = renderArgs(sub.getOptions());

        StringBuilder body = new StringBuilder();
        if (sub.getDescription() != null)
            body.append("*").append(sub.getDescription()).append("*\n");

        body.append(BeastarsEmoji.SLASH).append(" `/").append(path).append(args).append("`\n");
        body.append(mentionable
                ? BeastarsEmoji.MENTION + " `@" + botName + " " + path + args + "`\n"
                : BeastarsEmoji.MENTION + " *Slash only.*\n");

        // Shorthands only mean anything on the mention path, so a slash-only branch does
        // not advertise them even when it has them.
        if (mentionable && !sub.getAliases().isEmpty())
            body.append(BeastarsEmoji.PARAMETER).append(" Also: ")
                    .append(shorthands(command.getName(), path, sub.getAliases()))
                    .append("\n");

        // Only a hand-written example earns a line here. A generated one is the usage
        // line with the brackets taken off - `/image get name:text` under
        // `/image get name:<text>` - which is two more lines that teach nothing. A
        // declared example carries real values, so it stays.
        if (sub.getExample() != null && !sub.getExample().isBlank())
            body.append(example(sub.getExample(), path, sub.getOptions(), botName, mentionable)).append("\n");

        for (OptionData option : sub.getOptions())
            body.append(BeastarsEmoji.PARAMETER).append(" `").append(option.getName()).append("` ")
                    .append(option.isRequired() ? "required" : "optional").append(": ")
                    .append(option.getDescription() == null ? "" : option.getDescription()).append("\n");

        field(embed, "/" + path, body.toString(), false);
    }

    /**
     * Renders a subcommand's aliases as full paths rather than bare words.
     *
     * <p>{@code leg lb} is usable as typed; a bare {@code lb} is not, because the alias
     * replaces only the last segment. Printing the whole path removes the guess.
     */
    private static String shorthands(String commandName, String path, List<String> aliases) {
        // The path already carries the command and any group; swapping only the final
        // segment keeps a grouped branch such as `admin logging show` intact.
        String prefix = path.substring(0, path.lastIndexOf(' ') + 1);
        if (prefix.isEmpty())
            prefix = commandName + " ";

        StringBuilder rendered = new StringBuilder();
        for (String alias : aliases) {
            if (!rendered.isEmpty())
                rendered.append("  ");
            rendered.append("`").append(prefix).append(alias).append("`");
        }

        return rendered.toString();
    }

    /**
     * A copy-pasteable invocation.
     *
     * <p>Prefers whatever the command declared, since a hand-written {@code query:Legoshi}
     * teaches more than a synthesised {@code query:text}. Falls back to generating one
     * from the required options, which is always in step with the declaration even when
     * nobody remembered to write an example.
     */
    private static String example(String declared, String path, List<OptionData> options,
                                  String botName, boolean mentionable) {
        String slash = declared != null && !declared.isBlank()
                ? declared.trim()
                : "/" + path + generatedArgs(options);

        if (!mentionable)
            return BeastarsEmoji.EXAMPLE + " `" + slash + "`";

        // The mention form is the same line with the leading slash swapped for the bot.
        String mention = "@" + botName + " " + (slash.startsWith("/") ? slash.substring(1) : slash);
        return BeastarsEmoji.EXAMPLE + " `" + slash + "`\n"
                + BeastarsEmoji.EXAMPLE + " `" + mention + "`";
    }

    /**
     * Fills in every required option with a plausible value, and stops there.
     *
     * <p>Optional ones are left out on purpose: the point of an example is the shortest
     * thing that actually runs.
     */
    private static String generatedArgs(List<OptionData> options) {
        StringBuilder args = new StringBuilder();

        for (OptionData option : options) {
            if (!option.isRequired())
                continue;
            args.append(" ").append(option.getName()).append(":").append(sampleValue(option));
        }

        return args.toString();
    }

    private static String sampleValue(OptionData option) {
        // A declared choice is a real, accepted value - always better than a placeholder.
        if (!option.getChoices().isEmpty())
            return option.getChoices().get(0).getAsString();

        return switch (option.getType()) {
            case USER -> "@member";
            case ROLE -> "@role";
            case CHANNEL -> "#channel";
            case INTEGER, NUMBER -> "1";
            case BOOLEAN -> "true";
            default -> "text";
        };
    }

    /** Field names take no emoji, so the required/optional marker has to live here. */
    private static String parameterName(OptionData option) {
        return option.getName() + (option.isRequired() ? " (required)" : " (optional)");
    }

    private static String parameterValue(OptionData option) {
        StringBuilder body = new StringBuilder(BeastarsEmoji.PARAMETER).append(" ");

        if (option.getDescription() != null)
            body.append(option.getDescription());

        body.append("\nType: `").append(typeHint(option)).append("`");

        List<Command.Choice> choices = option.getChoices();
        if (!choices.isEmpty()) {
            // The whole reason this card exists: `source` accepts four things, and
            // nothing else in the help menu was ever going to tell you what they are.
            body.append("\nAccepts:");
            for (Command.Choice choice : choices)
                body.append("\n• `").append(choice.getAsString()).append("`: ").append(choice.getName());
        }

        return truncate(body.toString());
    }

    private static String footer(BunnyCommand command) {
        StringBuilder footer = new StringBuilder(command.getCategory());

        if (command.getCooldown() > 0)
            footer.append("  •  ").append(command.getCooldown()).append("s cooldown");
        if (command.isAdminOnly())
            footer.append("  •  Admins only");
        if (command.isNsfw())
            footer.append("  •  NSFW channels only");
        if (!command.isDmEnabled())
            footer.append("  •  Servers only");

        return footer.toString();
    }

    private static String truncate(String body) {
        return clamp(body, MAX_FIELD_VALUE);
    }

    // ------------------------------------------------------------------
    // Safe field construction
    // ------------------------------------------------------------------

    /**
     * Adds a field, clamped to Discord's limits, and reports whether it fitted.
     *
     * <p>Every field this class builds goes through here. JDA validates on
     * {@code addField} and <em>throws</em> rather than trimming, so a single over-long
     * value does not degrade the help menu - it takes the whole command down with an
     * {@code IllegalArgumentException}. That is exactly what happened when {@code /admin}
     * grew a third subcommand group and its entry crossed the ceiling.
     *
     * <p>The running total matters as much as the individual value: an embed is capped at
     * 6000 characters across every part of it, and a category holding several large
     * commands can pass that while every single field is legal on its own.
     *
     * @return false when the field was dropped because the embed is full
     */
    private static boolean field(EmbedBuilder embed, String name, String value, boolean inline) {
        String safeName = clamp(name, MAX_FIELD_NAME);
        String safeValue = clamp(value, MAX_FIELD_VALUE);

        if (embed.length() + safeName.length() + safeValue.length() > MAX_EMBED_TOTAL)
            return false;

        embed.addField(safeName, safeValue, inline);
        return true;
    }

    /**
     * Trims to a limit, and never returns something Discord will reject.
     *
     * <p>An empty field name or value is refused outright, so a null or blank becomes a
     * zero-width space - the standard stand-in, and invisible to the reader.
     */
    private static String clamp(String text, int limit) {
        if (text == null || text.isBlank())
            return "​";

        return text.length() <= limit ? text : text.substring(0, limit - 3) + "...";
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    /**
     * The navigation row: ends, steps, and a jump straight to a category.
     *
     * <p>Five controls, which is exactly Discord's per-row ceiling. Stepping is fine for
     * an adjacent page, but the reason somebody opens help is usually that they want one
     * particular category - <b>Browse</b> takes them there in one click instead of
     * however many <b>Next</b> presses the alphabet happens to demand.
     *
     * <p>Both ends are offered because the last page is as reachable a destination as
     * the first, and neither should cost a sequence of clicks.
     */
    public static ActionRow getPaginationButtons(List<BunnyCommand> commands, int currentPage) {
        int totalPages = getTotalPages(commands);
        int page = Math.max(1, Math.min(currentPage, totalPages));

        boolean atStart = page <= 1;
        boolean atEnd = page >= totalPages;

        return ActionRow.of(
                Button.secondary("help:first:" + page, "First")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.NAV_FIRST))
                        .withDisabled(atStart),
                Button.secondary("help:prev:" + page, "Previous")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.NAV_PREVIOUS))
                        .withDisabled(atStart),
                Button.primary("help:browse:" + page, "Browse")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.BROWSE))
                        // Never disabled: it is the way out of anywhere, including a
                        // single-page menu where every other control is greyed out.
                        .withDisabled(false),
                Button.secondary("help:next:" + page, "Next")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.NAV_NEXT))
                        .withDisabled(atEnd),
                Button.secondary("help:last:" + page, "Last")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.NAV_LAST))
                        .withDisabled(atEnd));
    }

    // ------------------------------------------------------------------
    // Category chooser
    // ------------------------------------------------------------------

    /** Modal id prefix, and the id of the menu inside it. */
    public static final String BROWSE_MODAL = "helpbrowse";
    public static final String INPUT_CATEGORY = "help_category";

    /**
     * The category chooser, as a modal rather than a select menu on the message.
     *
     * <p>A select menu on the message would be a third row competing with the command
     * picker already there, and it would rewrite the message for everyone reading it.
     * A modal is private to whoever opened it and costs no space at all - JDA 6 allows
     * select menus inside one, so the chooser is still a dropdown rather than somebody
     * having to type a category name correctly.
     */
    public static Modal categoryModal(List<BunnyCommand> commands, int currentPage) {
        Map<String, List<BunnyCommand>> grouped = categorise(commands);
        List<String> categories = new ArrayList<>(grouped.keySet());

        StringSelectMenu.Builder menu = StringSelectMenu.create(INPUT_CATEGORY)
                .setPlaceholder("Choose a category")
                .setRequiredRange(1, 1);

        int added = 0;
        for (String category : categories) {
            if (added >= MAX_PICKER_OPTIONS)
                break;

            int count = grouped.get(category).size();
            menu.addOption(category, category, count + (count == 1 ? " command" : " commands"));
            added++;
        }

        // A menu with no options is rejected outright, and an empty registry would
        // otherwise turn the button into a dead end.
        if (added == 0)
            menu.addOption("No categories", "none", "Nothing is available yet");

        return Modal.create(BROWSE_MODAL + ":" + currentPage, "Jump to a Category")
                .addComponents(Label.of("Category",
                        "The help menu will open on that category's page.", menu.build()))
                .build();
    }

    /**
     * The 1-indexed page a category sits on.
     *
     * <p>Categories are ordered alphabetically and one page holds one category, so the
     * position in that ordering <em>is</em> the page number.
     *
     * @return the page, or 1 when the name matches nothing
     */
    public static int categoryPage(List<BunnyCommand> commands, String category) {
        if (category == null)
            return 1;

        List<String> categories = new ArrayList<>(categorise(commands).keySet());
        for (int i = 0; i < categories.size(); i++)
            if (categories.get(i).equalsIgnoreCase(category))
                return i + 1;

        return 1;
    }

    /**
     * The command picker that sits under every help message.
     *
     * <p>Lists every visible command, not just the ones on the current page - the whole
     * point is to jump straight to one without paging to find it first.
     *
     * @param page     the page to return to when the reader backs out of a detail card
     * @param selected the command currently being shown, pre-ticked so the menu reflects
     *                 what is on screen rather than resetting to a blank placeholder
     */
    public static ActionRow getCommandPicker(List<BunnyCommand> commands, int page, String selected) {
        List<BunnyCommand> shown = visible(commands);
        StringSelectMenu.Builder menu = StringSelectMenu.create("helppick:" + page)
                .setPlaceholder("Pick a command for its full usage");

        int added = 0;
        for (BunnyCommand command : shown) {
            if (added++ >= MAX_PICKER_OPTIONS)
                break;

            SelectOption option = SelectOption.of("/" + command.getName(), command.getName())
                    .withDefault(command.getName().equalsIgnoreCase(selected));

            if (command.getDescription() != null)
                option = option.withDescription(clamp(command.getDescription()));

            menu.addOptions(option);
        }

        // A menu with no options is rejected outright, which would take the whole help
        // message with it. A disabled placeholder row says the same thing harmlessly.
        if (added == 0) {
            menu.addOptions(SelectOption.of("No commands available", "none"));
            return ActionRow.of(menu.build().asDisabled());
        }

        return ActionRow.of(menu.build());
    }

    /** Picker plus a way back to the index the reader came from. */
    public static List<ActionRow> getDetailComponents(List<BunnyCommand> commands, int page, String selected) {
        return List.of(
                getCommandPicker(commands, page, selected),
                ActionRow.of(Button.secondary("help:back:" + page, "Back to Command List")
                        .withEmoji(BeastarsEmoji.button(BeastarsEmoji.NAV_PREVIOUS))));
    }

    /** Pagination plus the picker: what a freshly opened help menu carries. */
    public static List<ActionRow> getIndexComponents(List<BunnyCommand> commands, int page) {
        return List.of(getPaginationButtons(commands, page), getCommandPicker(commands, page, null));
    }

    private static String clamp(String description) {
        return description.length() > MAX_OPTION_DESCRIPTION
                ? description.substring(0, MAX_OPTION_DESCRIPTION - 3) + "..."
                : description;
    }
}
