package com.photerest.app.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {Store.Album.class, Store.Profile.class, Store.Folder.class, Store.Photo.class, Store.Favorite.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract StoreDao store();
}
