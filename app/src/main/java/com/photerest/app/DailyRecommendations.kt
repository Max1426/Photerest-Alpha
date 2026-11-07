package com.photerest.app

import android.Manifest
import android.app.Notification
import android.app.AlarmManager
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.*
import com.photerest.app.data.AppDatabase
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal fun Photo.dailyIdentity(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("$uri\n$added\n$name".toByteArray())
    val hex = "0123456789abcdef"
    // Keep the same persisted key, without invoking Formatter 32 times for every photo.
    return buildString(64) { bytes.forEach { byte ->
        val value = byte.toInt() and 255
        append(hex[value ushr 4]); append(hex[value and 15])
    } }
}

private fun Photo.json() = JSONObject().put("uri", uri).put("name", name).put("album", albumId).put("mime", mime)
    .put("width", width).put("height", height).put("size", size).put("taken", taken).put("added", added).toString()
private fun photoFromJson(value: String): Photo = JSONObject(value).let {
    Photo(it.getString("uri"), it.getString("name"), it.getString("album"), it.getString("mime"),
        it.getInt("width"), it.getInt("height"), it.getLong("size"), it.getLong("taken"), it.getLong("added"))
}

/** Separate database: never alters or prunes the user's favorite/photo database. */
internal class DailyHistory(context: Context) : SQLiteOpenHelper(context, "daily-recommendations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE seen (identity TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE meta (id INTEGER PRIMARY KEY, lastIdentity TEXT)")
        db.execSQL("CREATE TABLE deliveries (slot TEXT PRIMARY KEY NOT NULL, photo TEXT NOT NULL, posted INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun seen(): Set<String> = readableDatabase.rawQuery("SELECT identity FROM seen", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    fun last(): String? = readableDatabase.rawQuery("SELECT lastIdentity FROM meta WHERE id=1", null).use { if (it.moveToFirst()) it.getString(0) else null }
    fun clearRound() { writableDatabase.execSQL("DELETE FROM seen") }
    fun hasSlot(slot: String): Boolean = readableDatabase.rawQuery("SELECT slot FROM deliveries WHERE slot=?", arrayOf(slot)).use { it.moveToFirst() }
    fun photo(slot: String): Photo? = readableDatabase.rawQuery("SELECT photo FROM deliveries WHERE slot=?", arrayOf(slot)).use { if (it.moveToFirst()) runCatching { photoFromJson(it.getString(0)) }.getOrNull() else null }
    fun claim(slot: String, photo: Photo, resetRound: Boolean): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (hasSlot(slot)) return false
            db.insertOrThrow("deliveries", null, ContentValues().apply { put("slot", slot); put("photo", photo.json()); put("posted", 0) })
            if (resetRound) db.execSQL("DELETE FROM seen")
            db.insertWithOnConflict("seen", null, ContentValues().apply { put("identity", photo.dailyIdentity()) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("id", 1); put("lastIdentity", photo.dailyIdentity()) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun testRecord(key: String, photo: Photo) { writableDatabase.insertWithOnConflict("deliveries", null, ContentValues().apply { put("slot", key); put("photo", photo.json()); put("posted", 0) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun posted(key: String) { writableDatabase.execSQL("UPDATE deliveries SET posted=1 WHERE slot=?", arrayOf(key)) }
}

class DailyRecommendations private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val statusMutable = MutableStateFlow(prefs.getString("status", "尚未发送推荐")!!)
    val status = statusMutable.asStateFlow()
    private val plannedMutable = MutableStateFlow<Instant?>(null)
    val planned = plannedMutable.asStateFlow()
    internal val history = DailyHistory(context)
    private val mutex = Mutex()
    private val alarms = DailyAlarms(context)
    private var migratedLegacyWork = false

    private fun readWeek(): List<DailyDay> = runCatching {
        val rows = JSONArray(prefs.getString("week", "[]"))
        List(7) { index ->
            val row = rows.optJSONObject(index)
            val quiet = row?.optJSONArray("quiet") ?: JSONArray()
            DailyDay(row?.optBoolean("enabled", true) ?: true, List(quiet.length()) { item ->
                val range = quiet.getJSONObject(item)
                QuietTime(range.getInt("start"), range.getInt("end"))
            })
        }
    }.getOrDefault(List(7) { DailyDay() })

    private fun weekJson(week: List<DailyDay>): String = JSONArray().apply {
        week.forEach { day -> put(JSONObject().put("enabled", day.enabled).put("quiet", JSONArray().apply {
            day.quiet.forEach { put(JSONObject().put("start", it.start).put("end", it.end)) }
        })) }
    }.toString()

    private fun read(): DailyConfig = PhotoSchedule.normalize(DailyConfig(
        enabled = prefs.getBoolean("enabled", false),
        source = runCatching { DailySource.valueOf(prefs.getString("source", "ALL")!!) }.getOrDefault(DailySource.ALL),
        albums = prefs.getStringSet("albums", emptySet())!!.toSet(),
        times = DailyRules.normalize(prefs.getString("times", "1200")!!.split(",").mapNotNull { it.toIntOrNull() }),
        lockscreenPreview = prefs.getBoolean("preview", true), revision = prefs.getLong("revision", 0), scope = prefs.getLong("scope", 0),
        mode = runCatching { DailyMode.valueOf(prefs.getString("mode", "FIXED")!!) }.getOrDefault(DailyMode.FIXED),
        intervalMinutes = prefs.getInt("interval_minutes", 30), week = readWeek(),
    ))
    private fun outcome(text: String) { prefs.edit().putString("status", text).apply(); statusMutable.value = text }

    suspend fun update(change: (DailyConfig) -> DailyConfig) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = state.value
        val requested = change(old)
        val scopeChanged = requested.source != old.source || requested.albums != old.albums
        val next = PhotoSchedule.normalize(requested.copy(times = if (requested.times.isEmpty()) old.times.take(1) else requested.times,
            revision = old.revision + 1, scope = old.scope + if (scopeChanged) 1 else 0))
        val resetInterval = next.mode != old.mode || next.intervalMinutes != old.intervalMinutes || next.week != old.week || (!old.enabled && next.enabled)
        val edit = prefs.edit().putBoolean("enabled", next.enabled).putString("source", next.source.name)
            .putStringSet("albums", next.albums).putString("times", next.times.joinToString(","))
            .putBoolean("preview", next.lockscreenPreview).putLong("revision", next.revision).putLong("scope", next.scope)
            .putString("mode", next.mode.name).putInt("interval_minutes", next.intervalMinutes).putString("week", weekJson(next.week))
        if (resetInterval || !next.enabled) edit.remove("interval_due")
        check(edit.commit())
        if (scopeChanged) history.clearRound()
        mutable.value = next
        if (!next.enabled || next.lockscreenPreview != old.lockscreenPreview) cancelNotifications()
        scheduleLocked()
    } }

    fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "每日精选", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "按你设置的时间推荐一张本机照片；声音和震动由系统控制"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }
    fun notificationsAllowed(): Boolean {
        createChannel()
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun openSystemSettings() {
        context.startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun exactAlarmsAllowed(): Boolean = alarms.exactAllowed()
    fun openTimingSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: android.content.ActivityNotFoundException) { openBackgroundSettings() }
        }
    }
    fun backgroundRestricted(): Boolean = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    fun openBackgroundSettings() {
        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    suspend fun reconcile(resetInterval: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock {
        if (resetInterval) check(prefs.edit().remove("interval_due").commit())
        scheduleLocked()
    } }

    private fun saveInterval(slot: DailySlot?, zone: ZoneId) {
        val edit = prefs.edit().putString("interval_zone", zone.id)
        if (slot == null) edit.remove("interval_due") else edit.putLong("interval_due", slot.instant.toEpochMilli())
        check(edit.commit())
    }

    private fun scheduleLocked(now: Instant = Instant.now()) {
        // Old installations have a delayed WorkManager chain. Cancel it once per process;
        // retained legacy workers only migrate the schedule, and never post late photos.
        if (!migratedLegacyWork) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            migratedLegacyWork = true
        }
        val config = state.value
        if (!config.enabled || !notificationsAllowed()) {
            alarms.cancel()
            plannedMutable.value = null
            return
        }
        val zone = ZoneId.systemDefault()
        val saved = if (prefs.getString("interval_zone", zone.id) == zone.id && prefs.contains("interval_due"))
            Instant.ofEpochMilli(prefs.getLong("interval_due", 0)) else null
        var slot = PhotoSchedule.restore(config, saved, now, zone)
        if (slot != null && history.hasSlot(slot.key)) slot = PhotoSchedule.following(config, slot, now, zone)
        if (config.mode == DailyMode.INTERVAL) saveInterval(slot, zone)
        if (slot == null) alarms.cancel() else alarms.schedule(slot, config.revision)
        plannedMutable.value = slot?.instant
    }

    private fun candidates(config: DailyConfig): List<Photo> {
        val photos = Gallery.scan(context).second
        return when (config.source) {
            DailySource.ALL -> photos
            DailySource.ALBUMS -> photos.filter { it.albumId in config.albums }
            DailySource.FAVORITES -> {
                val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
                val saved = try { db.store().photos().map(Photo::from).associateBy { it.uri } } finally { db.close() }
                photos.filter { sameSource(saved[it.uri] ?: return@filter false, it) }
            }
        }.distinctBy { it.uri }
    }

    /** Also used by instrumentation: identical permission, selection and posting path. */
    internal suspend fun deliver(slot: DailySlot?, revision: Long, test: Boolean = false, now: Instant = Instant.now()): String = withContext(Dispatchers.IO) { mutex.withLock {
        val config = state.value
        if (!test && (!config.enabled || revision != config.revision)) return@withLock "设置已变更，跳过旧任务"
        if (!notificationsAllowed()) return@withLock "系统通知未开启".also(::outcome)
        if (!test && (slot == null || !PhotoSchedule.eligible(config, slot, now, ZoneId.systemDefault()))) return@withLock "已跳过过期或静默时点"
        if (!test && history.hasSlot(slot!!.key)) return@withLock "该时点已处理"
        if (Gallery.access(context) == Access.NONE) return@withLock "没有照片访问权限，请在设置中授权".also(::outcome)
        val pool = try { candidates(config) } catch (e: SecurityException) { emptyList() }
        if (pool.isEmpty()) return@withLock "当前推荐范围没有可访问照片".also(::outcome)
        val seen = history.seen()
        val byId = pool.associateBy { it.dailyIdentity() }
        val order = DailyRules.order(byId.keys.toList(), seen, history.last())
        for (identity in order) {
            currentCoroutineContext().ensureActive()
            val photo = byId.getValue(identity)
            // Confirm the source is still readable, even if an old bitmap is cached.
            val readable = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(photo.uri), "r")?.use { true } ?: false }.getOrDefault(false)
            if (!readable) continue
            val bitmap = PhotoBitmaps.load(context, photo.uri, revision = photo.added) ?: continue
            if (!notificationsAllowed()) return@withLock "系统通知未开启".also(::outcome)
            if (!test && !PhotoSchedule.allowed(config, Instant.now(), ZoneId.systemDefault())) return@withLock "已进入静默时段"
            val record = if (test) "test" else slot!!.key
            if (test) history.testRecord(record, photo)
            else if (!history.claim(record, photo, identity in seen)) return@withLock "该时点已处理"
            try {
                post(record, bitmap, config, test)
            } catch (_: SecurityException) { return@withLock "通知权限已被关闭".also(::outcome) }
            if (!test) history.posted(record)
            return@withLock (if (test) "测试通知已发送，不计入推荐轮次" else "已推荐：${photo.name}").also(::outcome)
        }
        "当前范围的照片无法生成预览，已跳过本次推荐".also(::outcome)
    } }

    suspend fun testNotification(): String = deliver(null, state.value.revision, test = true)
    internal suspend fun runAlarm(intent: Intent) {
        val revision = intent.getLongExtra("revision", -1)
        val slot = runCatching { DailySlot(LocalDate.parse(intent.getStringExtra("day")), intent.getIntExtra("minute", -1),
            Instant.ofEpochMilli(intent.getLongExtra("instant", 0)), DailyMode.valueOf(intent.getStringExtra("mode") ?: "FIXED")) }.getOrNull() ?: return
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val due = mutex.withLock {
            val config = state.value
            if (!config.enabled || config.revision != revision || config.mode != slot.mode) return@withLock null
            if (config.mode == DailyMode.INTERVAL && prefs.getLong("interval_due", -1) != slot.instant.toEpochMilli()) return@withLock null
            val resolved = PhotoSchedule.due(config, slot, now, zone)
            // Arm the successor before local photo I/O, including when this occurrence is skipped.
            if (config.mode == DailyMode.INTERVAL) saveInterval(PhotoSchedule.following(config, slot, now, zone), zone)
            scheduleLocked(now)
            resolved
        }
        if (due == null) return
        android.util.Log.i("DailyPhotos", "Alarm received slot=${slot.key} expected=${slot.instant} actual=$now")
        try {
            val result = deliver(due, revision, now = now)
            android.util.Log.i("DailyPhotos", "Alarm result: $result")
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.w("DailyPhotos", "Alarm photo failed", e)
            outcome("本次读取未完成，将在下一时点继续")
        }
    }
    suspend fun recordPhoto(key: String): Photo? = withContext(Dispatchers.IO) { history.photo(key) }
    private fun contentIntent(key: String): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setAction(OPEN_ACTION).setData(Uri.parse("photerest://daily/${Uri.encode(key)}"))
            .putExtra(RECORD_EXTRA, key).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun post(key: String, bitmap: Bitmap, config: DailyConfig, test: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.activeNotifications.filter { it.notification.channelId == CHANNEL && it.tag != "summary" && it.tag != key }
        val grouped = existing.isNotEmpty()
        val notification = DailyPhotoNotification.build(context, bitmap, contentIntent(key), config.lockscreenPreview, test, if (grouped) GROUP else null)
        // A lone group child can be suppressed by SystemUI if there is no summary.
        // Publish the first photo ungrouped, and only form a group when a second exists.
        if (grouped) existing.forEach {
            manager.notify(it.tag, it.id, Notification.Builder.recoverBuilder(context, it.notification)
                .setGroup(GROUP).setOnlyAlertOnce(true).build())
        }
        manager.notify(key, 1, notification)
        if (grouped) manager.notify("summary", 1, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("每日精选").setContentText("查看照片推荐").setGroup(GROUP).setGroupSummary(true)
            .setContentIntent(contentIntent(key)).setAutoCancel(true).setSilent(true).build())
        else manager.cancel("summary", 1)
    }
    private fun cancelNotifications() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
    }

    companion object {
        const val CHANNEL = "daily_photos_v1"
        const val WORK = "photerest.daily.next"
        const val GROUP = "photerest.daily.photos"
        const val OPEN_ACTION = "com.photerest.app.OPEN_DAILY_PHOTO"
        const val RECORD_EXTRA = "daily_record"
        // The constructor is only called with applicationContext; no Activity is retained.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: DailyRecommendations? = null
        fun get(context: Context): DailyRecommendations = instance ?: synchronized(this) {
            instance ?: DailyRecommendations(context.applicationContext).also { instance = it }
        }
    }
}

class DailyPhotoWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // Compatibility with jobs persisted by 1.3.0–1.3.2. Do not deliver backlog on app launch.
        DailyRecommendations.get(applicationContext).reconcile()
        return Result.success()
    }
}

class DailyScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { DailyRecommendations.get(context).reconcile(resetInterval = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) }
            catch (e: Exception) { android.util.Log.w("DailyPhotos", "Could not restore schedule; retry on next app launch", e) }
            finally { pending.finish() }
        }
    }
}
