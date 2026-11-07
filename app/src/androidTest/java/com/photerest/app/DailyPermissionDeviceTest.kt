package com.photerest.app

import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Invoke separately: Android terminates app/instrumentation when permissions are revoked. */
@PermissionScenario
class DailyPermissionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun notificationsDenied() {
        val repository = DailyRecommendations.get(context)
        assertFalse(repository.notificationsAllowed())
        val before = repository.history.seen()
        assertEquals("系统通知未开启", runBlocking { repository.testNotification() })
        assertEquals(before, repository.history.seen())
    }
    @Test fun photosDenied() {
        val repository = DailyRecommendations.get(context)
        assertEquals(Access.NONE, Gallery.access(context))
        assertTrue(repository.notificationsAllowed())
        assertEquals("没有照片访问权限，请在设置中授权", runBlocking { repository.testNotification() })
    }
    @Test fun partialAccessUsesOnlyReadablePhotos() {
        assertEquals(Access.PARTIAL, Gallery.access(context))
        val repository = DailyRecommendations.get(context)
        runBlocking { repository.update { it.copy(enabled = false, source = DailySource.ALL, albums = emptySet()) } }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "partial-daily-fixture.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhoterestPartialQA")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(65, 110, 140)) }
            context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }; image.recycle()
            assertTrue(runBlocking { repository.testNotification() }.contains("已发送"))
            val selected = repository.history.photo("test")!!
            assertTrue(Gallery.scan(context).second.any { sameSource(selected, it) })
        } finally { context.contentResolver.delete(uri, null, null); context.getSystemService(NotificationManager::class.java).cancelAll() }
    }
}
