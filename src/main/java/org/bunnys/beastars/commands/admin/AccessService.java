package org.bunnys.beastars.commands.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bunnys.handler.metrics.CacheRegistry;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Per-guild command locks: which commands run, for whom, and where.
 *
 * <p>Consulted by {@code CommandGate} on <b>every</b> invocation of every command, so
 * the whole guild's ruleset is one cached document rather than a query per check. A
 * guild that has configured nothing caches an empty ruleset and costs nothing after the
 * first read.
 *
 * <h2>Document shape</h2>
 * <pre>
 * {
 *   _id: "&lt;guildId&gt;",
 *   disabled: ["manga", "leg"],
 *   rules: {
 *     "manga": {
 *       allowedRoles: [...], deniedRoles: [...],
 *       allowedChannels: [...], deniedChannels: [...]
 *     }
 *   }
 * }
 * </pre>
 *
 * <h2>Evaluation order</h2>
 * Global toggle, then channel, then role; deny always beats allow, and a non-empty
 * allow-list means "only these". Guild managers bypass the lot - the same
 * lockout protection {@code LegRuleEngine} applies, for the same reason: the people who
 * configure these rules must not be able to lock themselves out of fixing them.
 */
public final class AccessService {

    public static final String COLLECTION = "command_access";

    private static final String DISABLED = "disabled";
    private static final String RULES = "rules";

    static final String ALLOWED_ROLES = "allowedRoles";
    static final String DENIED_ROLES = "deniedRoles";
    static final String ALLOWED_CHANNELS = "allowedChannels";
    static final String DENIED_CHANNELS = "deniedChannels";

    private static final Cache<String, GuildAccess> ACCESS = CacheRegistry.register("access.access", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .recordStats()
            .build());

    private AccessService() {}

    /** Which side of an allow/deny pair a rule edit targets. */
    public enum Mode { ALLOW, DENY }

    /** Whether a rule applies to roles or channels. */
    public enum Scope { ROLE, CHANNEL }

    /** The outcome of a check. {@code reason} is null when allowed. */
    public record Verdict(boolean allowed, String reason) {
        static final Verdict OK = new Verdict(true, null);

        static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** One command's rules. */
    public record CommandRules(List<String> allowedRoles, List<String> deniedRoles,
                               List<String> allowedChannels, List<String> deniedChannels) {

        static final CommandRules EMPTY = new CommandRules(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return allowedRoles.isEmpty() && deniedRoles.isEmpty()
                    && allowedChannels.isEmpty() && deniedChannels.isEmpty();
        }
    }

    /** A guild's complete ruleset, cached as one immutable snapshot. */
    public record GuildAccess(Set<String> disabled, Map<String, CommandRules> rules) {

        static final GuildAccess EMPTY = new GuildAccess(Set.of(), Map.of());

        public CommandRules rulesFor(String command) {
            return rules.getOrDefault(command, CommandRules.EMPTY);
        }

        public boolean isDisabled(String command) {
            return disabled.contains(command);
        }
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    /**
     * @param command   the top-level command name
     * @param member    the caller; null outside a guild
     * @param channelId the channel the command was invoked in
     */
    public static Verdict check(String guildId, String command, Member member, String channelId) {
        if (guildId == null)
            return Verdict.OK; // No guild, no guild rules.

        // Whoever can configure these rules must never be locked out by them.
        if (isGuildManager(member))
            return Verdict.OK;

        GuildAccess access = get(guildId);

        if (access.isDisabled(command))
            return Verdict.deny("`/" + command + "` is currently disabled in this server.");

        CommandRules rules = access.rulesFor(command);
        if (rules.isEmpty())
            return Verdict.OK;

        if (channelId != null) {
            if (rules.deniedChannels().contains(channelId))
                return Verdict.deny("`/" + command + "` cannot be used in this channel.");

            if (!rules.allowedChannels().isEmpty() && !rules.allowedChannels().contains(channelId))
                return Verdict.deny("`/" + command + "` is restricted to specific channels here.");
        }

        if (member != null && (!rules.deniedRoles().isEmpty() || !rules.allowedRoles().isEmpty())) {
            Set<String> memberRoles = new HashSet<>();
            for (Role role : member.getRoles())
                memberRoles.add(role.getId());

            for (String denied : rules.deniedRoles())
                if (memberRoles.contains(denied))
                    return Verdict.deny("One of your roles is blocked from `/" + command + "`.");

            if (!rules.allowedRoles().isEmpty()) {
                boolean permitted = false;
                for (String allowed : rules.allowedRoles())
                    if (memberRoles.contains(allowed)) {
                        permitted = true;
                        break;
                    }
                if (!permitted)
                    return Verdict.deny("`/" + command + "` is restricted to specific roles here.");
            }
        }

        return Verdict.OK;
    }

