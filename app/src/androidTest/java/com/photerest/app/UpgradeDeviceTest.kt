package com.photerest.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.photerest.app.data.AppDatabase
import com.photerest.app.data.Store
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Invoked explicitly before and after `adb install -r`:
 * seed under the released 1.3.1 APK, upgrade to 1.4.0, then verify.
 * Excluded from ordinary device tests through @PermissionScenario.
 */
@PermissionScenario
class UpgradeDeviceTest {
    @Test fun seedVersionOne() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
            .putBoolean("dark", false).putBoolean("haptics", false)
            .putBoolean("no_text_mode", false).putString("operation_style", "EXPRESSIVE")
            .remove("autoplay_videos")
            .commit()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        try {
            val avatar = File(context.filesDir, "upgrade-avatar.jpg")
            instrumentation.context.assets.open("scene0.jpg").use { input -> avatar.outputStream().use { input.copyTo(it) } }
            db.store().saveProfile(Store.Profile().also { it.name = "升级验证"; it.avatar = Uri.fromFile(avatar).toString() })
            db.store().addFolder(Store.Folder(88, "升级收藏夹"))
            db.store().addAlbums(listOf(Store.Album("upgrade-album", "升级前相册")))
            val photo = Store.Photo().also {
                it.uri = "content://media/external_primary/images/media/999999998"
                it.name = "upgrade-marker.jpg"
                it.width = 600
                it.height = 900
            }
            db.store().savePhoto(photo)
            db.store().favorite(Store.Favorite(photo.uri, 88))
        } finally { db.close() }
        val week = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("enabled", false))
            put(org.json.JSONObject().put("quiet", org.json.JSONArray().put(
                org.json.JSONObject().put("start", 1320).put("end", 420)
            )))
        }
        check(context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("source", "FAVORITES").putStringSet("albums", emptySet())
            .putString("times", "540,900,1200").putBoolean("preview", false)
            .putString("mode", "INTERVAL").putInt("interval_minutes", 45).putString("week", week.toString())
            .remove("interval_due").remove("interval_zone").commit())
        val daily = DailyRecommendations.get(context)
        // This test APK runs against 1.3.1 first, where Photo has no duration field.
        val fields = arrayOf<Any>(
            "content://media/external_primary/images/media/999999998",
            "upgrade-marker.jpg", "upgrade-album", "image/jpeg", 600, 900, 1024L, 0L, 0L
        )
        val constructor = Photo::class.java.constructors.single { !it.isSynthetic }
        val marker = constructor.newInstance(*(if (constructor.parameterCount == fields.size) fields else fields + 0L)) as Photo
        daily.history.claim("upgrade-daily", marker, resetRound = false)
    }

    @Test fun verifyVersionTwo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(8, info.versionCode)
        assertEquals("1.4.0", info.versionName)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        try {
            assertEquals("升级验证", db.store().profile().name)
            assertTrue(File(Uri.parse(db.store().profile().avatar).path!!).length() > 0)
            assertTrue(db.store().folders().any { it.id == 88L && it.name == "升级收藏夹" })
            assertTrue(db.store().albums().any { it.id == "upgrade-album" })
            assertTrue(db.store().favorites().any { it.uri.endsWith("/999999998") && it.folderId == 88L })
        } finally { db.close() }
        val settings = SettingsStore(context).state.value
        assertFalse(settings.dark)
        assertFalse(settings.haptics)
        assertFalse(settings.noTextMode)
        assertEquals(OperationStyle.EXPRESSIVE, settings.operationStyle)
        assertTrue("1.4.0 keeps autoplay on by default for upgraded users", settings.autoplayVideos)
        val daily = DailyRecommendations.get(context)
        assertTrue(daily.state.value.enabled)
        assertEquals(listOf(540, 900, 1200), daily.state.value.times)
        assertEquals(DailyMode.INTERVAL, daily.state.value.mode)
        assertEquals(45, daily.state.value.intervalMinutes)
        assertFalse(daily.state.value.week[0].enabled)
        assertEquals(listOf(QuietTime(1320, 420)), daily.state.value.week[1].quiet)
        assertEquals(DailySource.FAVORITES, daily.state.value.source)
        assertFalse(daily.state.value.lockscreenPreview)
        assertTrue(daily.history.hasSlot("upgrade-daily"))
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 288, 288)
        icon.draw(Canvas(bitmap))
        val evidence = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(evidence, "upgrade-launcher-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
