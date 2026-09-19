package org.bunnys.utils;

import net.dv8tion.jda.api.entities.Member;
import org.bunnys.beastars.commands.admin.AdminService;

/**
 * Framework-facing shim for the bot-admin check.
 *
 * <p>{@code InteractionListener} enforces {@code setAdminOnly(true)} through this
 * class and must not depend on a feature package, so the predicate itself lives in
 * {@link AdminService} - one implementation, one cache, one place to change the
 * rules - and this simply forwards to it.
 */
public final class AdminUtils {

    private AdminUtils() {}

    /** True for native Discord administrators and for holders of a configured Bot Admin role. */
    public static boolean hasBotAdmin(Member member) {
        return AdminService.isAdmin(member);
    }
}
