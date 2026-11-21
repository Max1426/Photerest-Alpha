package com.photerest.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class SearchIdentity(val uri: String, val engine: SearchEngine, val fingerprint: String)
internal data class SavedSearch(val results: List<SearchResult>, val time: Long, val persisted: Boolean = true)

/** Separate from the user's profile and favorites database; results have no expiry. */
internal class SearchHistory(context: Context) : SQLiteOpenHelper(context, "image-search-history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE results (
            uri TEXT NOT NULL, engine TEXT NOT NULL, fingerprint TEXT NOT NULL,
            searched INTEGER NOT NULL, items TEXT NOT NULL,
            PRIMARY KEY(uri, engine, fingerprint))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun read(key: SearchIdentity): SavedSearch? = readableDatabase.query("results",
        arrayOf("searched", "items"), "uri=? AND engine=? AND fingerprint=?",
        arrayOf(key.uri, key.engine.id, key.fingerprint), null, null, null).use { cursor ->
        if (!cursor.moveToFirst()) return null
        val items = JSONArray(cursor.getString(1))
        SavedSearch((0 until items.length()).map {
            val row = items.getJSONObject(it)
            SearchResult(row.getString("image"), row.getString("thumbnail"),
                row.optString("page").takeIf { page -> page.startsWith("https://") || page.startsWith("http://") },
                row.getString("title"))
        }, cursor.getLong(0))
    }

    fun save(key: SearchIdentity, results: List<SearchResult>, time: Long) {
        val items = JSONArray()
        results.forEach { result ->
            items.put(JSONObject().put("image", result.image).put("thumbnail", result.thumbnail)
                .put("page", result.page ?: JSONObject.NULL).put("title", result.title))
        }
        writableDatabase.insertOrThrow("results", null, ContentValues().apply {
            put("uri", key.uri); put("engine", key.engine.id); put("fingerprint", key.fingerprint)
            put("searched", time); put("items", items.toString())
        })
    }

    fun replace(key: SearchIdentity, results: List<SearchResult>, time: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("results", "uri=? AND engine=? AND fingerprint=?", arrayOf(key.uri, key.engine.id, key.fingerprint))
            save(key, results, time)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

internal object SearchHistoryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<SearchIdentity, Deferred<SavedSearch>>()
    private var database: SearchHistory? = null
    @Synchronized private fun database(context: Context): SearchHistory =
        database ?: SearchHistory(context.applicationContext).also { database = it }

    suspend fun identify(context: Context, photo: Photo, engine: SearchEngine): SearchIdentity = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(photo.uri))?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: throw java.io.IOException("Photo unavailable")
        SearchIdentity(photo.uri, engine, digest.digest().joinToString("") { "%02x".format(it) })
    }

    suspend fun read(context: Context, key: SearchIdentity): SavedSearch? = withContext(Dispatchers.IO) {
        database(context).read(key)
    }

    @Synchronized fun pending(key: SearchIdentity): Deferred<SavedSearch>? = inFlight[key]?.takeIf { it.isActive }

    /** The app owns approved searches so closing a panel doesn't discard a paid result. */
    @Synchronized
    fun search(context: Context, key: SearchIdentity, refresh: Boolean,
        action: suspend () -> List<SearchResult>): Deferred<SavedSearch> {
        inFlight[key]?.takeIf { it.isActive }?.let { return it }
        val job = scope.async {
            if (!refresh) database(context).read(key)?.let { return@async it }
            val results = action()
            val time = System.currentTimeMillis()
            val saved = runCatching { database(context).replace(key, results, time) }.isSuccess
            SavedSearch(results, time, saved)
        }
        inFlight[key] = job
        job.invokeOnCompletion { synchronized(this) { if (inFlight[key] === job) inFlight.remove(key) } }
        return job
    }
}
