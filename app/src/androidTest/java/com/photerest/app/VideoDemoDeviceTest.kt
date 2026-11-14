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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds a mixed album and drives the video flow with deliberate pauses so
 * `adb shell screenrecord` can capture the deliverable demo. Not part of the default suite.
 */
@PermissionScenario
@RunWith(AndroidJUnit4::class)
class VideoDemoDeviceTest {
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

    private fun grant(permission: String) { shell("pm grant ${context.packageName} $permission") }

    private fun insert(video: Boolean, name: String, asset: String, directory: String): Uri {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = checkNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put("_display_name", name)
            put("mime_type", if (video) "video/mp4" else "image/jpeg")
            put("relative_path", "Pictures/$directory")
            put("is_pending", 1)
        }))
        sources += uri
        context.contentResolver.openOutputStream(uri)!!.use { destination ->
            instrumentation.context.assets.open(asset).use { it.copyTo(destination) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put("is_pending", 0) }, null, null)
        return uri
    }

    @After fun clean() {
        scenario?.close()
        sources.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun recordDemoFlow() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        scenario?.close()
        context.deleteDatabase("photerest.db")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            grant(android.Manifest.permission.READ_MEDIA_IMAGES)
            grant(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else grant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val directory = "VideoDemo-${System.nanoTime() % 100000}"
        insert(true, "Demo-sunrise.mp4", "video-preview.mp4", directory)
        insert(true, "Demo-river.mp4", "video-preview.mp4", directory)
        val (albums, _) = Gallery.scan(context, includeVideos = true)
        val album = albums.single { it.name == directory }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        try {
            db.store().addAlbums(listOf(Store.Album(album.id, album.name)))
            db.store().addFolder(Store.Folder(1, "默认收藏夹"))
            db.store().saveProfile(Store.Profile().also { it.name = "视频演示" })
        } finally { db.close() }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[PhotoModel::class.java] }
        ui.waitUntil(15_000) { ::model.isInitialized && model.state.value.ready && !model.state.value.busy }
        ui.waitForIdle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText(directory).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(directory).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == album.id && !model.state.value.busy }
        ui.waitUntil(10_000) {
            ui.onAllNodes(hasTestTag("video-preview-playing"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(4_000) // preview in the waterfall grid

        ui.onNodeWithContentDescription("播放视频：Demo-sunrise.mp4").performClick()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("video-detail"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3_000) // detail with sound
        ui.onNodeWithText("暂停").performClick()
        Thread.sleep(1_500)
        ui.onNodeWithTag("video-seek").performTouchInput { click(center.copy(x = width * .7f)) }
        Thread.sleep(1_500)
        ui.onNodeWithText("静音").performClick()
        Thread.sleep(1_500)
        ui.onNodeWithText("取消静音").performClick()
        Thread.sleep(1_500)
        ui.onNodeWithContentDescription("返回视频流").performClick()
        Thread.sleep(2_000) // muted preview resumes in the grid
        ui.onNodeWithContentDescription("个人账号").performClick()
        Thread.sleep(1_500)
        ui.onNodeWithContentDescription("设置").performClick()
        Thread.sleep(1_500)
        ui.onNodeWithTag("video-autoplay-switch").performScrollTo()
        Thread.sleep(1_500)
    }
}
