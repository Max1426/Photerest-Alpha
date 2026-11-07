package com.photerest.app

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.photerest.app.data.AppDatabase
import com.photerest.app.data.Store
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

data class Photo(
    val uri: String, val name: String, val albumId: String, val mime: String,
    val width: Int, val height: Int, val size: Long, val taken: Long, val added: Long,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
    fun stored() = Store.Photo().also {
        it.uri = uri; it.name = name; it.albumId = albumId; it.mime = mime
        it.width = width; it.height = height; it.size = size; it.taken = taken; it.added = added
    }
    companion object {
        fun from(p: Store.Photo) = Photo(p.uri, p.name, p.albumId, p.mime, p.width, p.height, p.size, p.taken, p.added)
    }
}
data class Album(val id: String, val name: String, val count: Int = 0, val cover: Photo? = null)
data class Folder(val id: Long, val name: String)
data class Membership(val uri: String, val folderId: Long)
enum class Access { FULL, PARTIAL, NONE }
data class PhotoState(
    val ready: Boolean = false, val busy: Boolean = false, val access: Access = Access.NONE,
    val deviceAlbums: List<Album> = emptyList(), val imported: List<Album> = emptyList(),
    val library: Map<String, Photo> = emptyMap(), val feed: List<Photo> = emptyList(),
    val selectedAlbum: String? = null, val folders: List<Folder> = emptyList(),
    val favorites: List<Photo> = emptyList(), val memberships: List<Membership> = emptyList(),
    val refreshing: Boolean = false, val switching: Boolean = false, val pendingAlbum: String? = null, val readError: Boolean = false,
    val profileName: String? = null, val avatar: String? = null, val message: String? = null,
) {
    fun isFavorite(photo: Photo) = memberships.any { it.uri == photo.uri }
    fun available(photo: Photo): Boolean = sameSource(photo, library[photo.uri])
}

/** Guards against a recycled MediaStore ID being mistaken for an old favorite. */
fun sameSource(saved: Photo, current: Photo?): Boolean = current != null &&
    (saved.added == 0L || current.added == saved.added) && current.name == saved.name

fun <T> nextPermutation(items: List<T>, previous: List<T>, random: Random = Random.Default): List<T> {
    val unique = items.distinct()
    if (unique.size < 2) return unique
    val next = unique.shuffled(random)
    return if (next == previous) next.drop(1) + next.first() else next
}

