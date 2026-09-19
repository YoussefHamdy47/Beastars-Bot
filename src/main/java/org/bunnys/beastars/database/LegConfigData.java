package org.bunnys.beastars.database;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import java.util.ArrayList;
import java.util.List;

public class LegConfigData {

    @BsonId
    private String guildId;

    @BsonProperty("enabled")
    private boolean enabled;

    @BsonProperty("waitPeriodHours")
    private int waitPeriodHours;

    @BsonProperty("allowedRoles")
    private List<String> allowedRoles;

    @BsonProperty("bannedRoles")
    private List<String> bannedRoles;

    @BsonProperty("bypassWaitRoles")
    private List<String> bypassWaitRoles;

    /**
     * Roles hidden from the leaderboard.
     *
     * <p>A separate axis from the three participation buckets above: a role can be
     * perfectly entitled to play and still not belong on a public ranking - staff,
     * alt accounts, bots given a profile by an admin edit. Hiding is display-only and
     * changes nobody's stats.
     */
    @BsonProperty("leaderboardHiddenRoles")
    private List<String> leaderboardHiddenRoles;

    /**
     * Boxed on purpose, both of them.
     *
     * <p>The POJO codec gives a missing {@code boolean} the value {@code false}, which
     * would silently opt every already-configured guild <em>out</em> of behaviour that
     * should be on by default. Boxed, an absent field arrives as null and the getter
     * answers with the intended default instead.
     */
    @BsonProperty("hideBannedOnLeaderboard")
    private Boolean hideBannedOnLeaderboard;

    @BsonProperty("hideDepartedOnLeaderboard")
    private Boolean hideDepartedOnLeaderboard;

    public LegConfigData() {
        // Safe defaults
        this.enabled = true;
        this.waitPeriodHours = 24;
        this.allowedRoles = new ArrayList<>();
        this.bannedRoles = new ArrayList<>();
        this.bypassWaitRoles = new ArrayList<>();
        this.leaderboardHiddenRoles = new ArrayList<>();
    }

    public LegConfigData(String guildId) {
        this();
        this.guildId = guildId;
    }

    // ... Getters and Setters ...
    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getWaitPeriodHours() { return waitPeriodHours; }
    public void setWaitPeriodHours(int waitPeriodHours) { this.waitPeriodHours = waitPeriodHours; }
    public List<String> getAllowedRoles() { return allowedRoles; }
    public void setAllowedRoles(List<String> allowedRoles) { this.allowedRoles = allowedRoles; }
    public List<String> getBannedRoles() { return bannedRoles; }
    public void setBannedRoles(List<String> bannedRoles) { this.bannedRoles = bannedRoles; }
    public List<String> getBypassWaitRoles() { return bypassWaitRoles; }
    public void setBypassWaitRoles(List<String> bypassWaitRoles) { this.bypassWaitRoles = bypassWaitRoles; }

    /** Never null, so callers can iterate a guild that predates this setting. */
    public List<String> getLeaderboardHiddenRoles() {
        return leaderboardHiddenRoles == null ? new ArrayList<>() : leaderboardHiddenRoles;
    }

    public void setLeaderboardHiddenRoles(List<String> leaderboardHiddenRoles) {
        this.leaderboardHiddenRoles = leaderboardHiddenRoles;
    }

    /** Defaults on: a banned member showing up as an apex predator makes the ban look ignored. */
    public boolean isHideBannedOnLeaderboard() {
        return hideBannedOnLeaderboard == null || hideBannedOnLeaderboard;
    }

    public void setHideBannedOnLeaderboard(Boolean hideBannedOnLeaderboard) {
        this.hideBannedOnLeaderboard = hideBannedOnLeaderboard;
    }

    /** Defaults on: a ranking full of people who left is a ranking nobody can act on. */
    public boolean isHideDepartedOnLeaderboard() {
        return hideDepartedOnLeaderboard == null || hideDepartedOnLeaderboard;
    }

    public void setHideDepartedOnLeaderboard(Boolean hideDepartedOnLeaderboard) {
        this.hideDepartedOnLeaderboard = hideDepartedOnLeaderboard;
    }
}