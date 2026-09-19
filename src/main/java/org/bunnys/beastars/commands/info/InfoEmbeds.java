package org.bunnys.beastars.commands.info;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.beastars.BeastarsEmoji;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Timestamps;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every embed the informational commands can produce.
 *
 * <p>Rendering only, per the house layering rule - nothing here reads the database or a
 * cache. Everything it needs is passed in, already resolved.
 *
 * <h2>Smart fields</h2>
 * A field that would say "None" is usually a field not worth printing. Roles, boosting,
 * key permissions, nicknames, server descriptions and banners all appear only when there
 * is something to show, so a card stays as short as its subject deserves rather than
 * padding itself out with absences.
 *
 * <h2>Times</h2>
 * Rendered as Discord timestamp markup ({@code <t:epoch:F>}) rather than formatted here.
 * The client renders them in the reader's own locale and timezone, and the relative form
 * keeps counting on its own instead of going stale the moment the message is sent.
 *
 * <h2>What is deliberately missing</h2>
 * No online status, device, or "listening to Spotify". All three need the
 * {@code GUILD_PRESENCES} privileged intent, which Discord no longer grants for this
 * kind of bot. Printing a permanent "Offline" would be worse than printing nothing.
 */
public final class InfoEmbeds {

    /** JDA reports -1 for the gateway until the first heartbeat lands. */
    private static final long UNKNOWN_LATENCY = -1L;

    /** Thresholds for the plain-English verdict on the slower of the two round trips. */
    private static final long LATENCY_GOOD_MS = 150L;
    private static final long LATENCY_FAIR_MS = 400L;

    /** Discord serves avatars at powers of two; 1024 is the largest that stays quick. */
    private static final int AVATAR_SIZE = 1024;

    /** A field value is capped at 1024 characters. Roles are the only unbounded list. */
    private static final int MAX_FIELD_VALUE = MessageEmbed.VALUE_MAX_LENGTH;

    /**
     * Permissions worth naming, in descending order of consequence.
     *
     * <p>Not every permission a member holds - that list is long, mostly uninteresting,
     * and would bury the two or three that actually matter. These are the ones that
     * change what somebody can do to other people.
     */
    private static final Permission[] KEY_PERMISSIONS = {
            Permission.ADMINISTRATOR,
            Permission.MANAGE_SERVER,
            Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL,
            Permission.MANAGE_WEBHOOKS,
            Permission.MANAGE_PERMISSIONS,
            Permission.BAN_MEMBERS,
            Permission.KICK_MEMBERS,
            Permission.MODERATE_MEMBERS,
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_THREADS,
            Permission.NICKNAME_MANAGE,
            Permission.MANAGE_GUILD_EXPRESSIONS,
            Permission.MESSAGE_MENTION_EVERYONE,
            Permission.VIEW_AUDIT_LOGS,
            Permission.MESSAGE_TTS,
            Permission.VOICE_MUTE_OTHERS,
            Permission.VOICE_DEAF_OTHERS,
            Permission.VOICE_MOVE_OTHERS
    };

    /** Footer hints, one drawn per card. Plain text, since footers cannot render emoji. */
    private static final String[] TIPS = {
            "Blue text is clickable",
            "Click the user ID to open their profile",
            "Click the colour to see the full shade",
            "This works for people outside this server too",
            "Empty fields are hidden rather than left blank",
            "Timestamps are drawn by your own client, so they never go stale",
            "Administrator outranks everything, so it is listed on its own",
            "Roles are listed highest first",
            "A server avatar only shows when it differs from the global one",
            "Cherryton keeps records on everyone"
    };

    private InfoEmbeds() {}

    // ------------------------------------------------------------------
    // Ping
    // ------------------------------------------------------------------

    /**
     * @param gatewayMillis {@code -1} while the first heartbeat is still outstanding
     * @param restMillis    {@code -1} when the REST probe failed outright
     */
    public static MessageEmbed ping(long gatewayMillis, long restMillis) {
        return new EmbedBuilder()
                .setTitle("Pong")
                .setDescription(BeastarsEmoji.LATENCY + " " + verdict(gatewayMillis, restMillis))
                .addField("Gateway", latency(gatewayMillis), true)
                .addField("REST API", latency(restMillis), true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now())
                .build();
    }

