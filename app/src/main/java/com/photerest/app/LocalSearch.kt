package com.photerest.app

import android.content.ContentValues
import android.content.Context
import com.photerest.ai.AiClient
import com.photerest.ai.ModelSpec
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Local semantic search based on Google AI Edge Gallery's Apache-2.0 implementation:
 * EmbeddingGemma-2 (740M, generic GPU/CPU file from Gallery's model_allowlists/1_0_23.json),
 * MediaPipe UniversalEmbedder (tasks-retrieval 1.2.0) and Gallery's TGA image encoding.
 *
 * The public Gallery source ships the GPU/CPU variant of the model; the per-SoC NPU variant is not
 * part of the open-source app, so this feature intentionally uses GPU with CPU fallback.
 */
internal object LocalSearchModel {
    const val FILE = "embeddinggemma-2-740m.litertlm"
    const val BYTES = 484_622_336L
    private const val REVISION = "86b903a293f96d042bb92e1b7af333d94114458f"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/$REVISION/$FILE?download=true"

    fun supported(): Boolean =
        Build.VERSION.SDK_INT >= 31 && "arm64-v8a" in Build.SUPPORTED_ABIS

    fun file(context: Context): File = File(context.noBackupFilesDir, "local-search/$FILE")
    fun downloaded(context: Context): Boolean = file(context).length() == BYTES


}

internal class LocalSearchEmbedder(private val context: Context) : AutoCloseable {
    private val client = AiClient(context)
    var metadata = Bundle(); private set
    val accelerator: String get() = "${metadata.getString("backend") ?: "共享中心"} · PID ${metadata.getInt("pid")}" 
    suspend fun initialize() { metadata = client.load().metadata }
    suspend fun embedText(text: String): FloatArray = client.text(text).let { metadata = it.metadata; it.vector }
    suspend fun embedImage(tga: ByteArray): FloatArray = client.image(tga).let { metadata = it.metadata; it.vector }
    suspend fun status() = client.status()
    fun disconnect() = client.close()
    override fun close() = client.close()

    companion object {
        private const val TAG = "LocalSearch"

        /** Gallery's ImageUtils.encodeTga: UniversalEmbedder accepts TGA bytes. */
        fun encodeTga(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
                val copy = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                try {
                    copy.getPixels(pixels, 0, width, 0, 0, width, height)
                } finally {
                    copy.recycle()
                }
            } else {
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            }
            val tga = ByteArray(20 + width * height * 4)
            val buffer = ByteBuffer.wrap(tga).order(ByteOrder.LITTLE_ENDIAN)
            tga[0] = 2
            tga[2] = 2
            tga[12] = (width and 0xFF).toByte()
            tga[13] = ((width shr 8) and 0xFF).toByte()
            tga[14] = (height and 0xFF).toByte()
            tga[15] = ((height shr 8) and 0xFF).toByte()
            tga[16] = 32
            tga[17] = 0x28
            buffer.position(20)
            buffer.asIntBuffer().put(pixels)
            return tga
        }

        /** Decodes a MediaStore photo into a TGA suitable for UniversalEmbedder. */
        fun loadTga(context: Context, photo: Photo): ByteArray {
            val bitmap = checkNotNull(
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(photo.uri))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            ) { "无法读取照片：${photo.name}" }
            val longest = max(bitmap.width, bitmap.height)
            val scaled = if (longest > 1024) {
                val ratio = 1024f / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                ).also { bitmap.recycle() }
            } else {
                bitmap
            }
            return try {
                encodeTga(scaled)
            } finally {
                scaled.recycle()
            }
        }
    }
}

/** SQLite vector index for the local semantic search, keyed by MediaStore URI. */
internal class LocalSearchIndex(context: Context) : SQLiteOpenHelper(context, "local-search.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE vectors (uri TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, vector BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun read(photo: Photo): FloatArray? = readableDatabase.rawQuery(
        "SELECT vector FROM vectors WHERE uri = ? AND fingerprint = ?",
        arrayOf(photo.uri, fingerprint(photo))
    ).use { row ->
        if (!row.moveToFirst()) return null
        val bytes = row.getBlob(0)
        if (bytes.size != 768 * Float.SIZE_BYTES) return null
        ByteBuffer.wrap(bytes).asFloatBuffer().let { buffer -> FloatArray(768).also { buffer.get(it) } }
    }

    fun write(photo: Photo, vector: FloatArray) {
        val bytes = ByteArray(vector.size * Float.SIZE_BYTES)
        ByteBuffer.wrap(bytes).asFloatBuffer().put(vector)
        writableDatabase.insertWithOnConflict(
            "vectors", null,
            ContentValues().apply {
                put("uri", photo.uri)
                put("fingerprint", fingerprint(photo))
                put("vector", bytes)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clear() {
        writableDatabase.delete("vectors", null, null)
    }

    fun retain(allowedUris: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT uri FROM vectors", null).use { rows ->
                while (rows.moveToNext()) {
                    val uri = rows.getString(0)
                    if (uri !in allowedUris) db.delete("vectors", "uri = ?", arrayOf(uri))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun fingerprint(photo: Photo): String =
        "gallery-gemma2-740m:1:${photo.albumId}:${photo.added}:${photo.size}:${photo.name}"
}

internal fun rankPhotos(query: FloatArray, entries: List<Pair<Photo, FloatArray>>): List<PhotoMatch> =
    entries.map { (photo, vector) ->
        var dot = 0f
        for (i in query.indices) dot += query[i] * vector[i]
        PhotoMatch(photo, dot.coerceIn(-1f, 1f))
    }.sortedByDescending { it.similarity }

internal data class PhotoMatch(val photo: Photo, val similarity: Float)
