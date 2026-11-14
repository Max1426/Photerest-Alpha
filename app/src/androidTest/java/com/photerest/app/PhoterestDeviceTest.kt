package com.photerest.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.material3.dynamicLightColorScheme
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

@RunWith(AndroidJUnit4::class)
class PhoterestDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: PhotoModel
    private val sources = mutableListOf<Uri>()
    private lateinit var album: Album
    private lateinit var fixturePhotos: List<Photo>
    private val fixtureAlbum = "光影集-${System.nanoTime() % 100000}"

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText() }
    @Before fun setup() {
        context.deleteDatabase("photerest.db")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        grantPhotos()
        val sizes = listOf(720 to 1080, 900 to 650, 700 to 1200, 720 to 900, 400 to 2400, 2048 to 1536)
        sizes.forEachIndexed { index, (w, h) -> sources.add(insertPhoto(index, w, h)) }
        val scan = Gallery.scan(context)
        val sourceIds = sources.map { android.content.ContentUris.parseId(it) }.toSet()
        fixturePhotos = scan.second.filter { p -> android.content.ContentUris.parseId(Uri.parse(p.uri)) in sourceIds }
        album = scan.first.first { it.id == fixturePhotos.first().albumId }
    }
    private fun grantPhotos() {
        if (android.os.Build.VERSION.SDK_INT < 33) shell("pm grant ${context.packageName} android.permission.READ_EXTERNAL_STORAGE")
        else shell("pm grant ${context.packageName} android.permission.READ_MEDIA_IMAGES")
        if (android.os.Build.VERSION.SDK_INT >= 34) shell("pm grant ${context.packageName} android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    }

    private fun insertPhoto(index: Int, w: Int, h: Int, albumName: String = fixtureAlbum, contrast: Boolean = false): Uri {
        val names = listOf("Coastal-light", "Quiet-mountains", "Summer-garden", "A-little-blue", "Long-panorama", "Large-landscape")
        val name = if (contrast) "Glass-contrast.jpg" else "${names[index]}.jpg"
        val modern = android.os.Build.VERSION.SDK_INT >= 29
        val oldFile = if (modern) null else File("/sdcard/Pictures/$albumName/$name")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, 1_700_000_000_000L + index * 1000)
            if (modern) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                shell("mkdir -p ${oldFile!!.parent}")
            }
        }
        val uri = if (modern) context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) else null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val source = if (contrast) null else runCatching { instrumentation.context.assets.open("scene${index % 4}.jpg").use(BitmapFactory::decodeStream) }.getOrNull()
        if (contrast) {
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(0f, 0f, w * .45f, h.toFloat(), Paint().apply { color = Color.WHITE })
        } else if (source != null) {
            val ratio = maxOf(w.toFloat() / source.width, h.toFloat() / source.height)
            val dw = source.width * ratio; val dh = source.height * ratio
            canvas.drawBitmap(source, null, android.graphics.RectF((w-dw)/2, (h-dh)/2, (w+dw)/2, (h+dh)/2), Paint(3))
            source.recycle()
        } else canvas.drawColor(Color.rgb(60 + index*20, 100, 150))
        if (uri == null) {
            val fixture = oldFile!!
            val staged = File(context.getExternalFilesDir(null), "motion-fixture.jpg")
            staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            shell("cp ${staged.absolutePath} ${fixture.absolutePath}")
            staged.delete()
            bitmap.recycle()
            val path = "/storage/emulated/0/Pictures/$albumName/$name"
            val media = "content://media/external/images/media"
            shell("content insert --uri $media --bind _data:s:$path --bind _display_name:s:$name --bind mime_type:s:image/jpeg --bind width:i:$w --bind height:i:$h --bind datetaken:l:1700000000000")
            val result = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "_data=?", arrayOf(path), null)
            val id = result.use { cursor -> check(cursor != null && cursor.moveToFirst()) { "Missing test photo: $path" }; cursor.getLong(0) }
            return android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use {
            val exif = androidx.exifinterface.media.ExifInterface(it.fileDescriptor)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:01 12:30:00")
            if (index == 0) exif.setLatLong(31.2304, 121.4737)
            exif.saveAttributes()
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun seed(profile: Boolean = false, favorites: Boolean = false, favoriteCount: Int = 3) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "photerest.db").build()
        db.store().addAlbums(listOf(Store.Album(album.id, album.name)))
        db.store().addFolder(Store.Folder(1, "默认收藏夹"))
        if (profile) db.store().saveProfile(Store.Profile().also { it.name = "我的光影" })
        if (favorites) {
            fixturePhotos.take(favoriteCount).forEach { photo -> db.store().savePhoto(photo.stored()); db.store().favorite(Store.Favorite(photo.uri, 1)) }
        }
        db.close()
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[PhotoModel::class.java] }
        settle()
    }
    private fun settle() { ui.waitUntil(15_000) { ::model.isInitialized && model.state.value.ready && !model.state.value.busy }; ui.waitForIdle() }
    private fun awaitDetail() {
        ui.waitUntil(10_000) {
            ui.onNodeWithContentDescription("照片信息").isDisplayed() &&
                ui.onAllNodesWithTag("photo-flight").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        Thread.sleep(650) // Let the native window and asynchronous thumbnail decoders finish.
        ui.waitForIdle() // Flush bitmap state changes through the Compose test clock before capture.
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @After fun cleanup() {
        scenario?.close()
        sources.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        grantPhotos()
    }

    @Test fun importRefreshMenuDetailAndShare() {
        launch()
        // This flow asserts the grid menu, which the default text-free grid hides.
        model.settings.setNoTextMode(false)
        ui.waitForIdle()
        assertTrue(model.state.value.imported.isEmpty())
        assertEquals(model.state.value.library.keys, model.state.value.feed.map { it.uri }.toSet())
        assertTrue(model.state.value.feed.map { it.uri }.containsAll(fixturePhotos.map { it.uri }))
        ui.onNodeWithText("关联手机相册").assertDoesNotExist()
        screenshot("01-all-without-albums")
        ui.onNodeWithContentDescription("导入相册").performClick()
        ui.onNodeWithText(album.name).performClick()
        ui.onNodeWithText("关联 1 个相册").performClick()
        ui.waitUntil(10_000) { model.state.value.imported.size == 1 && !model.state.value.busy }
        assertNull(model.state.value.selectedAlbum)
        assertEquals(model.state.value.library.keys, model.state.value.feed.map { it.uri }.toSet())
        ui.onNodeWithText(album.name).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == album.id && !model.state.value.busy }
        assertEquals(fixturePhotos.map { it.uri }.toSet(), model.state.value.feed.map { it.uri }.toSet())
        settle()
        screenshot("02-home")
        val first = model.state.value.feed.map { it.uri }
        ui.onNodeWithContentDescription("主页，刷新照片").performClick()
        ui.waitUntil(10_000) { model.state.value.feed.map { it.uri } != first }
        settle()
        val second = model.state.value.feed.map { it.uri }
        val feed = ui.onNodeWithTag("home-feed")
        val contentStart = ui.onNodeWithText("全部").fetchSemanticsNode().boundsInRoot.bottom -
            feed.fetchSemanticsNode().boundsInRoot.top
        feed.performTouchInput { swipeDown(startY = contentStart + 60f, endY = height * .8f, durationMillis = 800) }
        ui.waitUntil(10_000) { model.state.value.feed.map { it.uri } != second }
        settle()
        val photo = model.state.value.feed.first { it.aspect >= .6f }
        ui.onNodeWithTag("home-feed").performScrollToIndex(model.state.value.feed.indexOf(photo))
        ui.onNodeWithContentDescription("照片菜单：${photo.name}").performClick()
        ui.onNodeWithText("分享").assertExists(); ui.onNodeWithText("收藏").assertExists()
        screenshot("03-photo-menu")
        shell("input keyevent 4"); ui.waitForIdle()
        ui.onNodeWithContentDescription("查看照片：${photo.name}").performClick()
        awaitDetail()
        ui.onNodeWithContentDescription("照片信息").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("像素尺寸").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("像素尺寸").performScrollTo().assertIsDisplayed()
        screenshot("05-photo-info")
        shell("input keyevent 4"); ui.waitForIdle()
        screenshot("04-detail")
        instrumentation.checkOriginalOpened(photo) {
            ui.onNodeWithContentDescription("在相册中打开原图").assertIsDisplayed().performClick()
        }
        ui.onNodeWithText(photo.name).assertIsDisplayed()
        shell("rm -f /sdcard/Android/data/${instrumentation.context.packageName}/files/share-receipt.txt")
        ui.onNodeWithContentDescription("分享照片").performClick()
        Thread.sleep(1200)
        assertTrue(shell("dumpsys activity activities").contains("ChooserActivity"))
        assertTrue(File(context.cacheDir, "shares").listFiles().orEmpty().any { it.length() > 0 })
        screenshot("06-system-share")
        var receiver: android.view.accessibility.AccessibilityNodeInfo? = null
        repeat(50) {
            if (receiver == null) {
                receiver = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("QA图片接收器")?.firstOrNull()
                if (receiver == null) Thread.sleep(100)
            }
        }
        assertNotNull("The separate-UID receiver must be offered by the system sharesheet", receiver)
        var button = receiver!!
        while (!button.isClickable && button.parent != null) button = button.parent
        assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        var receipt = ""
        repeat(50) {
            if (!receipt.startsWith("image/")) {
                Thread.sleep(100)
                receipt = shell("cat /sdcard/Android/data/${instrumentation.context.packageName}/files/share-receipt.txt")
            }
        }
        assertTrue(receipt, receipt.startsWith(photo.mime))
        assertTrue(receipt, receipt.substringAfterLast(':').trim().toLong() > 0)
        screenshot("12-share-received")
        shell("input keyevent 4")
    }

    @Test fun originalViewerReturnsToZoomedDetail() {
        launch()
        val feed = model.state.value.feed
        val photo = feed.first { it in fixturePhotos && it.aspect >= .6f }
        ui.onNodeWithTag("home-feed").performScrollToIndex(feed.indexOf(photo))
        ui.onNodeWithContentDescription("查看照片：${photo.name}").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val originalButton = ui.onNodeWithContentDescription("在相册中打开原图")
        val originalBounds = originalButton.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val favoriteBounds = ui.onNodeWithText("收藏").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        var rightEdge = 0f
        for (description in listOf("分享照片", "在相册中打开原图")) {
            val bounds = ui.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$description bounds $bounds at density $density",
                bounds.left >= rightEdge && bounds.width >= 48 * density - 1 && bounds.height >= 48 * density - 1)
            rightEdge = bounds.right
        }
        assertTrue(originalBounds.right <= favoriteBounds.left)

        val bitmaps = ui.onAllNodesWithTag("bitmap-${photo.uri}", useUnmergedTree = true)
        ui.waitUntil(15_000) { bitmaps.fetchSemanticsNodes().any { it.boundsInRoot.width > originalBounds.width * 4 } }
        screenshot("detail-toolbar")
        fun zoomSample() = ui.onRoot().captureToImage().toPixelMap().let { pixels ->
            (2..6).flatMap { row ->
                (2..6).map { column -> pixels[pixels.width * column / 8, pixels.height * row / 8] }
            }
        }
        val unzoomed = zoomSample()
        bitmaps.onLast().performTouchInput { doubleClick() }
        val zoomed = zoomSample()
        assertNotEquals("Double-tapping zooms the photo", unzoomed, zoomed)
        instrumentation.checkOriginalOpened(photo) { originalButton.performClick() }
        ui.onNodeWithText(photo.name).assertIsDisplayed()
        assertEquals("Returning from the viewer keeps the zoomed image", zoomed, zoomSample())
    }

    @Test fun photoRoundTripKeepsHomeCardPosition() {
        launch()
        val photo = fixturePhotos.first { it.height > it.width && it.aspect >= .5f }
        val order = model.state.value.feed.map { it.uri }
        ui.onNodeWithTag("home-feed").performScrollToIndex(order.indexOf(photo.uri))
        val card = ui.onNodeWithContentDescription("查看照片：${photo.name}")
        val bounds = card.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        card.performClick()
        awaitDetail()
        ui.onNodeWithContentDescription("返回照片流").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("返回照片流").fetchSemanticsNodes().isEmpty() }
        assertEquals(bounds, card.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        assertEquals(order, model.state.value.feed.map { it.uri })
    }

    @Test fun photoMotionReturnsToAllFourSources() {
        seed(profile = true, favorites = true, favoriteCount = fixturePhotos.size)
        launch()
        val landscape = fixturePhotos.first { it.aspect > 1f }
        val portrait = fixturePhotos.first { it.aspect in .5f.. .8f }
        val panorama = fixturePhotos.first { it.aspect < .25f }
        fun openAndReturn(tag: String, photos: List<Photo>, photo: Photo, useSystemBack: Boolean = false) {
            val list = ui.onNodeWithTag(tag)
            list.performScrollToIndex(photos.indexOfFirst { it.uri == photo.uri })
            val bitmapTag = "bitmap-${photo.uri}"
            val bitmap = ui.onAllNodesWithTag(bitmapTag, useUnmergedTree = true)
            ui.waitUntil(10_000) { bitmap.fetchSemanticsNodes().isNotEmpty() }
            val card = ui.onNode(hasContentDescription("查看照片：${photo.name}") and hasAnyDescendant(hasTestTag(bitmapTag)), useUnmergedTree = true)
            val bounds = card.fetchSemanticsNode().boundsInRoot
            card.performClick()
            ui.waitUntil(10_000) {
                ui.onAllNodesWithContentDescription("在相册中打开原图").fetchSemanticsNodes().size == 1 &&
                    ui.onAllNodesWithTag("photo-flight").fetchSemanticsNodes().isEmpty()
            }
            ui.onNodeWithContentDescription("在相册中打开原图").assertIsDisplayed()
            if (useSystemBack) shell("input keyevent 4")
            else ui.onNodeWithContentDescription("返回照片流").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("返回照片流").fetchSemanticsNodes().isEmpty() }
            assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
            assertEquals(photos.map { it.uri }, (if (tag == "home-feed") model.state.value.feed else model.state.value.favorites).map { it.uri })
        }
        openAndReturn("home-feed", model.state.value.feed, landscape, useSystemBack = true)
        ui.onNodeWithText(album.name, substring = false).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == album.id }
        openAndReturn("home-feed", model.state.value.feed, panorama)
        ui.onNodeWithContentDescription("个人账号").performClick()
        openAndReturn("profile-photos", model.state.value.favorites, portrait)
        ui.onNodeWithText("收藏夹", substring = false).performClick()
        ui.onNodeWithText("默认收藏夹", substring = false).performClick()
        ui.onNodeWithText("查看全部").assertIsDisplayed()
        openAndReturn("profile-photos", model.state.value.favorites, landscape)
        ui.onNodeWithText("查看全部").assertIsDisplayed()
    }

    @Test fun displayedPhotosStillAnimateAfterThumbnailCacheIsCleared() {
        val previous = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 1")
        try {
            checkFlightsAfterCacheClear()
        } finally {
            if (previous == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $previous")
        }
    }

    private fun checkFlightsAfterCacheClear() {
        launch()
        ui.waitUntil(5_000) { android.animation.ValueAnimator.areAnimatorsEnabled() }
        val feed = model.state.value.feed
        listOf(fixturePhotos.first { it.aspect > 1f }, fixturePhotos.first { it.aspect in .5f.. .8f }).forEach { photo ->
            val index = feed.indexOfFirst { it.uri == photo.uri }
            val homeGrid = model.viewport(null).grid
            // Keep the fixture clear of the overlaid header when asserting an actual flight.
            ui.runOnIdle { homeGrid.requestScrollToItem(index) }
            ui.waitForIdle()
            val tag = "bitmap-${photo.uri}"
            val card = ui.onNode(hasContentDescription("查看照片：${photo.name}") and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
            ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val origin = card.fetchSemanticsNode().boundsInRoot
            assertTrue("${photo.name} must be visible: $origin", origin.height > 0f)
            ui.runOnIdle {
                PhotoBitmaps.clear()
                assertNull(PhotoBitmaps.peek(photo.uri, photo.added))
            }
            ui.mainClock.autoAdvance = false
            try {
                card.performSemanticsAction(SemanticsActions.OnClick) { it() }
                val flight = ui.onAllNodesWithTag("photo-flight")
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    flight.fetchSemanticsNodes().isNotEmpty()
                }
                ui.mainClock.advanceTimeBy(64)
                flight.onFirst().assertExists()
                ui.onNodeWithContentDescription("返回照片流").performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.mainClock.advanceTimeBy(350)
            } finally {
                ui.mainClock.autoAdvance = true
            }
            ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("返回照片流").fetchSemanticsNodes().isEmpty() }
            assertEquals(origin, card.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    fun predictivePhotoReturnFollowsEdgeAndCancels() {
        launch()
        val photo = model.state.value.feed.first { it.aspect > 1f && it in fixturePhotos }
        ui.onNodeWithTag("home-feed").performScrollToIndex(model.state.value.feed.indexOf(photo))
        val card = ui.onNodeWithContentDescription("查看照片：${photo.name}")
        val bounds = card.fetchSemanticsNode().boundsInRoot
        val bitmaps = ui.onAllNodesWithTag("bitmap-${photo.uri}", useUnmergedTree = true)
        ui.waitUntil(10_000) { bitmaps.fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { PhotoBitmaps.clear() }
        card.performClick()
        val back = ui.onNodeWithContentDescription("返回照片流")
        back.assertIsDisplayed()
        val flight = ui.onAllNodesWithTag("photo-flight")
        ui.waitUntil(10_000) { flight.fetchSemanticsNodes().isEmpty() }
        bitmaps.onLast().performTouchInput { doubleClick() }
        bitmaps.onLast().performTouchInput {
            swipe(center, center + Offset(width * .18f, height * .14f), durationMillis = 350)
        }
        screenshot("predictive-detail-before")
        val bitmapBounds = bitmaps.onLast().fetchSemanticsNode().boundsInRoot
        val screen = instrumentation.uiAutomation.takeScreenshot()
        val screenWidth = screen.width.toFloat()
        val y = screen.height * .48f
        screen.recycle()
        fun swipeBack(cancel: Boolean) {
            val start = android.os.SystemClock.uptimeMillis()
            fun send(action: Int, x: Float) {
                val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                try {
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            send(android.view.MotionEvent.ACTION_DOWN, 2f)
            try {
                for (step in 1..20) {
                    android.os.SystemClock.sleep(30)
                    send(android.view.MotionEvent.ACTION_MOVE, screenWidth * .42f * step / 20)
                }
                screenshot(if (cancel) "predictive-held-cancel" else "predictive-held-confirm")
                flight.onFirst().assertExists()
            } finally {
                val action = if (cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP
                send(action, screenWidth * .42f)
            }
        }
        swipeBack(cancel = true)
        ui.waitUntil(5_000) { flight.fetchSemanticsNodes().isEmpty() }
        back.assertIsDisplayed()
        assertEquals(bitmapBounds, bitmaps.onLast().fetchSemanticsNode().boundsInRoot)
        screenshot("predictive-detail-restored")
        swipeBack(cancel = false)
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("返回照片流").fetchSemanticsNodes().isEmpty() }
        assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
        screenshot("predictive-list-restored")
    }

    @Test fun browserChromeProtectsControlsAndLeavesLastPhotoReachable() {
        seed(profile = true, favorites = true, favoriteCount = fixturePhotos.size)
        launch()
        // The footer menu position is asserted below; reveal it from its default hidden state.
        model.settings.setNoTextMode(false)
        ui.waitForIdle()
        ui.onNodeWithText(album.name).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == album.id && !model.state.value.busy }
        val grid = ui.onNodeWithTag("home-feed")
        val tabBottom = ui.onNodeWithText("全部").fetchSemanticsNode().boundsInRoot.bottom
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val state = model.viewport(album.id).grid
        ui.runOnIdle { state.requestScrollToItem(0, (tabBottom * .48f).toInt()) }
        screenshot("chrome-home-dark")
        grid.performTouchInput { click(Offset(width * .85f, tabBottom - gridBounds.top - 8f)) }
        ui.onNodeWithContentDescription("返回照片流").assertDoesNotExist()
        ui.onNodeWithContentDescription("导入相册").assertIsDisplayed()

        model.settings.setDark(false)
        screenshot("chrome-home-light")
        val last = model.state.value.feed.last()
        grid.performScrollToIndex(model.state.value.feed.lastIndex)
        val menu = ui.onNodeWithContentDescription("照片菜单：${last.name}")
        val navigationTop = ui.onNodeWithContentDescription("个人账号").fetchSemanticsNode().boundsInRoot.top
        assertTrue(menu.fetchSemanticsNode().boundsInRoot.center.y < navigationTop)
        menu.performTouchInput { click() }
        ui.onNodeWithText("分享").assertIsDisplayed()
        shell("input keyevent 4")

        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithText("个人主页").assertIsDisplayed()
        val favoritesTop = ui.onNodeWithText("我收藏的图片").fetchSemanticsNode().boundsInRoot.bottom
        ui.onRoot().performTouchInput {
            swipeUp(startY = height * .72f, endY = favoritesTop + 12f, durationMillis = 800)
        }
        screenshot("chrome-profile-light")
        ui.onNodeWithText("收藏夹").performClick()
        ui.onNodeWithText("新建收藏夹").assertIsDisplayed()
    }

    @Test fun browserChromeShowsContinuousFadeAcrossTallPhotos() {
        val contrastAlbum = "渐变"
        val contrastPhotos = (0..1).map { index ->
            insertPhoto(index, 400, 2200, contrastAlbum, contrast = true).also { sources.add(it) }
        }
        val scan = Gallery.scan(context)
        val contrastIds = contrastPhotos.map(android.content.ContentUris::parseId).toSet()
        val contrastPhoto = scan.second.first { android.content.ContentUris.parseId(Uri.parse(it.uri)) in contrastIds }
        album = scan.first.first { it.id == contrastPhoto.albumId }
        seed()
        launch()
        ui.onNodeWithText(contrastAlbum).performClick()
        ui.waitUntil(10_000) { model.state.value.selectedAlbum == album.id && model.state.value.feed.size == 2 }
        val grid = ui.onNodeWithTag("home-feed")
        val height = grid.fetchSemanticsNode().boundsInRoot.height
        ui.runOnIdle { model.viewport(album.id).grid.requestScrollToItem(0, (height * 0.31f).toInt()) }
        for (photo in model.state.value.feed) {
            ui.waitUntil(10_000) { ui.onNodeWithTag("bitmap-${photo.uri}", useUnmergedTree = true).isDisplayed() }
        }
        screenshot("chrome-seam-dark")
        model.settings.setDark(false)
        screenshot("chrome-seam-light")
        grid.performTouchInput { swipeUp(startY = height * .7f, endY = height * .38f, durationMillis = 120) }
        grid.assertIsDisplayed()
        ui.onNodeWithContentDescription("个人账号").assertIsDisplayed()
    }

    @Test fun firstFavoriteCreatesProfileAndPersistsAcrossRestart() {
        seed(); launch()
        val photo = model.state.value.feed.first { p -> p.aspect >= .6f && fixturePhotos.any { it.uri == p.uri } }
        ui.onNodeWithTag("home-feed").performScrollToIndex(model.state.value.feed.indexOf(photo))
        ui.onNodeWithContentDescription("查看照片：${photo.name}").performClick()
        awaitDetail()
        ui.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        ui.onNodeWithText("创建个人档案").assertExists()
        ui.onNode(hasSetTextAction()).performTextInput("我的光影")
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10_000) { ui.onNodeWithText("保存到收藏夹").isDisplayed() }
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(10_000) { model.state.value.favorites.size == 1 }
        model.saveProfile("我的光影", Uri.parse(photo.uri)) {}
        ui.waitUntil(10_000) { model.state.value.avatar != null }
        assertTrue(File(Uri.parse(model.state.value.avatar).path!!).exists())
        scenario!!.close(); scenario = null
        launch()
        assertEquals("我的光影", model.state.value.profileName)
        assertTrue(model.state.value.isFavorite(photo))
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithText("1 张收藏 · 1 个收藏夹").assertExists()
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("bitmap-${photo.uri}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("07-profile")
        ui.onNodeWithContentDescription("查看照片：${photo.name}").performClick()
        instrumentation.checkOriginalOpened(photo) {
            ui.onNodeWithContentDescription("在相册中打开原图").performClick()
        }
        ui.onNodeWithText(photo.name).assertIsDisplayed()
    }

    @Test fun unlinkDoesNotRemoveFavoritesAndDeletedOriginalIsRetained() {
        seed(profile = true, favorites = true); launch()
        val saved = model.state.value.favorites.first()
        model.unlinkAlbum(album.id)
        ui.waitUntil(10_000) { model.state.value.imported.isEmpty() }
        assertEquals(3, model.state.value.favorites.size)
        assertTrue(model.state.value.feed.any { it.uri == saved.uri })
        assertEquals(model.state.value.library.keys, model.state.value.feed.map { it.uri }.toSet())
        assertNotNull(context.contentResolver.openInputStream(Uri.parse(saved.uri))?.use { it.read() })
        val withoutViewer = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: android.content.Intent) {
                throw android.content.ActivityNotFoundException()
            }
        }
        assertEquals("没有可用的相册或图片查看应用", runBlocking { Gallery.openOriginal(withoutViewer, saved) })
        context.contentResolver.delete(Uri.parse(saved.uri), null, null)
        assertEquals("原图已删除或无法读取", runBlocking { Gallery.openOriginal(context, saved) })
        model.reload()
        ui.waitUntil(10_000) { !model.state.value.library.containsKey(saved.uri) }
        assertTrue(model.state.value.favorites.any { it.uri == saved.uri })
        assertFalse(model.state.value.available(saved))
        ui.onNodeWithContentDescription("个人账号").performClick()
        screenshot("08-missing-original")
    }

    @Test fun foldersAreIndependentAndMembershipsAreDeduplicated() {
        seed(profile = true); launch()
        model.createFolder("旅行")
        ui.waitUntil(10_000) { model.state.value.folders.size == 2 }
        val folder = model.state.value.folders.last()
        val photo = fixturePhotos.first()
        model.saveFavorite(photo, setOf(1, folder.id)) {}
        ui.waitUntil(10_000) { model.state.value.memberships.size == 2 }
        model.saveFavorite(photo, setOf(1, folder.id)) {}
        settle()
        assertEquals(1, model.state.value.favorites.size)
        model.renameFolder(folder.id, "旅途")
        ui.waitUntil(10_000) { model.state.value.folders.last().name == "旅途" }
        model.deleteFolder(folder.id)
        ui.waitUntil(10_000) { model.state.value.folders.size == 1 }
        assertEquals(1, model.state.value.favorites.size)
        assertEquals(1, model.state.value.memberships.size)
        ui.onNodeWithContentDescription("个人账号").performClick()
        ui.onNodeWithText("收藏夹", substring = false).performClick()
        screenshot("09-folders")
    }

    @Test fun addingPhotoToLinkedAlbumAppearsAfterRefresh() {
        seed(); launch()
        val initial = model.state.value.feed.size
        sources.add(insertPhoto(0, 800, 1200))
        model.reload(true)
        ui.waitUntil(10_000) { model.state.value.feed.size == initial + 1 }
        assertEquals(initial + 1, model.state.value.feed.distinctBy { it.uri }.size)
        val secondUri = insertPhoto(1, 900, 650, "另一相册-$fixtureAlbum")
        sources.add(secondUri)
        model.reload().joinForTest()
        val secondAlbum = model.state.value.deviceAlbums.first { it.name == "另一相册-$fixtureAlbum" }
        model.importAlbums(setOf(secondAlbum.id))
        ui.waitUntil(10_000) { model.state.value.imported.size == 2 }
        scenario!!.onActivity { model.selectAlbum(album.id); model.selectAlbum(secondAlbum.id) }
        ui.waitUntil(10_000) { !model.state.value.busy && model.state.value.selectedAlbum == secondAlbum.id && model.state.value.feed.isNotEmpty() }
        assertTrue(model.state.value.feed.all { it.albumId == secondAlbum.id })
    }

    @Test
    fun materialAppearanceSwitchesAndKeepsSettingsRoute() {
        seed(profile = true, favorites = true)
        launch()
        val profile = ui.onNodeWithContentDescription("个人账号")
        val home = ui.onNodeWithContentDescription("主页，刷新照片")
        fun openSettings() {
            profile.performClick()
            ui.onNodeWithContentDescription("设置").performClick()
        }
        fun choose(style: OperationStyle) {
            ui.onNodeWithText("界面风格").performScrollTo().performClick()
            ui.onNodeWithTag("operation-style-${style.name}").performClick()
            ui.onNodeWithContentDescription("返回个人主页").assertIsDisplayed()
            ui.onNodeWithText(style.label).assertExists()
            assertEquals(style, model.settings.state.value.operationStyle)
        }
        fun surfacePixel(): Int {
            val pixels = ui.onNodeWithTag("operation-page").captureToImage().toPixelMap()
            return pixels[3, pixels.height / 2].toArgb()
        }
        fun checkBrowser(style: String) {
            ui.onNodeWithContentDescription("返回个人主页").performClick()
            ui.onNodeWithText("个人主页").assertIsDisplayed()
            screenshot("$style-profile")
            ui.onNodeWithText("收藏夹").performClick()
            ui.onNodeWithText("新建收藏夹").assertIsDisplayed()
            ui.onNodeWithText("我收藏的图片").performClick()
            val initialFeed = model.state.value.feed.map { it.uri }
            home.performClick()
            ui.onNodeWithTag("home-feed").assertIsDisplayed()
            assertEquals(initialFeed, model.state.value.feed.map { it.uri })
            screenshot("$style-home")
            val photo = model.state.value.feed.first { it in fixturePhotos && it.aspect >= .6f }
            ui.onNodeWithTag("home-feed").performScrollToIndex(model.state.value.feed.indexOf(photo))
            ui.onNodeWithContentDescription("查看照片：${photo.name}").performClick()
            ui.onNodeWithContentDescription("返回照片流").assertIsDisplayed()
            ui.onNodeWithContentDescription("在相册中打开原图").assertIsDisplayed()
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("bitmap-${photo.uri}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            screenshot("$style-detail")
            shell("input keyevent 4")
            ui.onNodeWithTag("home-feed").assertIsDisplayed()
        }
        openSettings()
        ui.onNodeWithText(OperationStyle.STANDARD.label).assertExists()
        assertTrue(model.settings.state.value.dark)
        assertFalse(model.settings.state.value.dynamicColor)
        screenshot("standard-settings")
        assertEquals(androidx.compose.material3.darkColorScheme().surface.toArgb(), surfacePixel())
        checkBrowser("standard")

        openSettings()
        choose(OperationStyle.EXPRESSIVE)
        ui.onNodeWithText("浅色").performScrollTo().performClick()
        assertFalse(model.settings.state.value.dark)
        screenshot("expressive-settings")
        checkBrowser("expressive")
        openSettings()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ui.onNodeWithTag("dynamic-color-switch").performScrollTo().performClick().assertIsOn()
            assertEquals(dynamicLightColorScheme(context).surface.toArgb(), surfacePixel())
            screenshot("expressive-dynamic")
        } else {
            ui.onNodeWithTag("dynamic-color-switch").performScrollTo().assertIsNotEnabled()
        }

        scenario!!.close()
        scenario = null
        launch()
        openSettings()
        ui.onNodeWithText(OperationStyle.EXPRESSIVE.label).assertExists()
        assertFalse(model.settings.state.value.dark)
        assertEquals(android.os.Build.VERSION.SDK_INT >= 31, model.settings.state.value.dynamicColor)
    }

    private fun kotlinx.coroutines.Job.joinForTest() = runBlocking { join() }

    @Test fun metadataAndBoundedDecodingHandleLongAndLargePhotos() {
        val large = fixturePhotos.first { it.width > 2000 }
        val long = fixturePhotos.first { it.height > 2000 }
        val rows = Gallery.metadata(context, large).toMap()
        assertEquals("2048 × 1536", rows["像素尺寸"])
        assertNotEquals("未记录", rows["拍摄时间"])
        runBlocking {
            listOf(large, long).forEach { p ->
                val bitmap = PhotoBitmaps.load(context, p.uri, large = true)
                assertNotNull(bitmap)
                assertTrue(maxOf(bitmap!!.width, bitmap.height) <= 2048)
            }
        }
    }

    @Test fun noTextModeHidesFooterByDefaultAndPersistsChoice() {
        seed(profile = true)
        launch()
        fun showPhoto(): Photo {
            val photo = model.state.value.feed.first { it in fixturePhotos }
            ui.onNodeWithTag("home-feed").performScrollToIndex(model.state.value.feed.indexOf(photo))
            ui.waitUntil(15_000) {
                ui.onAllNodesWithTag("bitmap-${photo.uri}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            return photo
        }
        fun openSettings() {
            ui.onNodeWithContentDescription("个人账号").performClick()
            ui.onNodeWithContentDescription("设置").performClick()
        }
        fun toggleNoText(expectedBefore: Boolean) {
            val row = ui.onNodeWithTag("no-text-mode-switch").performScrollTo()
            if (expectedBefore) row.assertIsOn() else row.assertIsOff()
            row.performClick()
            ui.waitUntil(5_000) { model.settings.state.value.noTextMode != expectedBefore }
        }
        fun closeAndRelaunch() { scenario!!.close(); scenario = null; launch() }

        assertTrue("无字模式默认开启", model.settings.state.value.noTextMode)
        val photo = showPhoto()
        ui.onNodeWithText(photo.name.substringBeforeLast('.')).assertDoesNotExist()
        ui.onNodeWithContentDescription("照片菜单：${photo.name}").assertDoesNotExist()
        screenshot("no-text-home")

        openSettings()
        toggleNoText(expectedBefore = true)
        ui.onNodeWithContentDescription("返回个人主页").performClick()

        closeAndRelaunch()
        assertFalse("关闭无字模式后重启仍保留", model.settings.state.value.noTextMode)
        val visible = showPhoto()
        ui.onNodeWithText(visible.name.substringBeforeLast('.')).assertExists()
        ui.onNodeWithContentDescription("照片菜单：${visible.name}").assertExists()
        screenshot("text-home")

        openSettings()
        toggleNoText(expectedBefore = false)
        ui.onNodeWithContentDescription("返回个人主页").performClick()

        closeAndRelaunch()
        assertTrue("重新开启无字模式后重启仍保留", model.settings.state.value.noTextMode)
        val hidden = showPhoto()
        ui.onNodeWithText(hidden.name.substringBeforeLast('.')).assertDoesNotExist()
        ui.onNodeWithContentDescription("照片菜单：${hidden.name}").assertDoesNotExist()
        screenshot("no-text-home-restored")
    }

}