    private static boolean isGuildManager(Member member) {
        return member != null
                && (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** A guild's ruleset, never null. */
    public static GuildAccess get(String guildId) {
        GuildAccess cached = ACCESS.get(guildId, k -> {
            try {
                Document doc = DB.getCollection(Document.class, COLLECTION)
                        .find(Filters.eq("_id", k))
                        .first();

                return doc == null ? GuildAccess.EMPTY : parse(doc);
            } catch (Exception e) {
                BunnyLog.error("[AccessService] Read failed for guild " + k, e);
                return null; // Keeps the failure out of the cache.
            }
        });

        // A DB outage must not silently lock every command in the guild.
        return cached != null ? cached : GuildAccess.EMPTY;
    }

    private static GuildAccess parse(Document doc) {
        Set<String> disabled = new HashSet<>(readList(doc, DISABLED));

        Map<String, CommandRules> rules = new HashMap<>();
        Document rulesDoc = doc.get(RULES, Document.class);

        if (rulesDoc != null)
            for (String command : rulesDoc.keySet()) {
                Document entry = rulesDoc.get(command, Document.class);
                if (entry == null)
                    continue;

                rules.put(command, new CommandRules(
                        readList(entry, ALLOWED_ROLES),
                        readList(entry, DENIED_ROLES),
                        readList(entry, ALLOWED_CHANNELS),
                        readList(entry, DENIED_CHANNELS)));
            }

        return new GuildAccess(Set.copyOf(disabled), Map.copyOf(rules));
    }

    private static List<String> readList(Document doc, String field) {
        List<String> values = doc.getList(field, String.class);
        return values == null ? List.of() : List.copyOf(values);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Turns a command on or off for the whole guild. @return true when the write landed. */
    public static boolean setEnabled(String guildId, String command, boolean enabled) {
        return write(guildId, enabled
                ? Updates.pull(DISABLED, command)
                : Updates.addToSet(DISABLED, command));
    }

    /**
     * Replaces one side of one rule list.
     *
     * <p>A targeted {@code $set} on the exact nested array, so two admins editing
     * different commands - or different scopes of the same command - do not overwrite
     * each other.
     *
     * @param ids the complete new list; empty clears the rule.
     */
    public static boolean setRule(String guildId, String command, Scope scope, Mode mode, List<String> ids) {
        String field = RULES + "." + command + "." + fieldFor(scope, mode);
        return write(guildId, Updates.set(field, new ArrayList<>(ids)));
    }

    private static String fieldFor(Scope scope, Mode mode) {
        if (scope == Scope.ROLE)
            return mode == Mode.ALLOW ? ALLOWED_ROLES : DENIED_ROLES;
        return mode == Mode.ALLOW ? ALLOWED_CHANNELS : DENIED_CHANNELS;
    }

    private static boolean write(String guildId, Bson update) {
        try {
            DB.getCollection(Document.class, COLLECTION).updateOne(
                    Filters.eq("_id", guildId), update, new UpdateOptions().upsert(true));

            // Invalidate rather than patch: the ruleset is a nested structure and
            // re-reading it once is cheaper than getting a hand-merged copy wrong.
            ACCESS.invalidate(guildId);
            return true;
        } catch (Exception e) {
            BunnyLog.error("[AccessService] Write failed for guild " + guildId, e);
            ACCESS.invalidate(guildId);
            return false;
        }
    }
}
