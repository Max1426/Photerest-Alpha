package com.photerest.app

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Controlled rendering fixture. No Gallery scan, preference writes or Room database changes:
 * safe on a user's device as well as an emulator. Uses the production video detail and motion. */
@RunWith(AndroidJUnit4::class)
class VideoBackdropDeviceTest {
    @get:Rule val ui = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var motion: PhotoMotion
    private lateinit var playback: VideoPlayback
    private lateinit var photo: Photo
    private lateinit var key: PhotoMotionKey
    private lateinit var clip: File
    private lateinit var backdrop: Bitmap
    private var baseline: Bitmap? = null
    private var caseName = ""

    @After fun cleanup() {
        baseline?.recycle()
        if (::clip.isInitialized) clip.delete()
        // The composition owns the player; onDispose releases it before test host teardown.
    }

    private fun prepare(dark: Boolean, portrait: Boolean, origin: PhotoListOrigin) {
        caseName = (if (dark) "dark" else "light") + (if (portrait) "-portrait" else "-landscape") + "-" + origin.toString().replace(Regex("[^A-Za-z0-9]"), "")
        clip = File(context.cacheDir, "backdrop-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open(if (portrait) "video-identity-red.mp4" else "video-identity-blue.mp4").use { input -> clip.outputStream().use { input.copyTo(it) } }
        photo = Photo(clip.toURI().toString(), "Backdrop-$caseName", "controlled", "video/mp4", if (portrait) 240 else 400,
            if (portrait) 400 else 200, clip.length(), 0, System.nanoTime(), if (portrait) 6000 else 8000)
        key = PhotoMotionKey(origin, photo.uri)
        backdrop = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(backdrop)
        val paint = Paint()
        for (row in 0 until 12) for (column in 0 until 6) {
            paint.color = if ((row + column) % 2 == 0) AndroidColor.rgb(20, 220, 45) else AndroidColor.rgb(70, 245, 90)
            canvas.drawRect(column * 60f, row * 60f, (column + 1) * 60f, (row + 1) * 60f, paint)
        }
        ui.setContent { Scene(dark) }
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("bitmap-${photo.uri}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        baseline = instrumentation.uiAutomation.takeScreenshot()
        ui.onNodeWithContentDescription("播放视频：${photo.name}").performClick()
        ui.waitUntil(15_000) { ::motion.isInitialized && motion.detail != null && motion.progress == 1f && motion.flight == null }
        ui.waitUntil(15_000) { ui.runOnIdle { playback.hasFrame(photo, key, false) } }
    }

    @Composable private fun Scene(dark: Boolean) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        DisposableEffect(ctx) {
            (ctx as ComponentActivity).enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT))
            onDispose { }
        }
        val controller = remember { PhotoMotion(scope) }
        val player = remember { VideoPlayback(ctx.applicationContext) }
        SideEffect { motion = controller; playback = player }
        DisposableEffect(player) { player.setForeground(true); onDispose { player.release() } }
        CompositionLocalProvider(LocalAppAppearance provides AppSettings(dark = dark, haptics = false, noTextMode = true),
            LocalPhotoMotion provides controller, LocalVideoPlayback provides player) {
            OperationTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .onGloballyPositioned { controller.browserViewport = it.boundsInRoot() }) {
                    // Same list visibility and full-screen dim layer as PhoterestApp.
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (controller.progress == 1f && controller.flight == null) 0f else 1f }) {
                        Image(backdrop.asImageBitmap(), "校准背景照片", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Box(Modifier.width(132.dp).align(Alignment.BottomStart).padding(start = 12.dp, bottom = 84.dp)) {
                            PhotoCard(photo, key, open = {
                                if (controller.detail == null) {
                                    val frame = player.captureFrame(photo, key)
                                    player.open(photo, key, true, startPaused = true)
                                    controller.open(photo, key, thumbnail = frame)
                                }
                            }, menu = {})
                        }
                    }
                    controller.detail?.let { scene ->
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = controller.progress }.background(MaterialTheme.colorScheme.background))
                        VideoDetailScreen(scene, player, controller, true, Access.FULL, false,
                            back = { scene.bitmap = player.captureFrame(photo, key) ?: scene.bitmap; controller.close(scene, true) },
                            share = {}, collect = {}, openOriginal = {}, authorize = {})
                        LaunchedEffect(scene, scene.viewport != null) { if (scene.viewport != null) controller.enter(scene, true) }
                        LaunchedEffect(scene, controller.progress, controller.flight) {
                            if (controller.progress == 1f && controller.flight == null) player.setDetailRevealed(true)
                        }
                        controller.flight?.let { PhotoFlightOverlay(it, { controller.progress }, { Offset.Zero }) }
                    }
                }
            }
        }
    }

    private fun evidence(name: String, bitmap: Bitmap) {
        val folder = File(context.getExternalFilesDir(null), "backdrop-evidence").apply { mkdirs() }
        File(folder, "$caseName-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertBackground(name: String, shouldReveal: Boolean) {
        ui.waitForIdle()
        Thread.sleep(100)
        val scene = motion.detail ?: error("Expected detail during sampling")
        val viewport = scene.viewport!!
        val flight = motion.flight
        val image = flight?.let { f ->
            val p = motion.progress
            fun mix(a: Float, b: Float) = a + (b - a) * p
            Rect(mix(f.card.image.left, f.detail.image.left), mix(f.card.image.top, f.detail.image.top),
                mix(f.card.image.right, f.detail.image.right), mix(f.card.image.bottom, f.detail.image.bottom))
        }
        val frame = instrumentation.uiAutomation.takeScreenshot()
        try {
            evidence(name, frame)
            val before = baseline!!
            var count = 0; var revealed = 0
            for (y in viewport.top.toInt() + 24 until viewport.bottom.toInt() - 24 step 24) {
                for (x in viewport.left.toInt() + 24 until viewport.right.toInt() - 24 step 24) {
                    if (shouldReveal && image?.contains(Offset(x.toFloat(), y.toFloat())) == true) continue
                    val original = before.getPixel(x, y)
                    if (AndroidColor.green(original) < maxOf(AndroidColor.red(original), AndroidColor.blue(original)) + 70) continue
                    val c = frame.getPixel(x, y)
                    if (AndroidColor.green(c) > maxOf(AndroidColor.red(c), AndroidColor.blue(c)) + 12) revealed++
                    count++
                }
            }
            println("BackdropCheck $caseName $name progress=${motion.progress} green=$revealed/$count")
            assertTrue("Need real background samples outside moving video", count >= 100)
            if (shouldReveal) assertTrue("Background photo must show through the old video viewport; opaque rectangle detected ($revealed/$count)", revealed > count * .85)
            else assertTrue("Completed/cancelled detail must remain opaque", revealed < count * .02)
        } finally { frame.recycle() }
    }

    private fun checkCase(dark: Boolean, portrait: Boolean, origin: PhotoListOrigin) {
        prepare(dark, portrait, origin)
        val scene = motion.detail!!
        val sourceBounds = ui.onNodeWithContentDescription("播放视频：${photo.name}").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle { scene.bitmap = playback.captureFrame(photo, key) ?: scene.bitmap }
        for (fraction in listOf(.25f, .5f, .75f)) {
            ui.runOnIdle { motion.seekBack(scene, fraction, true) }
            assertBackground("return-${(fraction * 100).toInt()}", true)
        }
        ui.runOnIdle { motion.cancelBack(scene) }
        ui.waitUntil(10_000) { motion.progress == 1f && motion.flight == null }
        assertBackground("cancel-restored", false)
        ui.runOnIdle { motion.seekBack(scene, .6f, true); motion.close(scene, true) }
        ui.waitUntil(10_000) { motion.detail == null }
        assertEquals(sourceBounds, ui.onNodeWithContentDescription("播放视频：${photo.name}").fetchSemanticsNode().boundsInRoot)
        // Normal back also uses the same layer visibility; sample using actual progress, not time.
        ui.onNodeWithContentDescription("播放视频：${photo.name}").performClick()
        ui.waitUntil(10_000) { motion.progress == 1f && motion.flight == null }
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithContentDescription("返回视频流").performClick()
            ui.waitUntil(5_000) { ui.mainClock.advanceTimeByFrame(); motion.progress < .9f && motion.progress > .1f }
            assertBackground("button-return", true)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitUntil(10_000) { motion.detail == null }
    }

    @Test fun darkLandscapeAllPhotos() = checkCase(true, false, PhotoListOrigin.Album(null))
    @Test fun lightLandscapeAlbum() = checkCase(false, false, PhotoListOrigin.Album("fixture"))
    @Test fun darkPortraitFavorites() = checkCase(true, true, PhotoListOrigin.Favorites(null))
    @Test fun lightPortraitFolder() = checkCase(false, true, PhotoListOrigin.Favorites(1))
}
