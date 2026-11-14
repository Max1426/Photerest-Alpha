package com.photerest.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoDeviceTest {
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

    private fun prepare() {
        scenario?.close()
        scenario = null
        context.deleteDatabase("photerest.db")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            grant(android.Manifest.permission.READ_MEDIA_IMAGES)
            grant(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else grant(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

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

    private fun seed(album: Album) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        try {
            db.store().addAlbums(listOf(Store.Album(album.id, album.name)))
            db.store().addFolder(Store.Folder(1, "默认收藏夹"))
            db.store().saveProfile(Store.Profile().also { it.name = "视频测试" })
        } finally { db.close() }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[PhotoModel::class.java] }
        ui.waitUntil(15_000) { ::model.isInitialized && model.state.value.ready && !model.state.value.busy }
        ui.waitForIdle()
    }

    private fun openAlbum(directory: String, albumId: String) {
        await(hasText(directory))
        ui.onNodeWithText(directory).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == albumId && !model.state.value.busy }
    }

    @After fun clean() {
        scenario?.close()
        sources.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun mixedAlbumMutedPreviewAndDetailControls() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        prepare()
        val directory = "VideoQA-${System.nanoTime() % 100000}"
        insert(false, "VideoQA-still.jpg", "scene0.jpg", directory)
        insert(true, "VideoQA-motion.mp4", "video-preview.mp4", directory)
        insert(true, "VideoQA-second.mp4", "video-preview.mp4", directory)

        // Daily recommendations and the default scan must keep seeing photos only.
        assertTrue(Gallery.scan(context).second.none { it.isVideo })
        val (albums, items) = Gallery.scan(context, includeVideos = true)
        val album = albums.single { it.name == directory }
        val media = items.filter { it.albumId == album.id }
        assertEquals(3, media.size)
        assertEquals(2, media.count { it.isVideo && it.durationMs >= 9900 && it.width < it.height })

        seed(album)
        launch()
        openAlbum(directory, album.id)
        await(hasTestTag("video-preview-active"))
        await(hasTestTag("video-preview-playing"))
        assertEquals("Only one muted preview may run at a time", 1,
            ui.onAllNodesWithTag("video-preview-active", useUnmergedTree = true).fetchSemanticsNodes().size)
        ui.onNodeWithContentDescription("查看照片：VideoQA-still.jpg").assertExists()
        await(hasTestTag("video-duration"))
        // Text-free mode is the default: the duration badge stays, the card footer menu does not.
        assertTrue(ui.onAllNodes(hasContentDescription("视频菜单：", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        screenshot("video-grid-preview")

        val active = listOf("VideoQA-motion.mp4", "VideoQA-second.mp4").firstOrNull { name ->
            ui.onAllNodes(hasContentDescription("播放视频：$name") and hasAnyDescendant(hasTestTag("video-preview-active")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        } ?: error("A muted preview must be active")
        val indexBefore = model.viewport(album.id).grid.firstVisibleItemIndex
        Thread.sleep(1500)
        ui.onNodeWithContentDescription("播放视频：$active").performClick()
        await(hasTestTag("video-detail"))
        await(hasText("暂停"))
        ui.onNodeWithTag("video-detail-audio-on", useUnmergedTree = true).assertExists()
        val progress = ui.onNodeWithTag("video-seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("Preview progress ($progress) must continue in the detail player", progress > 0f)
        screenshot("video-detail")

        ui.onNodeWithText("暂停").performClick()
        await(hasText("播放"))
        ui.onNodeWithText("播放").performClick()
        await(hasText("暂停"))
        ui.onNodeWithText("静音").performClick()
        await(hasText("取消静音"))
        ui.onNodeWithTag("video-detail-audio-off", useUnmergedTree = true).assertExists()
        ui.onNodeWithText("取消静音").performClick()
        await(hasText("静音"))
        ui.onNodeWithTag("video-seek").performTouchInput { click(center.copy(x = width * .65f)) }
        Thread.sleep(500)
        ui.onNodeWithTag("video-seek").performTouchInput { click(center.copy(x = width - 2f)) }
        ui.waitUntil(15_000) { ui.onAllNodesWithText("重播").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("重播").performClick()
        await(hasText("暂停"))

        ui.onNodeWithText("收藏").performClick()
        await(hasText("保存到收藏夹"))
        ui.onNodeWithText("保存").performClick()
        await(hasText("管理收藏"))

        ui.onNodeWithContentDescription("返回视频流").performClick()
        await(hasTestTag("video-preview-playing"))
        assertEquals("The grid keeps its scroll position after the video detail", indexBefore,
            model.viewport(album.id).grid.firstVisibleItemIndex)
        screenshot("video-back-to-grid")

        // The video detail offers the same "open in gallery" path as photos. This runs last because
        // the external viewer takes over the task and the test does not need to return afterwards.
        ui.onNodeWithContentDescription("播放视频：$active").performClick()
        await(hasTestTag("video-detail"))
        await(hasText("暂停"))
        ui.onNodeWithContentDescription("在相册中打开视频").assertIsDisplayed()
        val activePhoto = model.state.value.feed.first { it.name == active }
        instrumentation.checkOriginalOpened(activePhoto) {
            ui.onNodeWithContentDescription("在相册中打开视频").performClick()
        }
    }

    @Test fun backgroundStopsPreviewAndAutoplaySwitchDisablesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        prepare()
        val directory = "VideoBG-${System.nanoTime() % 100000}"
        insert(true, "VideoQA-background.mp4", "video-preview.mp4", directory)
        val (albums, items) = Gallery.scan(context, includeVideos = true)
        val album = albums.single { it.name == directory }
        assertTrue(items.any { it.albumId == album.id && it.isVideo })
        seed(album)
        launch()
        openAlbum(directory, album.id)
        await(hasTestTag("video-preview-playing"))

        // Backgrounding the app must stop the preview immediately (and therefore audio focus is never held).
        shell("logcat -c")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(1000)
        assertTrue("Background must stop the muted preview", shell("logcat -d -s VideoPlayback").contains("preview stopped"))
        shell("am start -n ${context.packageName}/${MainActivity::class.java.name}")
        await(hasText(directory))
        ui.onNodeWithText(directory).performClick()
        await(hasTestTag("video-preview-playing"))

        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithTag("video-autoplay-switch").performScrollTo().performClick()
        ui.waitForIdle()
        assertFalse(SettingsStore(context).state.value.autoplayVideos)
        screenshot("video-autoplay-off")
        ui.onNodeWithContentDescription("返回个人主页").performClick()
        ui.onNodeWithContentDescription("主页，刷新照片").performClick()
        await(hasText(directory))
        ui.onNodeWithText(directory).performClick()
        Thread.sleep(1500)
        assertTrue("Autoplay off must not start a preview",
            ui.onAllNodesWithTag("video-preview-active", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        scenario?.close()
        scenario = null
        launch()
        assertFalse("The autoplay preference must survive a restart", SettingsStore(context).state.value.autoplayVideos)
        await(hasText(directory))
        ui.onNodeWithText(directory).performClick()
        Thread.sleep(1500)
        assertTrue(ui.onAllNodesWithTag("video-preview-active", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun landscapeNoAudioShareAndDeletedVideoPlaceholder() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        prepare()
        val directory = "VideoWide-${System.nanoTime() % 100000}"
        val videoUri = insert(true, "VideoQA-wide.mp4", "video-landscape.mp4", directory)

        val assetBytes = instrumentation.context.assets.open("video-landscape.mp4").use { it.readBytes() }
        val extractor = MediaExtractor()
        extractor.setDataSource(context, videoUri, null)
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        extractor.release()
        assertTrue("The landscape fixture must contain a video track", mimes.any { it.startsWith("video/") })
        assertTrue("The landscape fixture must have no audio track", mimes.none { it.startsWith("audio/") })

        val (albums, items) = Gallery.scan(context, includeVideos = true)
        val album = albums.single { it.name == directory }
        val video = items.single { it.albumId == album.id && it.isVideo }
        assertTrue("Landscape fixture width ${video.width} height ${video.height}", video.width > video.height)
        assertTrue("Landscape fixture duration ${video.durationMs}", video.durationMs in 3_000..9_000)
        seed(album)
        launch()
        openAlbum(directory, album.id)
        await(hasTestTag("video-preview-playing"))
        await(hasTestTag("video-duration"))

        ui.onNodeWithContentDescription("播放视频：VideoQA-wide.mp4").performClick()
        await(hasTestTag("video-detail"))
        await(hasText("暂停"))
        val testPackage = instrumentation.context.packageName
        val receipt = "/sdcard/Android/data/$testPackage/files/share-receipt.txt"
        shell("rm -f $receipt")
        ui.onNodeWithContentDescription("分享视频").performClick()
        var receiver: android.view.accessibility.AccessibilityNodeInfo? = null
        val timeout = android.os.SystemClock.elapsedRealtime() + 8_000L
        while (receiver == null && android.os.SystemClock.elapsedRealtime() < timeout) {
            receiver = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("QA图片接收器")?.firstOrNull()
            if (receiver == null) Thread.sleep(100)
        }
        assertNotNull("The system sharesheet must offer the QA receiver for videos", receiver)
        var button = receiver!!
        while (!button.isClickable && button.parent != null) button = button.parent
        if (!button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = android.graphics.Rect().also { receiver?.getBoundsInScreen(it) }
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        var result = ""
        val receiptTimeout = android.os.SystemClock.elapsedRealtime() + 8_000L
        while (result != "video/mp4:${assetBytes.size}" && android.os.SystemClock.elapsedRealtime() < receiptTimeout) {
            Thread.sleep(100)
            result = shell("cat $receipt").trim()
        }
        assertEquals("video/mp4:${assetBytes.size}", result)
        shell("input keyevent 4")

        // Keep the video as a favorite, then delete the original so the favorites grid keeps its slot with a hint.
        ui.onNodeWithText("收藏").performClick()
        await(hasText("保存到收藏夹"))
        ui.onNodeWithText("保存").performClick()
        await(hasText("管理收藏"))
        ui.onNodeWithContentDescription("返回视频流").performClick()
        await(hasTestTag("video-preview-active"))
        context.contentResolver.delete(videoUri, null, null)
        sources.remove(videoUri)
        ui.onNodeWithContentDescription("主页，刷新照片").performClick()
        ui.waitUntil(15_000) { !model.state.value.busy }
        ui.onNodeWithContentDescription("个人账号").performClick()
        await(hasText("我收藏的图片"))
        ui.onNodeWithTag("profile-photos").performScrollToIndex(0)
        val card = ui.onNodeWithContentDescription("播放视频：VideoQA-wide.mp4")
        card.assertExists()
        await(hasText("原视频已删除或不可读取"))
        card.performClick()
        await(hasTestTag("video-detail"))
        await(hasText("原视频已删除或不可读取"))
        screenshot("video-deleted-placeholder")
        ui.onNodeWithContentDescription("返回视频流").performClick()
    }

    @Test fun videoDetailFlightExpandsAndReturnsToCard() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val previousScale = shell("settings get global animator_duration_scale").trim().ifBlank { "1" }
        shell("settings put global animator_duration_scale 1")
        try {
            prepare()
            val directory = "VideoFly-${System.nanoTime() % 100000}"
            insert(true, "VideoQA-flight.mp4", "video-preview.mp4", directory)
            val (albums, _) = Gallery.scan(context, includeVideos = true)
            val album = albums.single { it.name == directory }
            seed(album)
            launch()
            openAlbum(directory, album.id)
            await(hasTestTag("video-preview-playing"))
            val card = ui.onNodeWithContentDescription("播放视频：VideoQA-flight.mp4")
            val before = card.fetchSemanticsNode().boundsInRoot
            saveRawScreenshot("video-frame-before")

            val flight = ui.onAllNodesWithTag("photo-flight")
            ui.mainClock.autoAdvance = false
            try {
                card.performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    flight.fetchSemanticsNodes().isNotEmpty()
                }
                ui.mainClock.advanceTimeBy(64)
                flight.onFirst().assertExists()
                saveRawScreenshot("video-flight-enter")
            } finally {
                ui.mainClock.autoAdvance = true
            }
            await(hasTestTag("video-detail"))
            ui.waitUntil(5_000) { flight.fetchSemanticsNodes().isEmpty() }
            await(hasText("暂停"))
            Thread.sleep(300)
            saveRawScreenshot("video-frame-after")

            ui.mainClock.autoAdvance = false
            try {
                ui.onNodeWithContentDescription("返回视频流").performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    flight.fetchSemanticsNodes().isNotEmpty()
                }
                ui.mainClock.advanceTimeBy(64)
                flight.onFirst().assertExists()
                saveRawScreenshot("video-flight-return")
                ui.mainClock.advanceTimeBy(350)
            } finally {
                ui.mainClock.autoAdvance = true
            }
            await(hasTestTag("video-preview-active"))
            ui.waitUntil(5_000) { flight.fetchSemanticsNodes().isEmpty() }
            assertEquals("The video card must be restored at its original bounds", before,
                ui.onNodeWithContentDescription("播放视频：VideoQA-flight.mp4").fetchSemanticsNode().boundsInRoot)
        } finally {
            shell("settings put global animator_duration_scale $previousScale")
        }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    fun predictiveVideoReturnFollowsEdgeAndCancels() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val previousScale = shell("settings get global animator_duration_scale").trim().ifBlank { "1" }
        shell("settings put global animator_duration_scale 1")
        try {
            prepare()
            val directory = "VideoPred-${System.nanoTime() % 100000}"
            insert(true, "VideoQA-predictive.mp4", "video-preview.mp4", directory)
            val (albums, _) = Gallery.scan(context, includeVideos = true)
            val album = albums.single { it.name == directory }
            seed(album)
            launch()
            openAlbum(directory, album.id)
            await(hasTestTag("video-preview-playing"))
            ui.onNodeWithContentDescription("播放视频：VideoQA-predictive.mp4").performClick()
            await(hasTestTag("video-detail"))
            await(hasText("暂停"))

            val screen = instrumentation.uiAutomation.takeScreenshot()
            val screenWidth = screen.width.toFloat()
            val screenHeight = screen.height.toFloat()
            screen.recycle()
            val y = screenHeight * .48f
            fun swipeBack(cancel: Boolean) {
                val start = android.os.SystemClock.uptimeMillis()
                fun send(action: Int, x: Float) {
                    val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                    try {
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                    } finally { event.recycle() }
                }
                send(android.view.MotionEvent.ACTION_DOWN, 2f)
                try {
                    for (step in 1..20) {
                        Thread.sleep(30)
                        send(android.view.MotionEvent.ACTION_MOVE, screenWidth * .42f * step / 20)
                    }
                    ui.waitUntil(3_000) {
                        ui.onAllNodes(hasTestTag("photo-flight"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    saveRawScreenshot(if (cancel) "video-predictive-cancel" else "video-predictive-confirm")
                } finally {
                    send(if (cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP, screenWidth * .42f)
                }
            }

            swipeBack(cancel = true)
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("photo-flight"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithContentDescription("返回视频流").assertIsDisplayed()
            await(hasText("暂停"))

            swipeBack(cancel = false)
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("video-detail"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            await(hasTestTag("video-preview-active"))
        } finally {
            shell("settings put global animator_duration_scale $previousScale")
        }
    }

    @Test fun hidingVideosRemovesThemFromTheWaterfallAndPersists() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        prepare()
        val directory = "VideoHide-${System.nanoTime() % 100000}"
        insert(false, "VideoQA-hide-still.jpg", "scene0.jpg", directory)
        insert(true, "VideoQA-hide.mp4", "video-preview.mp4", directory)
        val (albums, _) = Gallery.scan(context, includeVideos = true)
        val album = albums.single { it.name == directory }
        seed(album)
        launch()
        openAlbum(directory, album.id)
        await(hasTestTag("video-duration"))
        ui.onNodeWithContentDescription("查看照片：VideoQA-hide-still.jpg").assertExists()

        fun openSettings() {
            ui.onNodeWithContentDescription("个人账号").performClick()
            ui.onNodeWithContentDescription("设置").performClick()
        }
        fun toggleShowVideos() {
            ui.onNodeWithTag("show-videos-switch").performScrollTo().performClick()
        }
        fun backHome() {
            ui.onNodeWithContentDescription("返回个人主页").performClick()
            ui.onNodeWithContentDescription("主页，刷新照片").performClick()
        }

        openSettings()
        toggleShowVideos()
        ui.waitUntil(5_000) { !SettingsStore(context).state.value.showVideos }
        backHome()
        ui.waitUntil(15_000) { !model.state.value.busy }
        await(hasText(directory))
        ui.onNodeWithText(directory).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == album.id && !model.state.value.busy }
        assertEquals("Videos disappear from the waterfall", 0, model.state.value.feed.count { it.isVideo })
        assertTrue(ui.onAllNodes(hasContentDescription("播放视频：", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        ui.onNodeWithContentDescription("查看照片：VideoQA-hide-still.jpg").assertExists()
        assertEquals("The album count only includes photos", 1, model.state.value.imported.single { it.id == album.id }.count)
        screenshot("video-hidden-grid")

        scenario!!.close()
        scenario = null
        launch()
        assertFalse("The video visibility choice survives a restart", SettingsStore(context).state.value.showVideos)
        assertTrue(model.state.value.feed.none { it.isVideo })

        openSettings()
        toggleShowVideos()
        ui.waitUntil(5_000) { SettingsStore(context).state.value.showVideos }
        backHome()
        ui.waitUntil(15_000) { !model.state.value.busy }
        await(hasText(directory))
        ui.onNodeWithText(directory).performClick()
        await(hasTestTag("video-duration"))
    }

    @Test fun recordedFixturesMatchTheirTracksAndDimensions() {        assumeTrue(Build.VERSION.SDK_INT >= 29)
        prepare()
        val directory = "VideoFX-${System.nanoTime() % 100000}"
        val portrait = insert(true, "VideoFX-portrait.mp4", "video-preview.mp4", directory)
        val landscape = insert(true, "VideoFX-landscape.mp4", "video-landscape.mp4", directory)
        val (_, items) = Gallery.scan(context, includeVideos = true)
        val portraitPhoto = items.single { it.name == "VideoFX-portrait.mp4" }
        val landscapePhoto = items.single { it.name == "VideoFX-landscape.mp4" }
        assertTrue(portraitPhoto.width < portraitPhoto.height)
        assertTrue(portraitPhoto.durationMs >= 9_900)
        assertTrue(landscapePhoto.width > landscapePhoto.height)
        assertTrue(landscapePhoto.durationMs in 3_000..9_000)
    }

    private fun saveRawScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        Thread.sleep(650)
        val directory = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun await(matcher: SemanticsMatcher) {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