object Gallery {
    fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun access(context: Context): Access = when {
        Build.VERSION.SDK_INT >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        Build.VERSION.SDK_INT <= 32 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        else -> Access.NONE
    }
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun scan(context: Context): Pair<List<Album>, List<Photo>> {
        if (access(context) == Access.NONE) return emptyList<Album>() to emptyList()
        val cols = mutableListOf("_id", "_display_name", "bucket_id", "bucket_display_name", "mime_type", "width", "height", "_size", "datetaken", "date_added", "orientation")
        if (Build.VERSION.SDK_INT >= 29) cols.add("volume_name")
        val photos = ArrayList<Photo>()
        val names = linkedMapOf<String, String>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(), null, null, "date_added DESC, _id DESC")?.use { c ->
            fun string(name: String) = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
            fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
            while (c.moveToNext()) {
                val volume = if (Build.VERSION.SDK_INT >= 29) string("volume_name") else "external"
                val album = "$volume:${string("bucket_id")}"
                names[album] = string("bucket_display_name").ifBlank { "未命名相册" }
                val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val rotated = long("orientation") % 180L != 0L
                val w = long("width").toInt(); val h = long("height").toInt()
                photos.add(Photo(ContentUris.withAppendedId(base, long("_id")).toString(), string("_display_name").ifBlank { "未命名照片" }, album, string("mime_type").ifBlank { "image/jpeg" }, if (rotated) h else w, if (rotated) w else h, long("_size"), long("datetaken"), long("date_added")))
            }
        }
        val albums = photos.groupBy { it.albumId }.map { (id, items) -> Album(id, names.getValue(id), items.size, items.first()) }
        return albums to photos
    }
    fun metadata(context: Context, photo: Photo): List<Pair<String, String>> {
        var taken = photo.taken.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(it)) }
        var location = "未记录"
        try {
            var uri = Uri.parse(photo.uri)
            val canReadLocation = Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= 29 && canReadLocation) uri = MediaStore.setRequireOriginal(uri)
            context.contentResolver.openInputStream(uri)?.use {
                val exif = ExifInterface(it)
                val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                if (!date.isNullOrBlank()) taken = date
                val coords = exif.latLong
                location = if (coords != null) String.format(Locale.CHINA, "纬度 %.6f，经度 %.6f", coords[0], coords[1])
                else if (!canReadLocation) "未授权读取照片位置" else "未记录"
            }
        } catch (_: Exception) { /* Basic MediaStore fields remain usable if EXIF is unsupported. */ }
        return listOf("文件名" to photo.name, "格式" to photo.mime, "像素尺寸" to if (photo.width > 0 && photo.height > 0) "${photo.width} × ${photo.height}" else "未记录", "文件大小" to android.text.format.Formatter.formatFileSize(context, photo.size), "拍摄时间" to (taken ?: "未记录"), "拍摄位置" to location)
    }
    suspend fun openOriginal(context: Context, photo: Photo): String? {
        fun unavailable(): String = if (access(context) == Access.FULL) "原图已删除或无法读取" else "照片访问受限，请重新授权"
        val uri = Uri.parse(photo.uri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return "找不到这张照片的系统相册原图"
        return try {
            val mime = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val columns = arrayOf("_display_name", "date_added", "mime_type")
                val type = resolver.query(uri, columns, null, null, null)?.use { row ->
                    if (!row.moveToFirst()) return@use null
                    if (!sameSource(photo, photo.copy(name = row.getString(0).orEmpty(), added = row.getLong(1)))) return@use null
                    row.getString(2)?.takeIf { it.startsWith("image/") } ?: "image/*"
                } ?: return@withContext null
                resolver.openFileDescriptor(uri, "r")?.use { type }
            } ?: return unavailable()
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            null
        } catch (_: ActivityNotFoundException) {
            "没有可用的相册或图片查看应用"
        } catch (_: SecurityException) {
            "照片访问受限，请重新授权"
        } catch (_: IOException) {
            unavailable()
        } catch (_: IllegalArgumentException) {
            "找不到这张照片的系统相册原图"
        }
    }
    suspend fun share(context: Context, photo: Photo) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val name = photo.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(64).ifBlank { "photo.jpg" }
        val file = File(dir, "${System.nanoTime()}_$name")
        try {
            context.contentResolver.openInputStream(Uri.parse(photo.uri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = photo.mime; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(photo.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享照片"))
            }
        } catch (e: Exception) { file.delete(); throw e }
    }
}

object PhotoBitmaps {
    private val decoders = Semaphore(2)
    fun clear() { cache.evictAll() }
    fun peek(uri: String, revision: Long) = cache.get("$uri:false:$revision")
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun load(context: Context, uriString: String, large: Boolean = false, revision: Long = 0): Bitmap? = decoders.withPermit { withContext(Dispatchers.IO) {
        val key = "$uriString:$large:$revision"
        cache.get(key)?.let { return@withContext it }
        try {
            val uri = Uri.parse(uriString)
            val thumbnail = if (!large && Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(512, 768), null) }.getOrNull()
            } else null
            val bitmap = thumbnail ?: run {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                var sample = 1
                val limit = if (large) 2048 else 768
                while (maxOf(options.outWidth, options.outHeight) / sample > limit) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
                val matrix = Matrix()
                runCatching { context.contentResolver.openInputStream(uri)?.use {
                    val exif = ExifInterface(it)
                    matrix.postRotate(exif.rotationDegrees.toFloat())
                    if (exif.isFlipped) matrix.postScale(-1f, 1f)
                } }
                if (!matrix.isIdentity) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() } else decoded
            }
            cache.put(key, bitmap); bitmap
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    } }
}

