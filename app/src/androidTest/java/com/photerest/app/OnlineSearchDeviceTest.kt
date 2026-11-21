package com.photerest.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Uses only generated local pictures and fake search/thumbnail callbacks, never the live API. */
@RunWith(AndroidJUnit4::class)
class OnlineSearchDeviceTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shown = mutableStateOf(true)
    private val calls = AtomicInteger()
    private var response = CompletableDeferred<List<SearchResult>>()
    private lateinit var file: File
    private lateinit var photo: Photo
    private lateinit var bitmap: Bitmap

    @Before fun prepare() {
        bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        file = File(context.cacheDir, "online-regression-${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        photo = Photo(file.toURI().toString(), "test", "test", "image/jpeg", 120, 160, file.length(), 0, System.nanoTime())
        ui.setContent {
            if (shown.value) OnlineSearchSheet(photo,
                searchRequest = { _, _ -> calls.incrementAndGet(); response.await() },
                thumbnailRequest = { bitmap }, dismiss = { shown.value = false })
        }
        waitText("允许搜图（消耗 1 次）")
        expand()
    }

    @After fun cleanup() { response.complete(emptyList()); file.delete() }

    private fun waitText(value: String) {
        ui.waitUntil(15_000) { ui.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitResults() {
        waitText("已保存的搜索结果")
        ui.onNodeWithText("正在搜图").assertDoesNotExist()
    }
    private fun headerTop() = ui.onNodeWithTag("online-search-header").fetchSemanticsNode().boundsInRoot.top
    private fun expand() {
        val top = headerTop()
        ui.onNodeWithTag("online-search-header").performTouchInput {
            swipe(center, Offset(center.x, center.y + 160f - top), 600)
        }
        ui.waitForIdle()
    }
    private fun approve() { ui.onNodeWithText("允许搜图（消耗 1 次）").performClick(); waitText("正在搜图") }
    private fun items() = List(24) { SearchResult("https://example.invalid/$it", "https://example.invalid/thumb/$it", "https://example.invalid/page/$it", "模拟结果 $it") }

    @Test fun firstSearchShowsImagesImmediatelyAndReopensFree() {
        approve()
        response.complete(items())
        waitResults()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("online-result-image:0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("online-result-image:0", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, calls.get())
        ui.onNodeWithText("关闭").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("online-search-sheet").fetchSemanticsNodes().isEmpty() }
        ui.runOnIdle { shown.value = true }
        waitResults()
        assertEquals(1, calls.get())
    }

    @Test fun stableAnchorsAcrossLoadingResultsAndNestedScroll() {
        val expandedTop = headerTop()
        repeat(3) {
            ui.onNodeWithTag("online-search-header").performTouchInput { swipe(center, Offset(center.x, center.y - 70f), 450) }
            ui.waitForIdle()
            assertEquals("Expanded anchor must not drift", expandedTop, headerTop(), 4f)
        }
        approve()
        repeat(3) {
            ui.onNodeWithTag("online-search-header").performTouchInput { swipe(center, Offset(center.x, center.y - 70f), 450) }
            ui.waitForIdle()
            assertEquals(expandedTop, headerTop(), 4f)
        }
        // Publish while a pointer is still dragging, then settle with the new content.
        ui.onNodeWithTag("online-search-header").performTouchInput { down(center); moveBy(Offset(0f, 30f)) }
        response.complete(items())
        waitResults()
        ui.onNodeWithTag("online-search-header").performTouchInput { up() }
        ui.waitForIdle()
        assertEquals(expandedTop, headerTop(), 4f)
        ui.onNodeWithTag("online-search-grid").performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals("Grid scroll must not move expanded header", expandedTop, headerTop(), 4f)
        ui.onNodeWithTag("online-search-grid").performScrollToIndex(0)
        ui.onNodeWithTag("online-search-grid").performTouchInput {
            swipe(Offset(center.x, 50f), Offset(center.x, 700f), 1_200)
        }
        ui.waitForIdle()
        assertTrue("Down from list top should collapse sheet", headerTop() > expandedTop + 80f)
        ui.onNodeWithTag("online-search-header").performTouchInput {
            swipe(center, Offset(center.x, center.y + 650f), 1_200)
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("online-search-sheet").fetchSemanticsNodes().isEmpty() }
        assertFalse(shown.value)
        assertEquals(1, calls.get())
    }

    @Test fun failureAndEmptyResultLeaveLoadingWithoutAutomaticRetry() {
        approve()
        response.completeExceptionally(IOException("fake offline"))
        waitText("无法连接识图服务，请检查网络")
        ui.onNodeWithText("正在搜图").assertDoesNotExist()
        assertEquals(1, calls.get())
        response = CompletableDeferred()
        approve()
        response.complete(emptyList())
        waitResults()
        ui.onNodeWithText("没有找到相关图片。你可以稍后重新搜图。").assertExists()
        assertEquals(2, calls.get())
    }

    @Test fun closingPendingSearchStillSavesAndReopensFree() {
        approve()
        ui.onNodeWithText("关闭").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("online-search-sheet").fetchSemanticsNodes().isEmpty() }
        response.complete(items())
        val identity = runBlocking { SearchHistoryRepository.identify(context, photo, SearchEngine.GOOGLE) }
        ui.waitUntil(10_000) { runBlocking { SearchHistoryRepository.read(context, identity) } != null }
        ui.runOnIdle { shown.value = true }
        waitResults()
        assertEquals(1, calls.get())
    }
}
