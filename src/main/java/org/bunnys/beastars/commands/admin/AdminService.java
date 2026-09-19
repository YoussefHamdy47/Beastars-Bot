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
import org.bunnys.handler.database.DB;
import org.bunnys.utils.BunnyLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bot-admin authority: who counts as an admin.
 *
 * <p>Absorbs the former {@code AdminRoleManager}. The permission predicate lives here
 * too - it used to be copy-pasted, subtly differently, into the admin command, the
 * dashboard button handler and the modal handler.
 *
 * <p>The audit trail moved to {@link AuditService}, which needs a {@code Guild} and a
 * fallback channel to dispatch its Discord copy - dependencies that have no business in
 * a pure permission lookup.
 *
 * <p>The old {@code GuildAdminData} POJO is gone. It mapped its id field to
 * {@code guildID}, which the POJO codec never wired to {@code _id}, so the field
 * was silently always null; for a one-array document a raw {@link Document} read
 * is both correct and cheaper than standing up a codec.
 */
public final class AdminService {

    public static final String ROLES_COLLECTION = "guild_admins";

    private static final String ROLE_FIELD = "adminRoleIds";

    /**
     * Consulted on every admin command, dashboard click and modal submit - the
     * single busiest read in the admin feature, and the one most worth caching.
     */
    private static final Cache<String, List<String>> ADMIN_ROLES = CacheRegistry.register("admin.admin_roles", Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .recordStats()
            .build());

    private AdminService() {}

    // ------------------------------------------------------------------
    // Authority
    // ------------------------------------------------------------------

    /**
     * True if the member is a native Discord administrator, or holds a role this
     * guild has authorised as a Bot Admin.
     *
     * <p>This is <em>the</em> admin check. Nothing in the feature re-implements it.
     */
    public static boolean isAdmin(Member member) {
        if (member == null)
            return false;
        if (member.hasPermission(Permission.ADMINISTRATOR))
            return true;

        List<String> authorised = getAdminRoles(member.getGuild().getId());
        if (authorised.isEmpty())
            return false;

        for (Role role : member.getRoles())
            if (authorised.contains(role.getId()))
                return true;
        return false;
    }

    /** Returns the configured Bot Admin role ids, never null. */
    public static List<String> getAdminRoles(String guildId) {
        List<String> cached = ADMIN_ROLES.get(guildId, k -> {
            try {
                Document doc = DB.getCollection(Document.class, ROLES_COLLECTION)
                        .find(Filters.eq("_id", k))
                        .first();

                List<String> ids = doc == null ? null : doc.getList(ROLE_FIELD, String.class);
                return ids == null ? List.of() : List.copyOf(ids);
            } catch (Exception e) {
                BunnyLog.error("[AdminService] Failed to read admin roles for guild " + k, e);
                return null; // Keeps the failure out of the cache.
            }
        });

        return cached != null ? cached : List.of();
    }

    /**
     * Replaces the guild's Bot Admin role list wholesale.
     *
     * <p>A single atomic {@code $set} rather than the previous add/remove pair,
     * because the UI is now one multi-select picker whose submission <em>is</em> the
     * complete list. An empty list clears it.
     *
     * <p>On success the cache is written with the exact list that was stored, so the
     * confirmation embed the caller builds from the return value shows live values
     * and never a stale read.
     *
     * @return the stored list, or null if the write failed.
     */
    public static List<String> setAdminRoles(String guildId, List<String> roleIds) {
        List<String> stored = List.copyOf(roleIds);
        try {
            DB.getCollection(Document.class, ROLES_COLLECTION).updateOne(
                    Filters.eq("_id", guildId),
                    Updates.set(ROLE_FIELD, new ArrayList<>(stored)),
                    new UpdateOptions().upsert(true));

            ADMIN_ROLES.put(guildId, stored);
            return stored;
        } catch (Exception e) {
            BunnyLog.error("[AdminService] Failed to set admin roles for guild " + guildId, e);
            ADMIN_ROLES.invalidate(guildId);
            return null;
        }
    }

}