    /** Field values lead with an emoji too, so both columns read like the description. */
    private static String latency(long millis) {
        return millis < 0
                ? BeastarsEmoji.NOT_FOUND + " unavailable"
                : BeastarsEmoji.LATENCY + " " + millis + " ms";
    }

    private static String verdict(long gatewayMillis, long restMillis) {
        long worst = Math.max(gatewayMillis, restMillis);

        if (worst == UNKNOWN_LATENCY)
            return "Still measuring. Ask again in a moment.";
        if (worst <= LATENCY_GOOD_MS)
            return "Responding normally.";
        if (worst <= LATENCY_FAIR_MS)
            return "Responding, a little slower than usual.";

        return "Responding, but the connection is sluggish right now.";
    }

    // ------------------------------------------------------------------
    // Uptime
    // ------------------------------------------------------------------

    /**
     * The bot status card, and the one place the bot credits whoever built it.
     *
     * <p>Deliberately one place. A credit in every footer would be branding somebody
     * else's server, and it is the version that gets asked for removal; a credit on the
     * command that already answers "what is this bot" is the version that stays.
     *
     * @param developerName null to render no credit at all
     * @param developerId   Discord id for the profile link, or null
     */
    public static MessageEmbed uptime(String botName, String botAvatarUrl,
                                      long startedAtMillis, int guilds, String version,
                                      String developerName, String developerId) {
        long startedAtSeconds = startedAtMillis / 1000L;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(botName + " Uptime")
                .setThumbnail(botAvatarUrl)
                // Relative markup, not a rendered duration. "Online for 3 hours" is
                // wrong the moment the message is scrolled back to; <t:...:R> keeps
                // counting client-side and is correct whenever it is read.
                .setDescription(BeastarsEmoji.UPTIME + " Online since " + Timestamps.relative(startedAtSeconds) + ".")
                // The absolute form alongside it, so the card answers both "how long"
                // and "since exactly when" without repeating itself.
                .addField("Last Started", BeastarsEmoji.CREATED + " " + Timestamps.absolute(startedAtSeconds), true)
                .addField("Servers", BeastarsEmoji.SERVER + " " + String.format("%,d", guilds), true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Version " + version)
                .setTimestamp(Instant.now());

        if (developerName != null && !developerName.isBlank())
            embed.addField("Built By", BeastarsEmoji.PANEL + " " + credit(developerName, developerId), true);

        return embed.build();
    }

    /**
     * Adds the bot's own version and authorship, and only to the bot's own card.
     *
     * <p>Callers pass null for everybody else, so this is a no-op on an ordinary profile.
     */
    private static void appendBotCredit(EmbedBuilder embed, String botVersion,
                                        String developerName, String developerId) {
        if (botVersion != null && !botVersion.isBlank())
            embed.addField("Version", BeastarsEmoji.PANEL + " `" + botVersion + "`", true);

        if (developerName != null && !developerName.isBlank())
            embed.addField("Built By", BeastarsEmoji.PANEL + " "
                    + credit(developerName, developerId), true);
    }

    /**
     * The developer credit, linked to a Discord profile where one is configured.
     *
     * <p>A profile URL rather than a {@code <@id>} mention on purpose: a mention renders
     * as "@unknown-user" for anybody who does not share a server with them, which makes
     * the credit look broken exactly where it is most likely to be read by a stranger.
     */
    private static String credit(String name, String developerId) {
        return developerId == null
                ? name
                : "[" + name + "](https://discord.com/users/" + developerId + ")";
    }

    /**
     * Renders a span as {@code 3d 4h 12m 8s}, dropping leading units that are zero.
     *
     * <p>Seconds are always kept, so a bot that restarted moments ago reads as "6s"
     * rather than as an empty string.
     */
    private static String duration(long millis) {
        long totalSeconds = millis / 1000L;

        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder text = new StringBuilder();
        if (days > 0)
            text.append(days).append("d ");
        if (days > 0 || hours > 0)
            text.append(hours).append("h ");
        if (days > 0 || hours > 0 || minutes > 0)
            text.append(minutes).append("m ");
        text.append(seconds).append("s");

        return text.toString();
    }

    // ------------------------------------------------------------------
    // Avatar
    // ------------------------------------------------------------------

    /**
     * The avatar card.
     *
     * <p>Deliberately the one embed in the bot with no emoji in it. The picture is the
     * whole point of the message, and anything sitting above it competes with it.
     *
     * <p>When a member has both a global and a per-server avatar, both are shown at once
     * - the requested one large, the other as a thumbnail - because the usual reason to
     * run this on somebody is to compare the two.
     */
    public static MessageEmbed avatar(User target, Member member, boolean preferGlobal) {
        String global = globalAvatar(target);
        String server = serverAvatar(member);
        boolean split = server != null;

        String main = (preferGlobal || !split) ? global : server;
        String secondary = (preferGlobal || !split) ? server : global;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(displayName(target, member) + "'s Avatar")
                .setDescription(avatarLinks(global, server))
                .setImage(main)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setTimestamp(Instant.now());

        if (split && secondary != null)
            embed.setThumbnail(secondary);

        return embed.build();
    }

    /** Link buttons for whichever avatars exist. One row, one or two buttons. */
    public static ActionRow avatarButtons(User target, Member member) {
        String server = serverAvatar(member);
        List<Button> buttons = new ArrayList<>();

        buttons.add(Button.link(globalAvatar(target), "Global Avatar"));
        if (server != null)
            buttons.add(Button.link(server, "Server Avatar"));

        return ActionRow.of(buttons);
    }

    /** Null when the member has no avatar of their own for this server. */
    private static String serverAvatar(Member member) {
        if (member == null || member.getAvatarId() == null)
            return null;
        return member.getEffectiveAvatar().getUrl(AVATAR_SIZE);
    }

    private static String globalAvatar(User target) {
        return target.getEffectiveAvatar().getUrl(AVATAR_SIZE);
    }

    private static String avatarLinks(String global, String server) {
        String links = "[Global Avatar](" + global + ")";
        return server == null ? links : links + "  |  [Server Avatar](" + server + ")";
    }

    // ------------------------------------------------------------------
    // User profile
    // ------------------------------------------------------------------

    /**
     * A full profile card.
     *
     * @param member null outside a guild, or when the target is not a member of it -
     *               in which case the guild half is dropped rather than faked
     */
    public static MessageEmbed userInfo(User target, Member member) {
        return userInfo(target, member, null, null, null);
    }

    /**
     * A profile card that credits the developer when the subject is the bot itself.
     *
     * <p>Looking up the bot is the one moment somebody is actually asking "what is this
     * thing and who made it", so that is where the answer belongs. Every other profile -
     * and every other embed in the bot - is left alone: a credit repeated everywhere
     * stops reading as authorship and starts reading as advertising.
     *
     * @param developerName null on any card that is not the bot's own
     */
    public static MessageEmbed userInfo(User target, Member member, String botVersion,
                                        String developerName, String developerId) {
        if (member == null)
            return strangerInfo(target, botVersion, developerName, developerId);

        long created = target.getTimeCreated().toEpochSecond();

        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor(displayName(target, member), profileUrl(target), globalAvatar(target))
                .setThumbnail(member.getEffectiveAvatarUrl())
                .setDescription(BeastarsEmoji.PROFILE + " " + target.getAsMention() + "\n"
                        + avatarLinks(globalAvatar(target), serverAvatar(member)))
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("TIP: " + tip())
                .setTimestamp(Instant.now());

        String badges = badges(target);
        if (badges != null)
            embed.addField("Badges", BeastarsEmoji.APEX + " " + badges, false);

        embed.addField("Joined Discord", BeastarsEmoji.CREATED + " " + moment(created), true);

        if (member.hasTimeJoined())
            embed.addField("Joined Server", BeastarsEmoji.JOINED + " "
                    + moment(member.getTimeJoined().toEpochSecond()), true);

        embed.addField("Account Type", BeastarsEmoji.PROFILE + " "
                + (target.isBot() ? "Bot" : "Human"), true);

        embed.addField("Username", BeastarsEmoji.PROFILE + " `" + target.getName() + "`", true);

        // Only worth a field when it differs from the name already in the author line.
        if (member.getNickname() != null)
            embed.addField("Nickname", BeastarsEmoji.EDIT + " " + member.getNickname(), true);

        embed.addField("User ID", " ["
                + target.getId() + "](" + profileUrl(target) + ")", true);

        String colour = colourField(member);
        if (colour != null)
            embed.addField("Colour", colour, true);

        embed.addField("Highest Role", BeastarsEmoji.ROLES + " " + highestRole(member), true);

        if (member.isBoosting() && member.getTimeBoosted() != null)
            embed.addField("Boosting Since", BeastarsEmoji.BOOSTS + " "
                    + moment(member.getTimeBoosted().toEpochSecond()), true);

        List<Role> roles = member.getRoles();
        if (!roles.isEmpty())
            embed.addField("Roles [" + roles.size() + "]", roleList(roles), false);

        List<String> keyPermissions = keyPermissions(member);
        if (!keyPermissions.isEmpty())
            embed.addField("Key Permissions [" + keyPermissions.size() + "]",
                    clamp(BeastarsEmoji.OVERRIDE + " " + String.join(", ", keyPermissions)), false);

        appendBotCredit(embed, botVersion, developerName, developerId);

        return embed.build();
    }

    /**
     * The reduced card for somebody who is not in this server.
     *
     * <p>Everything guild-shaped is genuinely unknowable here, so the card says less
     * rather than printing a column of dashes.
     */
    private static MessageEmbed strangerInfo(User target, String botVersion,
                                             String developerName, String developerId) {
        long created = target.getTimeCreated().toEpochSecond();

        EmbedBuilder embed = new EmbedBuilder()
                .setAuthor(target.getEffectiveName(), profileUrl(target), globalAvatar(target))
                .setThumbnail(globalAvatar(target))
                .setDescription(BeastarsEmoji.PROFILE + " " + target.getAsMention() + "\n"
                        + avatarLinks(globalAvatar(target), null))
                .addField("Joined Discord", BeastarsEmoji.CREATED + " " + moment(created), true)
                .addField("Account Type", BeastarsEmoji.PROFILE + " "
                        + (target.isBot() ? "Bot" : "Human"), true)
                .addField("Username", BeastarsEmoji.PROFILE + " `" + target.getName() + "`", true)
                .addField("User ID", BeastarsEmoji.PANEL + " ["
                        + target.getId() + "](" + profileUrl(target) + ")", true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("TIP: This shows much more for members of this server")
                .setTimestamp(Instant.now());

        String badges = badges(target);
        if (badges != null)
            embed.addField("Badges", BeastarsEmoji.APEX + " " + badges, false);

        appendBotCredit(embed, botVersion, developerName, developerId);

        return embed.build();
    }

    /**
     * Discord's account badges, by name.
     *
     * <p>Named rather than drawn: the badge artwork is Discord's, and this bot's emoji
     * set is Beastars. Inventing approximations would look worse than reading them.
     *
     * @return null when the account carries none, so the caller can drop the field
     */
    private static String badges(User target) {
        EnumSet<User.UserFlag> flags = target.getFlags();
        if (flags.isEmpty())
            return null;

        List<String> named = new ArrayList<>();
        for (User.UserFlag flag : flags) {
            // Internal plumbing rather than anything a person earned or displays.
            if (flag == User.UserFlag.UNKNOWN
                    || flag == User.UserFlag.TEAM_USER
                    || flag == User.UserFlag.BOT_HTTP_INTERACTIONS)
                continue;
            named.add(flag.getName());
        }

        return named.isEmpty() ? null : String.join(", ", named);
    }

    /**
     * The member's display colour, linked to a full swatch.
     *
     * <p>Read through {@code getColors()} rather than the deprecated {@code getColor()},
     * because Discord roles are no longer a single colour - they can be a two-stop
     * gradient or holographic. Each is named for what it is instead of being flattened
     * to its first stop and quietly mislabelled.
     *
     * <p>The embed itself stays {@code ColorCodes.DEFAULT}. Recolouring the whole card
     * per member would break the one-accent rule the rest of the bot follows, and a
     * profile is not the place to start making exceptions to it.
     *
     * @return null when the member has no colour of their own
     */
    private static String colourField(Member member) {
        var colours = member.getColors();
        if (colours == null || colours.isDefault())
            return null;

        if (colours.isHolographic())
            return BeastarsEmoji.EDIT + " Holographic";

        String primary = swatch(colours.getPrimary());
        if (primary == null)
            return null;

        if (colours.isGradient()) {
            String secondary = swatch(colours.getSecondary());
            return BeastarsEmoji.EDIT + " " + primary + " to " + (secondary == null ? "?" : secondary);
        }

        return BeastarsEmoji.EDIT + " " + primary;
    }

    /** A hex code linked to a full-size swatch, or null when there is no colour. */
    private static String swatch(Color colour) {
        if (colour == null)
            return null;

        String hex = String.format("%06X", colour.getRGB() & 0xFFFFFF);
        return "[#" + hex + "](https://www.color-hex.com/color/" + hex + ")";
    }

    private static String highestRole(Member member) {
        List<Role> roles = member.getRoles();
        return roles.isEmpty() ? "@everyone" : roles.get(0).getAsMention();
    }

    /**
     * Every role, highest first, trimmed to fit.
     *
     * <p>{@code getRoles()} is already ordered and already excludes {@code @everyone}.
     * A role-heavy member would blow past the 1024-character field ceiling, so the tail
     * becomes a count rather than taking the embed down.
     */
    private static String roleList(List<Role> roles) {
        StringBuilder text = new StringBuilder(BeastarsEmoji.ROLES);
        int shown = 0;

        for (Role role : roles) {
            String next = " " + role.getAsMention();
            // Leave room for the "and N more" tail before committing to another mention.
            if (text.length() + next.length() > MAX_FIELD_VALUE - 24)
                break;
            text.append(next);
            shown++;
        }

        int hidden = roles.size() - shown;
        if (hidden > 0)
            text.append(" and ").append(hidden).append(" more");

        return text.toString();
    }

    private static List<String> keyPermissions(Member member) {
        List<String> held = new ArrayList<>();

        for (Permission permission : KEY_PERMISSIONS) {
            if (!member.hasPermission(permission))
                continue;

            // Administrator implies every other permission, so listing the rest beside
            // it says nothing. One line is the more honest answer.
            if (permission == Permission.ADMINISTRATOR)
                return List.of(permission.getName());

            held.add(permission.getName());
        }

        return held;
    }

    // ------------------------------------------------------------------
    // Server profile
    // ------------------------------------------------------------------

    public static MessageEmbed serverInfo(Guild guild) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(guild.getName())
                .setDescription(BeastarsEmoji.SERVER + " " + guild.getName() + " was created "
                        + moment(guild.getTimeCreated().toEpochSecond()))
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("Server ID: " + guild.getId())
                .setTimestamp(Instant.now());

        if (guild.getIconUrl() != null)
            embed.setThumbnail(guild.getIconUrl());

        // Deliberately the total, not a humans-and-bots split. That split needs a full
        // member cache, which needs the GUILD_MEMBERS intent this bot does not take -
        // counting whatever happens to be cached would report a confidently wrong number.
        embed.addField("Members", BeastarsEmoji.MEMBERS + " "
                + String.format("%,d", guild.getMemberCount()), true);
        embed.addField("Owner", BeastarsEmoji.OWNER + " <@" + guild.getOwnerId() + ">", true);
        embed.addField("Roles", BeastarsEmoji.ROLES + " " + guild.getRoles().size(), true);

        embed.addField("Channels", BeastarsEmoji.CHANNELS + " "
                + guild.getTextChannels().size() + " text\n"
                + guild.getVoiceChannels().size() + " voice\n"
                + guild.getCategories().size() + " categories", true);

        embed.addField("Boosts", BeastarsEmoji.BOOSTS + " " + guild.getBoostCount()
                + "\n" + boostTier(guild), true);

        embed.addField("Verification", BeastarsEmoji.ROLE_ALLOWED + " " + verification(guild), true);
        embed.addField("Content Filter", BeastarsEmoji.ROLE_BANNED + " " + nsfwLevel(guild), true);
        embed.addField("Language", BeastarsEmoji.WIKI + " " + guild.getLocale().getLanguageName(), true);

        if (guild.getAfkChannel() != null)
            embed.addField("AFK", BeastarsEmoji.TIMER + " " + guild.getAfkChannel().getAsMention()
                    + "\nafter " + duration(guild.getAfkTimeout().getSeconds() * 1000L), true);

        if (guild.getDescription() != null)
            embed.addField("Description", BeastarsEmoji.PANEL + " "
                    + clamp(guild.getDescription()), false);

        String features = features(guild);
        if (features != null)
            embed.addField("Features", BeastarsEmoji.SUCCESS + " " + clamp(features), false);

        // The banner is the one image worth showing large; the icon is already the thumbnail.
        if (guild.getBannerUrl() != null)
            embed.setImage(guild.getBannerUrl());

        return embed.build();
    }

    private static String boostTier(Guild guild) {
        return switch (guild.getBoostTier()) {
            case TIER_1 -> "Tier 1";
            case TIER_2 -> "Tier 2";
            case TIER_3 -> "Tier 3";
            case NONE -> "No tier";
            default -> "Unknown tier";
        };
    }

    /** Discord's own wording, shortened to something that fits an inline field. */
    private static String verification(Guild guild) {
        return switch (guild.getVerificationLevel()) {
            case NONE -> "Unrestricted";
            case LOW -> "Verified email";
            case MEDIUM -> "Registered 5+ minutes";
            case HIGH -> "Member 10+ minutes";
            case VERY_HIGH -> "Verified phone";
            default -> "Unknown";
        };
    }

    private static String nsfwLevel(Guild guild) {
        return switch (guild.getNSFWLevel()) {
            case DEFAULT -> "Default";
            case SAFE -> "Safe";
            case EXPLICIT -> "Explicit";
            case AGE_RESTRICTED -> "Age restricted";
            default -> "Unknown";
        };
    }

    /**
     * Guild feature flags, made readable.
     *
     * <p>Discord ships these as {@code SCREAMING_SNAKE_CASE}; printed raw they look like
     * a leaked internal constant.
     *
     * @return null when the guild has none, so the caller can drop the field
     */
    private static String features(Guild guild) {
        if (guild.getFeatures().isEmpty())
            return null;

        List<String> readable = new ArrayList<>();
        for (String feature : guild.getFeatures()) {
            StringBuilder pretty = new StringBuilder();
            for (String word : feature.toLowerCase().split("_")) {
                if (word.isEmpty())
                    continue;
                if (!pretty.isEmpty())
                    pretty.append(' ');
                pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            readable.add(pretty.toString());
        }

        readable.sort(String::compareToIgnoreCase);
        return String.join(", ", readable);
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    /**
     * {@code /info server} was run outside a guild.
     *
     * <p>{@code CommandGate} gates whole commands, not branches, and {@code /info user}
     * is perfectly useful in a DM - so this one branch turns itself away rather than
     * taking the other one down with it.
     */
    public static MessageEmbed guildOnly() {
        return new EmbedBuilder()
                .setTitle("Server Only")
                .setDescription(BeastarsEmoji.DENIED
                        + " There is no server to describe here. Run this one in a server.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * The mention path could not resolve the named user.
     *
     * <p>Only reachable from a mention: a slash {@code USER} option is resolved by
     * Discord itself and always arrives complete.
     */
    public static MessageEmbed unknownUser() {
        return new EmbedBuilder()
                .setTitle("User Not Found")
                .setDescription(BeastarsEmoji.NOT_FOUND
                        + " I could not work out who that is. Mention them directly, or use the slash command.")
                .setColor(AppDesign.ColorCodes.ERROR_RED)
                .setTimestamp(Instant.now())
                .build();
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    private static String displayName(User target, Member member) {
        return member != null ? member.getEffectiveName() : target.getEffectiveName();
    }

    private static String profileUrl(User target) {
        return "https://discord.com/users/" + target.getId();
    }

    /** Full date plus a live relative counter, both rendered client-side. */
    private static String moment(long epochSeconds) {
        return Timestamps.absoluteAndRelative(epochSeconds);
    }

    private static String tip() {
        return TIPS[ThreadLocalRandom.current().nextInt(TIPS.length)];
    }

    private static String clamp(String value) {
        return value.length() <= MAX_FIELD_VALUE
                ? value
                : value.substring(0, MAX_FIELD_VALUE - 3) + "...";
    }
}
