package com.photerest.app.data;

import androidx.room.*;
import java.util.List;

@Dao
public interface StoreDao {
    @Query("SELECT * FROM albums ORDER BY rowid") List<Store.Album> albums();
    @Insert(onConflict = OnConflictStrategy.IGNORE) void addAlbums(List<Store.Album> albums);
    @Query("DELETE FROM albums WHERE id = :id") void removeAlbum(String id);
    @Query("SELECT * FROM profiles WHERE id = 1") Store.Profile profile();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void saveProfile(Store.Profile profile);
    @Query("SELECT * FROM folders ORDER BY id") List<Store.Folder> folders();
    @Insert(onConflict = OnConflictStrategy.IGNORE) long addFolder(Store.Folder folder);
    @Query("UPDATE folders SET name = :name WHERE id = :id") void renameFolder(long id, String name);
    @Query("DELETE FROM folders WHERE id = :id AND id != 1") void deleteFolder(long id);
    @Query("DELETE FROM favorites WHERE folderId = :id") void clearFolder(long id);
    @Query("SELECT * FROM photos WHERE uri IN (SELECT uri FROM favorites) ORDER BY rowid DESC") List<Store.Photo> photos();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void savePhoto(Store.Photo photo);
    @Query("SELECT * FROM favorites") List<Store.Favorite> favorites();
    @Insert(onConflict = OnConflictStrategy.IGNORE) void favorite(Store.Favorite favorite);
    @Query("DELETE FROM favorites WHERE uri = :uri") void unfavorite(String uri);
    @Query("DELETE FROM photos WHERE uri NOT IN (SELECT uri FROM favorites)") void prunePhotos();
    @Query("DELETE FROM favorites WHERE uri = :uri AND folderId = :folderId") void removeFromFolder(String uri, long folderId);
}
