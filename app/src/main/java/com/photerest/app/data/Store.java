package com.photerest.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/** Only references and organization are stored here; original media is never modified. */
public final class Store {
    @Entity(tableName = "albums")
    public static class Album {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String name = "";
        public Album() {}
        @Ignore public Album(String id, String name) { this.id = id; this.name = name; }
    }
    @Entity(tableName = "profiles")
    public static class Profile {
        @PrimaryKey public int id = 1;
        @NonNull public String name = "";
        public String avatar;
    }
    @Entity(tableName = "folders")
    public static class Folder {
        @PrimaryKey(autoGenerate = true) public long id;
        @NonNull public String name = "";
        public Folder() {}
        @Ignore public Folder(long id, String name) { this.id = id; this.name = name; }
    }
    @Entity(tableName = "photos")
    public static class Photo {
        @PrimaryKey @NonNull public String uri = "";
        @NonNull public String name = "";
        @NonNull public String albumId = "";
        @NonNull public String mime = "image/jpeg";
        public int width;
        public int height;
        public long size;
        public long taken;
        public long added;
    }
    @Entity(tableName = "favorites", primaryKeys = {"uri", "folderId"})
    public static class Favorite {
        @NonNull public String uri = "";
        public long folderId;
        public Favorite() {}
        @Ignore public Favorite(String uri, long folderId) { this.uri = uri; this.folderId = folderId; }
    }
}
