package org.bunnys.beastars.database;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import java.util.ArrayList;
import java.util.List;

public class LegData {

    @BsonId
    private String id;

    @BsonProperty("guildId")
    private String guildId;

    @BsonProperty("userId")
    private String userId;

    @BsonProperty("legsGiven")
    private int legsGiven;

    @BsonProperty("legsReceived")
    private int legsReceived;

    @BsonProperty("banned")
    private boolean banned;

    @BsonProperty("givenTo")
    private List<String> givenTo;

    @BsonProperty("receivedFrom")
    private List<String> receivedFrom;

    public LegData() {
        this.legsGiven = 0;
        this.legsReceived = 0;
        this.banned = false;
        this.givenTo = new ArrayList<>();
        this.receivedFrom = new ArrayList<>();
    }

    public LegData(String guildId, String userId) {
        this.id = guildId + ":" + userId;
        this.guildId = guildId;
        this.userId = userId;
        this.legsGiven = 0;
        this.legsReceived = 0;
        this.banned = false;
        this.givenTo = new ArrayList<>();
        this.receivedFrom = new ArrayList<>();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public int getLegsGiven() { return legsGiven; }
    public void setLegsGiven(int legsGiven) { this.legsGiven = legsGiven; }
    public int getLegsReceived() { return legsReceived; }
    public void setLegsReceived(int legsReceived) { this.legsReceived = legsReceived; }
    public boolean isBanned() { return banned; }
    public void setBanned(boolean banned) { this.banned = banned; }

    // Safe getters that prevent NullPointerExceptions if older DB documents are missing the arrays
    public List<String> getGivenTo() { return givenTo != null ? givenTo : new ArrayList<>(); }
    public void setGivenTo(List<String> givenTo) { this.givenTo = givenTo; }
    public List<String> getReceivedFrom() { return receivedFrom != null ? receivedFrom : new ArrayList<>(); }
    public void setReceivedFrom(List<String> receivedFrom) { this.receivedFrom = receivedFrom; }
}