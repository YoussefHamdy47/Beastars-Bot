package org.bunnys.beastars.commands.leg;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bunnys.beastars.database.LegConfigData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Everything that decides who appears on the leaderboard, resolved in one place.
 *
 * <p>Three rules stack, and they are not the same kind of rule:
 * <ul>
 *   <li><b>Banned members</b> - the bot's own flag, applied in the Mongo query by
 *       {@link LegService}. No Discord state involved, so it always works.</li>
 *   <li><b>Hidden roles</b> - configured by an admin; anybody holding one drops off.</li>
 *   <li><b>Departed members</b> - people who left the server.</li>
 *   <li><b>The viewer's own role filter</b> - a temporary lens, chosen per message.</li>
 * </ul>
 *
 * <h2>Why the last three need the member list</h2>
 * Gating a <em>command</em> on somebody's roles needs no privileged intent, because
 * Discord ships the invoking member inside the interaction payload - the answer arrives
 * with the question. A leaderboard asks the opposite question: given a list of user ids,
 * which of them hold a role, and which are still here at all. Nobody sent us those
 * members, so they have to come from the member cache, and a complete member cache is
 * exactly what {@code GUILD_MEMBERS} buys.
 *
 * <h2>Failing open</h2>
 * Without that intent the cache holds only whoever has been seen recently, so this
 * reports {@link Result#membersUnavailable()} and applies no member-dependent rule at
 * all. Showing somebody who should have been hidden is a cosmetic problem; hiding a
 * legitimate member because we could not look them up reads as their score being
 * deleted. Between the two, the honest failure is to show too much and say so.
 */
public final class LegLeaderboardPolicy {

    /**
     * @param filter              who survives every applicable rule, or null for everyone
     * @param viewRoleIds         the viewer's own filter, echoed back for the embed
     * @param hiddenRoleCount     how many roles the admin has hidden, for the footer
     * @param membersUnavailable  true when role rules could not be applied at all
     */
    public record Result(Predicate<String> filter,
                         List<String> viewRoleIds,
                         int hiddenRoleCount,
                         boolean membersUnavailable) {}

    private LegLeaderboardPolicy() {}

    /**
     * Builds the effective filter for one render.
     *
     * @param viewRoleIds roles the viewer chose to narrow to, empty for everyone
     */
    public static Result build(Guild guild, List<String> viewRoleIds) {
        LegConfigData config = LegConfigService.getConfig(guild.getId());

        List<String> hiddenRoles = config.getLeaderboardHiddenRoles();
        boolean hideDeparted = config.isHideDepartedOnLeaderboard();
        boolean wantsMemberRules = !hiddenRoles.isEmpty() || hideDeparted || !viewRoleIds.isEmpty();

        if (!wantsMemberRules)
            return new Result(null, List.of(), 0, false);

        // Anything below reads the member cache. Without a complete one the answers
        // would be wrong in the direction that looks like data loss.
        if (!guild.isLoaded())
            return new Result(null, List.of(), hiddenRoles.size(), true);

        Set<String> hidden = holdersOf(guild, hiddenRoles);
        Set<String> visible = viewRoleIds.isEmpty() ? null : holdersOf(guild, viewRoleIds);

        Predicate<String> filter = userId -> {
            if (hidden.contains(userId))
                return false;
            if (visible != null && !visible.contains(userId))
                return false;
            // getMemberById is exact here: the guild is loaded, so a miss means gone.
            return !hideDeparted || guild.getMemberById(userId) != null;
        };

        return new Result(filter, viewRoleIds, hiddenRoles.size(), false);
    }

    /**
     * Everybody holding at least one of these roles. Empty when the list is empty.
     *
     * <p><b>Any, not all.</b> This deliberately does not use
     * {@code Guild#getMembersWithRoles}, which returns only members holding <em>every</em>
     * role passed to it. Both callers mean "any": hiding two roles should hide the holders
     * of either, and the viewer's filter reads as "<i>role A</i> or <i>role B</i>" in the
     * embed that renders it. With one role the two are indistinguishable, which is why the
     * mismatch stayed invisible - it only bites the first admin to hide a second role, and
     * then silently, by putting people back on a leaderboard they were meant to be off.
     *
     * <p>One pass over the member cache rather than one pass per role: the cache is the
     * expensive thing to walk in a large guild, and the role test is a set lookup.
     */
    private static Set<String> holdersOf(Guild guild, List<String> roleIds) {
        if (roleIds.isEmpty())
            return Set.of();

        Set<String> wanted = new HashSet<>(roleIds);
        Set<String> holders = new HashSet<>();

        for (Member member : guild.getMemberCache()) {
            for (Role role : member.getRoles()) {
                if (wanted.contains(role.getId())) {
                    holders.add(member.getId());
                    break;
                }
            }
        }

        return holders;
    }
}