class PhotoModel(app: Application) : AndroidViewModel(app) {
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "photerest.db").build()
    private val dao = db.store()
    private val mutex = Mutex()
    @Volatile private var selectionEpoch = 0
    private val mutable = MutableStateFlow(PhotoState())
    val state = mutable.asStateFlow()
    val settings = SettingsStore(app)
    private val tabFeeds = mutableMapOf<String?, List<Photo>>()
    private val viewports = mutableMapOf<String?, TabViewport>()
    private var preloadJob: Job? = null
    val hapticRequests = java.util.concurrent.atomic.AtomicInteger(0)
    fun viewport(id: String?) = viewports.getOrPut(id) { TabViewport() }
    init { reload() }
    private fun launchOperation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { message("操作未完成，请检查照片访问权限后重试") }
    }
    fun message(value: String?) { mutable.value = mutable.value.copy(message = value) }
    private fun reconcile(previous: List<Photo>?, pool: List<Photo>, shuffle: Boolean): List<Photo> {
        if (previous == null || shuffle) return nextPermutation(pool, previous.orEmpty())
        val byId = pool.associateBy { it.uri }
        val oldIds = previous.map { it.uri }.toHashSet()
        return previous.mapNotNull { byId[it.uri] } + pool.filter { it.uri !in oldIds }
    }
    fun reload(shuffle: Boolean = false) = launchOperation {
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true, refreshing = shuffle)
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    dao.addFolder(Store.Folder(1, "默认收藏夹"))
                    val (albums, photos) = Gallery.scan(getApplication())
                    val profile = dao.profile()
                    PhotoState(ready = true, access = Gallery.access(getApplication()), deviceAlbums = albums,
                        imported = dao.albums().map { saved -> albums.find { it.id == saved.id } ?: Album(saved.id, saved.name) },
                        library = photos.associateBy { it.uri }, folders = dao.folders().map { Folder(it.id, it.name) },
                        favorites = dao.photos().map(Photo::from), memberships = dao.favorites().map { Membership(it.uri, it.folderId) },
                        profileName = profile?.name, avatar = profile?.avatar)
                }
                val current = mutable.value
                if (snapshot.access != current.access) { tabFeeds.clear(); PhotoBitmaps.clear() }
                val selected = current.selectedAlbum.takeIf { id -> snapshot.imported.any { it.id == id } }
                val keys = listOf<String?>(null) + snapshot.imported.map { it.id }
                tabFeeds.keys.retainAll(keys.toSet())
                val groups = snapshot.library.values.groupBy { it.albumId }
                keys.forEach { id ->
                    val pool = if (id == null) snapshot.library.values.toList() else groups[id].orEmpty()
                    tabFeeds[id] = reconcile(tabFeeds[id], pool, shuffle && id == selected)
                }
                if (snapshot.access == Access.NONE) { selectionEpoch++; preloadJob?.cancel() }
                mutable.value = snapshot.copy(selectedAlbum = selected, feed = tabFeeds[selected].orEmpty(),
                    switching = current.switching && snapshot.access != Access.NONE, pendingAlbum = current.pendingAlbum,
                    message = current.message)
                preloadNeighbours()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                selectionEpoch++; preloadJob?.cancel(); tabFeeds.clear(); PhotoBitmaps.clear()
                mutable.value = mutable.value.copy(ready = true, busy = false, refreshing = false, switching = false,
                    readError = true, feed = emptyList(), library = emptyMap(), access = Gallery.access(getApplication()), message = "读取相册失败，请重新授权后重试")
            }
        }
    }
    private suspend fun warm(id: String?, photos: List<Photo>) {
        if (photos.isEmpty()) return
        val index = viewport(id).grid.firstVisibleItemIndex.coerceIn(0, photos.lastIndex)
        for (photo in photos.drop(index).take(2)) {
            if (PhotoBitmaps.load(getApplication(), photo.uri, revision = photo.added) != null) break
        }
    }
    private fun preloadNeighbours() {
        preloadJob?.cancel()
        preloadJob = launchOperation {
            val current = mutable.value
            val keys = listOf<String?>(null) + current.imported.map { it.id }
            val index = keys.indexOf(current.selectedAlbum).coerceAtLeast(0)
            for (key in keys.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(keys.size))) warm(key, tabFeeds[key].orEmpty())
        }
    }
    fun selectAlbum(id: String?): Job? {
        val current = mutable.value
        if (id != null && current.imported.none { it.id == id }) return null
        if (current.selectedAlbum == id && !current.switching) return null
        val token = ++selectionEpoch
        preloadJob?.cancel()
        mutable.value = current.copy(switching = true, pendingAlbum = id)
        return launchOperation {
            val pool = if (id == null) mutable.value.library.values.toList() else mutable.value.library.values.filter { it.albumId == id }
            val photos = reconcile(tabFeeds[id], pool, false)
            warm(id, photos)
            if (token != selectionEpoch) return@launchOperation
            if (Gallery.access(getApplication()) != mutable.value.access) reload().join()
            if (token != selectionEpoch || mutable.value.access == Access.NONE) return@launchOperation
            if (id != null && mutable.value.imported.none { it.id == id }) {
                mutable.value = mutable.value.copy(switching = false, pendingAlbum = null)
                return@launchOperation
            }
            val currentPool = if (id == null) mutable.value.library.values.toList() else mutable.value.library.values.filter { it.albumId == id }
            val accessible = reconcile(photos, currentPool, false)
            tabFeeds[id] = accessible
            mutable.value = mutable.value.copy(selectedAlbum = id, feed = accessible, switching = false, pendingAlbum = null)
            preloadNeighbours()
        }
    }
    fun importAlbums(ids: Set<String>) = launchOperation {
        withContext(Dispatchers.IO) { dao.addAlbums(mutable.value.deviceAlbums.filter { it.id in ids }.map { Store.Album(it.id, it.name) }) }
        reload()
    }
    fun unlinkAlbum(id: String) = launchOperation { withContext(Dispatchers.IO) { dao.removeAlbum(id) }; reload() }
    fun saveProfile(name: String, selectedAvatar: Uri?, onFailure: () -> Unit = {}, done: () -> Unit) = viewModelScope.launch {
        try {
        val cleaned = name.trim().take(30)
        if (cleaned.isBlank()) { onFailure(); message("请输入昵称"); return@launch }
        withContext(Dispatchers.IO) {
            var avatar = dao.profile()?.avatar
            if (selectedAvatar != null) {
                // Decode a bounded avatar rather than copying an arbitrarily large source file.
                val bitmap = PhotoBitmaps.load(getApplication(), selectedAvatar.toString()) ?: error("Unreadable avatar")
                val file = File(getApplication<Application>().filesDir, "avatar-${System.nanoTime()}.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                val old = avatar
                avatar = Uri.fromFile(file).toString()
                if (old != null) runCatching { File(Uri.parse(old).path!!).delete() }
            }
            dao.saveProfile(Store.Profile().also { it.name = cleaned; it.avatar = avatar })
        }
        reload().join(); done()
        } catch (_: Exception) { onFailure(); message("无法保存个人资料，请重新选择头像后重试") }
    }
    fun createFolder(name: String) = launchOperation {
        val clean = name.trim().take(30)
        if (clean.isBlank()) { message("请输入收藏夹名称"); return@launchOperation }
        if (mutable.value.folders.any { it.name == clean }) { message("已存在同名收藏夹"); return@launchOperation }
        withContext(Dispatchers.IO) { dao.addFolder(Store.Folder(0, clean)) }; reload()
    }
    fun renameFolder(id: Long, name: String) = launchOperation {
        val clean = name.trim().take(30)
        if (clean.isBlank() || mutable.value.folders.any { it.id != id && it.name == clean }) { message("请输入不重复的收藏夹名称"); return@launchOperation }
        withContext(Dispatchers.IO) { dao.renameFolder(id, clean) }; reload()
    }
    fun deleteFolder(id: Long) = launchOperation {
        if (id == 1L) return@launchOperation
        withContext(Dispatchers.IO) { db.runInTransaction { dao.clearFolder(id); dao.deleteFolder(id); dao.prunePhotos() } }; reload()
    }
    fun saveFavorite(photo: Photo, folderIds: Set<Long>, done: () -> Unit) = launchOperation {
        if (mutable.value.profileName == null) { message("请先创建个人档案"); return@launchOperation }
        withContext(Dispatchers.IO) {
            db.runInTransaction {
                dao.unfavorite(photo.uri)
                if (folderIds.isNotEmpty()) {
                    dao.savePhoto(photo.stored())
                    folderIds.forEach { dao.favorite(Store.Favorite(photo.uri, it)) }
                }
                dao.prunePhotos()
            }
        }
        reload().join(); done(); message(if (folderIds.isEmpty()) "已取消收藏" else "已保存到收藏夹")
    }
    override fun onCleared() { db.close(); super.onCleared() }
}
