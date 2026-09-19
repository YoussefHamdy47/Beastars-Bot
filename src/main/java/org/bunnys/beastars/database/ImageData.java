package org.bunnys.beastars.database;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

public class ImageData {

    @BsonId
    private ObjectId id;

    @BsonProperty("guildID")
    private String guildID;

    @BsonProperty("__v")
    private Integer v;

    @BsonProperty("images")
    private List<ImageEntry> images;

    public ImageData() {}

    public ImageData(String guildID, List<ImageEntry> images) {
        this.id = new ObjectId(); // Natively generate an ObjectId for new servers
        this.guildID = guildID;
        this.images = images != null ? images : new ArrayList<>();
    }

    public ObjectId getId() { return id; }
    public void setId(ObjectId id) { this.id = id; }

    public String getGuildID() { return guildID; }
    public void setGuildID(String guildID) { this.guildID = guildID; }

    public Integer getV() { return v; }
    public void setV(Integer v) { this.v = v; }

    public List<ImageEntry> getImages() { return images; }
    public void setImages(List<ImageEntry> images) { this.images = images; }

    public static class ImageEntry {

        @BsonProperty("_id")
        private ObjectId id; // Fixes the Mongoose sub-document crash

        @BsonProperty("name")
        private String name;

        @BsonProperty("imageID")
        private String imageID;

        @BsonProperty("URL")
        private String url;

        public ImageEntry() {}

        public ImageEntry(String name, String imageID, String url) {
            this.id = new ObjectId(); // Natively generate an ObjectId for new images
            this.name = name;
            this.imageID = imageID;
            this.url = url;
        }

        public ObjectId getId() { return id; }
        public void setId(ObjectId id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getImageID() { return imageID; }
        public void setImageID(String imageID) { this.imageID = imageID; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}