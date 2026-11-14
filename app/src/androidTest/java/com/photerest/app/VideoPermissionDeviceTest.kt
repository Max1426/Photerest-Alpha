package com.photerest.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photerest.app.data.AppDatabase
import com.photerest.app.data.Store
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Permission combinations are prepared OUTSIDE the instrumentation process with adb
 * (`pm grant` / `pm revoke`), then run one scenario at a time, exactly like PermissionDeviceTest.
 */
@PermissionScenario
@RunWith(AndroidJUnit4::class)
class VideoPermissionDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val sources = mutableListOf<Uri>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: PhotoModel

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }

    private fun prepare() {
        scenario?.close()
        scenario = null
        context.deleteDatabase("photerest.db")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit()
            .putBoolean("photo_permission_requested", true).commit()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[PhotoModel::class.java] }
        ui.waitUntil(15_000) { ::model.isInitialized && model.state.value.ready && !model.state.value.busy }
        ui.waitForIdle()
    }

    private fun insertVideo(directory: String): Uri {
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", "PermQA-clip.mp4")
            put("mime_type", "video/mp4")
            put("relative_path", "Pictures/$directory")
            put("is_pending", 1)
        }))
        sources += uri
        context.contentResolver.openOutputStream(uri)!!.use { destination ->
            instrumentation.context.assets.open("video-preview.mp4").use { it.copyTo(destination) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
        return uri
    }

    @After fun clean() {
        scenario?.close()
        sources.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun photosOnlyHidesVideosAndOffersTheVideoPermissionEntry() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue("run with only photo permission granted", Gallery.granted(context, android.Manifest.permission.READ_MEDIA_IMAGES))
        assumeTrue(!Gallery.granted(context, android.Manifest.permission.READ_MEDIA_VIDEO))
        if (Build.VERSION.SDK_INT >= 34) {
            assumeTrue(!Gallery.granted(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
        prepare()
        launch()
        assertEquals(Access.FULL, model.state.value.access)
        assertEquals(Access.NONE, model.state.value.videoAccess)
        assertTrue(model.state.value.feed.none { it.isVideo })
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("授权视频访问").performScrollTo().assertExists()
        screenshot("video-permission-entry")

        // The entry launches the system permission flow (also observed in logcat during development);
        // here we exercise the click and then continue with the externally granted state.
        ui.onNodeWithText("授权视频访问").performClick()
        Thread.sleep(800)
        shell("input keyevent 4")
        Thread.sleep(500)

        // Accepting that dialog grants video access; simulate the accept and confirm the app picks it up.
        shell("pm grant ${context.packageName} android.permission.READ_MEDIA_VIDEO")
        scenario!!.close()
        scenario = null
        launch()
        assertEquals(Access.FULL, model.state.value.videoAccess)
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        Thread.sleep(500)
        assertTrue("The authorization entry disappears once video access is granted",
            ui.onAllNodesWithText("授权视频访问").fetchSemanticsNodes().isEmpty())
    }

    @Test fun videoOnlyFeedKeepsTheGridAndNeverShowsThePhotoEmptyState() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue("run with only video permission granted", Gallery.granted(context, android.Manifest.permission.READ_MEDIA_VIDEO))
        assumeTrue(!Gallery.granted(context, android.Manifest.permission.READ_MEDIA_IMAGES))
        if (Build.VERSION.SDK_INT >= 34) {
            assumeTrue(!Gallery.granted(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
        prepare()
        insertVideo("PermVideo-${System.nanoTime() % 100000}")
        launch()
        assertEquals(Access.NONE, model.state.value.access)
        assertEquals(Access.FULL, model.state.value.videoAccess)
        assertTrue(model.state.value.feed.any { it.isVideo })
        ui.onNodeWithText("让照片在这里相遇").assertDoesNotExist()
        ui.onNodeWithTag("home-feed").assertIsDisplayed()
        ui.waitUntil(10_000) {
            ui.onAllNodes(hasTestTag("video-duration"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("video-only-feed")
    }

    @Test fun revokedVideoPermissionKeepsFavoritePlaceholder() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue("run with video permission revoked", !Gallery.granted(context, android.Manifest.permission.READ_MEDIA_VIDEO))
        prepare()
        val missing = Photo("content://media/external/video/media/999999999", "PermQA-gone.mp4", "perm-fixture", "video/mp4", 720, 1280, 4096, 0, 0)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        try {
            db.store().addFolder(Store.Folder(1, "默认收藏夹"))
            db.store().saveProfile(Store.Profile().also { it.name = "权限测试" })
            db.store().savePhoto(missing.stored())
            db.store().favorite(Store.Favorite(missing.uri, 1))
        } finally { db.close() }
        launch()
        assertEquals(Access.NONE, model.state.value.videoAccess)
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("我收藏的图片").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("播放视频：PermQA-gone.mp4").assertExists()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("视频未授权或原视频已删除").fetchSemanticsNodes().isNotEmpty() }
        screenshot("video-revoked-favorite")
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        Thread.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
