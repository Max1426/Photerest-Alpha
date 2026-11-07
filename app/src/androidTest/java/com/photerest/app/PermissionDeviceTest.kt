package com.photerest.app

import android.graphics.Bitmap
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photerest.app.data.AppDatabase
import com.photerest.app.data.Store
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Permissions must be changed OUTSIDE the instrumentation process, which Android kills on revocation. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PermissionScenario

@PermissionScenario
@RunWith(AndroidJUnit4::class)
class PermissionDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private fun verify(access: Access, screenshot: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(access, Gallery.access(context))
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("photo_permission_requested", true).commit()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        db.store().addFolder(Store.Folder(1, "默认收藏夹"))
        db.store().saveProfile(Store.Profile().also { it.name = "我的光影" })
        val photo = Photo("content://media/external_primary/images/media/999999999", "Retained-favorite.jpg", "permission-fixture", "image/jpeg", 600, 900, 1024, 0, 0)
        assertEquals("照片访问受限，请重新授权", runBlocking { Gallery.openOriginal(context, photo) })
        db.store().savePhoto(photo.stored()); db.store().favorite(Store.Favorite(photo.uri, 1))
        val count = db.store().photos().size
        db.close()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: PhotoModel
            scenario.onActivity { model = ViewModelProvider(it)[PhotoModel::class.java] }
            ui.waitUntil(10_000) { model.state.value.ready && !model.state.value.busy }
            assertEquals(count, model.state.value.favorites.size)
            assertFalse(model.state.value.available(photo))
            if (access == Access.NONE) {
                assertTrue(model.state.value.feed.isEmpty())
                ui.onNodeWithText("让照片在这里相遇").assertExists()
            } else ui.onNodeWithText("仅显示已授权照片 · 管理访问范围").assertExists()
            ui.waitForIdle()
            val directory = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { image -> File(directory, "$screenshot.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
        }
    }
    @Test fun denied() = verify(Access.NONE, "10-permission-denied")
    @Test fun partial() = verify(Access.PARTIAL, "11-partial-access")

    @Test fun firstLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(Access.NONE, Gallery.access(context))
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("photo_permission_requested", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: PhotoModel
            scenario.onActivity { model = ViewModelProvider(it)[PhotoModel::class.java] }
            var allow: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(100) {
                if (allow == null) {
                    allow = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Allow all")?.firstOrNull()
                    if (allow == null) Thread.sleep(100)
                }
            }
            assertNotNull("First launch must request photo permission without opening an album picker", allow)
            var button = allow!!
            while (!button.isClickable && button.parent != null) button = button.parent
            assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(15_000) { model.state.value.ready && !model.state.value.busy && model.state.value.access == Access.FULL }
            assertNull(model.state.value.selectedAlbum)
            assertEquals(model.state.value.library.keys, model.state.value.feed.map { it.uri }.toSet())
            ui.onNodeWithText("关联手机相册").assertDoesNotExist()
            ui.onNodeWithText("全部").assertExists()
        }
    }
}
