package org.bunnys.beastars.commands.leg;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bunnys.beastars.database.LegConfigData;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a member may take part in the Leg economy.
 *
 * <p>Every rule reads from a cache ({@link LegConfigService} for the guild config,
 * {@link LegService} for the member's ban flag), so a gate check costs no database
 * round trips in the steady state.
 *
 * <h2>Administrator lockout protection</h2>
 * Role-based gates are configured through the very dashboard that requires the
 * economy to be usable. A moderator who sets an allow-list and forgets to include
 * their own role, or who bans a role they happen to hold, would lock themselves out
 * of testing their own configuration - with no way to see the effect of a fix.
 *
 * <p>So members holding {@link Permission#ADMINISTRATOR} or
 * {@link Permission#MANAGE_SERVER} always bypass {@link #ROLE_GATES}: the banned-role
 * list, the allowed-role list, and the new-member wait period.
 *
 * <p>They deliberately do <em>not</em> bypass {@link RuleResult#DISABLED} or
 * {@link RuleResult#USER_BANNED}. Those two are explicit, individually-targeted
 * decisions rather than role-configuration accidents - an "off" switch that stays on
 * for staff is a broken off switch, and an admin banned by user ID was banned on
 * purpose. Neither can strand anyone: both are reversible from the dashboard, which
 * needs no economy access at all.
 */
public final class LegRuleEngine {

    /** The gates a guild administrator is exempt from. */
    private static final Set<RuleResult> ROLE_GATES =
            Set.of(RuleResult.ROLE_BANNED, RuleResult.NOT_ALLOWED_ROLE, RuleResult.WAIT_PERIOD);

    private LegRuleEngine() {}

    /**
     * Outcome of a gate check. Each carries the title <em>and</em> body its denial
     * embed should use, so no call site has to invent copy for a rule it didn't write.
     */
    public enum RuleResult {
        ALLOWED(null, null),
        DISABLED("Economy Closed",
                "The Leg Economy is currently disabled in this server."),
        USER_BANNED("Blacklisted",
                "You are banned from participating in the Leg Economy in this server."),
        ROLE_BANNED("Marked Herd",
                "You have a role that is banned from the Leg Economy."),
        NOT_ALLOWED_ROLE("Insufficient Standing",
                "You do not have the required roles to participate in the Leg Economy."),
        WAIT_PERIOD("Still Settling In",
                "You must wait longer before participating. The server requires a wait period for new members.");

        private final String title;
        private final String message;

        RuleResult(String title, String message) {
            this.title = title;
            this.message = message;
        }

        public String getTitle() { return title; }
        public String getMessage() { return message; }
        public boolean isAllowed() { return this == ALLOWED; }
    }

    public static RuleResult canParticipate(Member member) {
        RuleResult verdict = evaluate(member);

        // Administrators are never held out by a role gate they themselves configure.
        if (ROLE_GATES.contains(verdict) && isGuildManager(member))
            return RuleResult.ALLOWED;

        return verdict;
    }

    /**
     * True if the member can reach the admin dashboard through native Discord
     * permissions. Deliberately not {@code AdminService.isAdmin} - a configured Bot
     * Admin role is a bot-level grant, whereas this exemption exists to protect the
     * people who own the server's actual permission model.
     */
    public static boolean isGuildManager(Member member) {
        return member != null
                && (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER));
    }

    /** The rules as configured, before any administrator exemption is applied. */
    private static RuleResult evaluate(Member member) {
        String guildId = member.getGuild().getId();
        LegConfigData config = LegConfigService.getConfig(guildId);

        if (!config.isEnabled())
            return RuleResult.DISABLED;

        if (LegService.getUser(guildId, member.getId()).isBanned())
            return RuleResult.USER_BANNED;

        List<String> banned = config.getBannedRoles();
        List<String> allowed = config.getAllowedRoles();
        List<String> bypass = config.getBypassWaitRoles();
        int waitHours = config.getWaitPeriodHours();

        // The overwhelmingly common guild has configured none of this, and every check
        // below is a no-op for it. Building the role set first would still walk the
        // member's roles and allocate a HashSet on every single /leg invocation, to then
        // ask it nothing - so the set is built only once a rule actually needs one.
        if (!banned.isEmpty() || !allowed.isEmpty() || !bypass.isEmpty()) {
            Set<String> memberRoles = roleIds(member);

            if (containsAny(banned, memberRoles))
                return RuleResult.ROLE_BANNED;

            // An empty allow-list means "everyone".
            if (!allowed.isEmpty() && !containsAny(allowed, memberRoles))
                return RuleResult.NOT_ALLOWED_ROLE;

            if (containsAny(bypass, memberRoles))
                return RuleResult.ALLOWED;
        }

        // No wait period means nothing to compare against, and getTimeJoined is not free:
        // when the member arrived without a join timestamp JDA falls back to the guild's
        // creation date, which would silently answer a question nobody asked.
        if (waitHours <= 0)
            return RuleResult.ALLOWED;

        long hoursInGuild = ChronoUnit.HOURS.between(member.getTimeJoined(), OffsetDateTime.now());
        return hoursInGuild >= waitHours ? RuleResult.ALLOWED : RuleResult.WAIT_PERIOD;
    }

    /** A set, not a list - up to three membership tests follow and roles can number in the hundreds. */
    private static Set<String> roleIds(Member member) {
        Set<String> ids = new HashSet<>();
        for (Role role : member.getRoles())
            ids.add(role.getId());
        return ids;
    }

    private static boolean containsAny(List<String> configured, Set<String> memberRoles) {
        for (String id : configured)
            if (memberRoles.contains(id))
                return true;
        return false;
    }
}
