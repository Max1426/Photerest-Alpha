package com.photerest.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.photerest.app.data.AppDatabase
import com.photerest.app.data.Store
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyRecommendationDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = DailyRecommendations.get(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val sources = mutableListOf<Uri>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixture: List<Photo>
    private lateinit var album: String
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    @Before fun setup() {
        shell("wm dismiss-keyguard")
        shell("pm grant ${context.packageName} android.permission.READ_MEDIA_IMAGES")
        shell("pm grant ${context.packageName} android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("dark", true).commit()
        val path = "Pictures/PhoterestDailyQA-${System.nanoTime()}"
        repeat(3) { i ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "每日光影-${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val palettes = listOf(intArrayOf(0xff173a60.toInt(), 0xffe8ac77.toInt()), intArrayOf(0xff352a63.toInt(), 0xffcda1d0.toInt()), intArrayOf(0xff124c53.toInt(), 0xffb7d1b3.toInt()))
            canvas.drawRect(0f, 0f, 900f, 1200f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1200f, palettes[i], null, Shader.TileMode.CLAMP) })
            canvas.drawCircle(650f, 320f, 115f, Paint(3).apply { color = 0xffffe2b0.toInt() })
            canvas.drawPath(Path().apply { moveTo(0f, 850f); lineTo(300f, 430f); lineTo(620f, 850f); lineTo(780f, 600f); lineTo(900f, 820f); lineTo(900f, 1200f); lineTo(0f, 1200f); close() }, Paint(3).apply { color = 0xff193a48.toInt() })
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            sources.add(uri)
        }
        val ids = sources.map { android.content.ContentUris.parseId(it) }.toSet()
        fixture = Gallery.scan(context).second.filter { android.content.ContentUris.parseId(Uri.parse(it.uri)) in ids }
        album = fixture.first().albumId
        runBlocking {
            repository.update { DailyConfig(revision = it.revision, scope = it.scope, source = DailySource.ALBUMS, albums = setOf(album)) }
        }
        repository.history.writableDatabase.execSQL("DELETE FROM deliveries")
        repository.history.writableDatabase.execSQL("DELETE FROM seen")
        repository.history.writableDatabase.execSQL("DELETE FROM meta")
        manager.cancelAll()
    }
    @After fun cleanup() {
        scenario?.close()
        runBlocking { repository.update { it.copy(enabled = false) } }
        sources.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        Thread.sleep(650) // The system IME and dialog window can still be animating.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "daily-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription("个人账号").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun today(hour: Int): DailySlot {
        val day = LocalDate.now()
        return DailySlot(day, hour * 60, day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant())
    }
    private fun activePhoto(): android.service.notification.StatusBarNotification {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.tag != "summary" && it.notification.channelId == DailyRecommendations.CHANNEL }?.let { return it }
            Thread.sleep(50)
        }
        error("System did not publish the photo notification")
    }

    @Test fun notificationHasPhotoAndOpensCorrectDetailColdAndWarm() {
        val before = repository.history.seen()
        val result = runBlocking { repository.testNotification() }
        assertTrue(result.contains("已发送")); assertEquals(before, repository.history.seen())
        val posted = activePhoto()
        assertNull(posted.notification.getLargeIcon())
        assertNotNull(posted.notification.contentView)
        assertNotNull(posted.notification.bigContentView)
        assertEquals(context.getString(R.string.daily_photo_test_message), posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val chosen = repository.history.photo("test")!!
        assertTrue(fixture.any { it.uri == chosen.uri })
        shell("cmd statusbar expand-notifications"); Thread.sleep(600); capture("notification-collapsed")
        shell("cmd statusbar collapse")
        // No activity has been launched by this test: the PendingIntent creates it.
        posted.notification.contentIntent.send()
        ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription("返回照片流").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(chosen.name).assertIsDisplayed()
        instrumentation.checkOriginalOpened(chosen) {
            ui.onNodeWithContentDescription("在相册中打开原图").performClick()
        }
        ui.onNodeWithText(chosen.name).assertIsDisplayed()
        capture("notification-open-detail")
        ui.onNodeWithContentDescription("返回照片流").performClick()
        posted.notification.contentIntent.send()
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("返回照片流").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(chosen.name).assertIsDisplayed()
        // Close the notification-created activity without force-stopping instrumentation.
        shell("input keyevent 4")
    }

    @Test fun roundPersistsAndDuplicateSlotNeverPostsTwice() {
        runBlocking { repository.update { it.copy(enabled = true, times = listOf(540, 600, 660, 720)) } }
        val chosen = mutableListOf<String>()
        for (hour in 9..12) {
            val slot = today(hour)
            val result = runBlocking { repository.deliver(slot, repository.state.value.revision, now = slot.instant.plusSeconds(1)) }
            assertTrue(result, result.startsWith("已推荐"))
            chosen.add(repository.history.photo(slot.key)!!.dailyIdentity())
        }
        assertEquals(3, chosen.take(3).toSet().size); assertNotEquals(chosen[2], chosen[3])
        val visibleBy = System.currentTimeMillis() + 5_000
        while (manager.activeNotifications.count { it.tag != "summary" } < 4 && System.currentTimeMillis() < visibleBy) Thread.sleep(50)
        val notifications = manager.activeNotifications.filter { it.tag != "summary" }
        assertEquals(4, notifications.size)
        notifications.forEach {
            assertEquals(DailyRecommendations.GROUP, it.notification.group)
            assertNotNull(it.notification.contentView); assertNotNull(it.notification.bigContentView)
        }
        val reopened = DailyHistory(context)
        assertEquals(repository.history.seen(), reopened.seen()); reopened.close()
        val duplicate = runBlocking { repository.deliver(today(12), repository.state.value.revision, now = today(12).instant.plusSeconds(1)) }
        assertEquals("该时点已处理", duplicate)
    }

    @Test fun sourceSelectionAndPrivacyPersistWithoutTouchingFavorites() {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        val photo = fixture.first()
        db.store().addFolder(Store.Folder(777, "每日精选测试收藏"))
        db.store().savePhoto(photo.stored()); db.store().favorite(Store.Favorite(photo.uri, 777)); db.close()
        runBlocking { repository.update { it.copy(source = DailySource.FAVORITES, albums = emptySet(), lockscreenPreview = false) } }
        assertTrue(runBlocking { repository.testNotification() }.contains("已发送"))
        assertEquals(photo.uri, repository.history.photo("test")!!.uri)
        val notification = activePhoto().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertNull(notification.publicVersion.getLargeIcon())
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(photo.name))
        val prefs = context.getSharedPreferences("daily_recommendations", Context.MODE_PRIVATE)
        assertEquals("FAVORITES", prefs.getString("source", "")); assertFalse(prefs.getBoolean("preview", true))
        runBlocking { repository.update { it.copy(lockscreenPreview = true) } }
        val clearedBy = System.currentTimeMillis() + 5_000
        while (manager.activeNotifications.any { it.notification.channelId == DailyRecommendations.CHANNEL } && System.currentTimeMillis() < clearedBy) Thread.sleep(50)
        assertTrue(manager.activeNotifications.none { it.notification.channelId == DailyRecommendations.CHANNEL })
        val check = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        assertTrue(check.store().favorites().any { it.uri == photo.uri && it.folderId == 777L })
        check.store().clearFolder(777); check.store().deleteFolder(777); check.store().prunePhotos(); check.close()
    }

    @Test fun emptyAndDeletedSourcesSkipWithoutNotifications() {
        sources.forEach { context.contentResolver.delete(it, null, null) }
        assertEquals("当前推荐范围没有可访问照片", runBlocking { repository.testNotification() })
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(repository.history.seen().isEmpty())
    }

    @Test fun staleWorkAndDisabledWorkDoNotConsumeRound() {
        val slot = today(9)
        runBlocking { repository.update { it.copy(enabled = true, times = listOf(540)) } }
        val old = repository.state.value.revision
        runBlocking { repository.update { it.copy(times = listOf(600)) } }
        assertEquals("设置已变更，跳过旧任务", runBlocking { repository.deliver(slot, old, now = slot.instant) })
        runBlocking { repository.update { it.copy(enabled = false) } }
        assertEquals("设置已变更，跳过旧任务", runBlocking { repository.deliver(slot, repository.state.value.revision, now = slot.instant) })
        assertTrue(repository.history.seen().isEmpty()); assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun legacyWorkerMigratesWithoutSendingLateNotifications() {
        val now = Instant.now()
        val local = now.atZone(ZoneId.systemDefault())
        val minute = local.hour * 60 + local.minute
        val slot = DailySlot(local.toLocalDate(), minute, local.withSecond(0).withNano(0).toInstant())
        runBlocking { repository.update { it.copy(enabled = true, times = listOf(minute)) } }
        val request = OneTimeWorkRequestBuilder<DailyPhotoWorker>().setInputData(workDataOf("day" to slot.day.toString(), "minute" to slot.minute,
            "instant" to slot.instant.toEpochMilli(), "revision" to repository.state.value.revision)).build()
        val work = WorkManager.getInstance(context)
        work.enqueue(request).result.get(10, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 20_000
        while (work.getWorkInfoById(request.id).get()?.state?.isFinished != true && System.currentTimeMillis() < deadline) Thread.sleep(150)
        assertEquals(WorkInfo.State.SUCCEEDED, work.getWorkInfoById(request.id).get()?.state)
        assertFalse(repository.history.hasSlot(slot.key))
        assertTrue(manager.activeNotifications.isEmpty())
        assertNotNull(DailyAlarms(context).pending())
    }

    @Test fun settingsShowTimesAndThemesWithoutAProfile() {
        launch()
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("每日精选").performScrollTo().performClick()
        ui.onNodeWithText("每天设置 1 个时点，每个时间一张").performScrollTo().assertIsDisplayed()
        capture("settings-dark-top")
        ui.onNodeWithTag("daily-edit-schedule").performScrollTo().performClick()
        ui.onNodeWithText("添加时间").performScrollTo().performClick()
        ui.onAllNodes(hasSetTextAction())[0].performTextReplacement("10")
        ui.onAllNodes(hasSetTextAction())[1].performTextReplacement("30")
        ui.onNodeWithText("确定").performClick()
        assertEquals(listOf(1200), repository.state.value.times)
        ui.onNodeWithTag("schedule-save").performClick()
        ui.waitUntil(5_000) { repository.state.value.times == listOf(630, 1200) }
        ui.onNodeWithTag("daily-edit-schedule").performScrollTo().performClick()
        ui.onNodeWithText("10:30").assertExists()
        ui.onNodeWithText("添加时间").performScrollTo().performClick()
        ui.onAllNodes(hasSetTextAction())[0].performTextReplacement("10")
        ui.onAllNodes(hasSetTextAction())[1].performTextReplacement("30")
        ui.onNodeWithText("确定").performClick()
        ui.onNodeWithTag("schedule-error").assertExists()
        assertEquals(listOf(630, 1200), repository.state.value.times)
        ui.onNodeWithTag("schedule-delete-630").performScrollTo().performClick()
        ui.onNodeWithTag("schedule-save").performClick()
        ui.waitUntil(5_000) { repository.state.value.times == listOf(1200) }
        runBlocking { repository.update { it.copy(times = listOf(540, 900, 1200)) } }
        ui.waitForIdle()
        ui.onNodeWithText("每天设置 3 个时点，每个时间一张").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("09:00、15:00、20:00").assertExists()
        ui.onNodeWithText("发送测试通知").performScrollTo()
        capture("settings-dark-bottom")
        ui.onNodeWithContentDescription("返回设置").performClick()
        ui.onNodeWithText("浅色").performClick()
        ui.onNodeWithText("每日精选").performScrollTo().performClick()
        capture("settings-light-top")
        assertEquals(listOf(540, 900, 1200), repository.state.value.times)
    }

    @Test fun intervalAndWeeklyDraftCommitsTogether() {
        launch()
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("每日精选").performScrollTo().performClick()
        ui.onNodeWithTag("daily-edit-schedule").performScrollTo().performClick()
        ui.onNodeWithTag("schedule-mode-INTERVAL").performClick()
        ui.onNodeWithTag("schedule-minutes").performTextReplacement("14")
        ui.onNodeWithTag("schedule-save").performClick()
        ui.onNodeWithTag("schedule-error").assertIsDisplayed()
        ui.onNodeWithTag("schedule-minutes").performTextReplacement("45")
        ui.onNodeWithTag("schedule-day-0").performScrollTo().performClick()
        ui.onNodeWithTag("schedule-expand-1").performScrollTo().performClick()
        ui.onNodeWithTag("schedule-add-quiet-1").performScrollTo().performClick()
        ui.onNodeWithText("静默开始时间").assertExists()
        ui.onNodeWithText("确定").performClick()
        ui.onNodeWithText("静默结束时间").assertExists()
        ui.onNodeWithText("确定").performClick()
        ui.onNodeWithTag("schedule-add-quiet-1").performScrollTo().performClick()
        fun setTime(hour: String, minute: String) {
            val fields = ui.onAllNodes(hasSetTextAction())
            val count = fields.fetchSemanticsNodes().size
            fields[count - 2].performTextReplacement(hour)
            fields[count - 1].performTextReplacement(minute)
            ui.onNodeWithText("确定").performClick()
        }
        setTime("12", "00")
        setTime("14", "00")
        assertEquals(DailyMode.FIXED, repository.state.value.mode)
        assertTrue(repository.state.value.week[0].enabled)
        capture("schedule-week-draft")
        ui.onNodeWithTag("schedule-save").performClick()
        ui.waitUntil(5_000) { repository.state.value.mode == DailyMode.INTERVAL }
        assertEquals(45, repository.state.value.intervalMinutes)
        assertFalse(repository.state.value.week[0].enabled)
        assertEquals(listOf(QuietTime(720, 840), QuietTime(1380, 480)), repository.state.value.week[1].quiet)
        assertEquals(listOf(1200), repository.state.value.times)
        ui.onNodeWithText("每隔 45 分钟推荐一张").performScrollTo().assertIsDisplayed()
        capture("interval-settings")
        ui.onNodeWithTag("daily-edit-schedule").performScrollTo().performClick()
        ui.onNodeWithTag("schedule-mode-FIXED").performClick()
        ui.onNodeWithContentDescription("取消编辑").performClick()
        assertEquals(DailyMode.INTERVAL, repository.state.value.mode)
    }
}
