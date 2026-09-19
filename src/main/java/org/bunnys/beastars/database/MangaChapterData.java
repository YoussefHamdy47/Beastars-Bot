package org.bunnys.beastars.database;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import java.util.List;

public class MangaChapterData {
    @BsonId
    private String id;

    @BsonProperty("pages")
    private List<String> pages;

    @BsonProperty("last_updated")
    private long lastUpdated;

    public MangaChapterData() {}

    public MangaChapterData(String id, List<String> pages, long lastUpdated) {
        this.id = id;
        this.pages = pages;
        this.lastUpdated = lastUpdated;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public List<String> getPages() { return pages; }
    public void setPages(List<String> pages) { this.pages = pages; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
}
