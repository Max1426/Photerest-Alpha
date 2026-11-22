package com.photerest.app

import android.animation.ValueAnimator
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Isolated emulator host: no gallery, model, preferences or network requests. */
class FiSheetDeviceTest {
    @get:Rule val ui = createComposeRule()

    @Test fun tenCyclesKeepNavigationInPlaceAndNeverCreateAWindow() {
        val shown=mutableStateOf(false)
        ui.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(80.dp)
                    .testTag("underlying-nav").background(MaterialTheme.colorScheme.surface)) {
                    Text("搜索",Modifier.align(Alignment.Center).clickable { shown.value=true }.testTag("trigger"))
                }
                if(shown.value) FiDisabledSheet({ shown.value=false },{})
            }
        }
        val before=ui.onNodeWithTag("underlying-nav").fetchSemanticsNode().boundsInRoot
        repeat(10) {
            ui.onNodeWithTag("trigger").performClick()
            ui.onNodeWithTag("fi-disabled-sheet").assertExists()
            ui.onAllNodes(isDialog()).assertCountEquals(0)
            assertEquals(before,ui.onNodeWithTag("underlying-nav").fetchSemanticsNode().boundsInRoot)
            ui.onNodeWithText("暂不开启").performClick()
            ui.waitForIdle()
            ui.onNodeWithTag("fi-disabled-sheet").assertDoesNotExist()
            assertEquals(before,ui.onNodeWithTag("underlying-nav").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun entranceAndExitAreMonotonicWithFixedContentMeasurement() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        ui.mainClock.autoAdvance=false
        val shown=mutableStateOf(true)
        ui.setContent { Box(Modifier.fillMaxSize()) { if(shown.value) FiDisabledSheet({shown.value=false},{}) } }
        ui.mainClock.advanceTimeBy(16);ui.waitForIdle()
        fun snapshot()=ui.onNodeWithTag("fi-disabled-sheet").fetchSemanticsNode().config
        val initial=snapshot()[FiSheetViewport]
        var offset=snapshot()[FiSheetOffset]
        var movingFrames=0
        repeat(26) {
            ui.mainClock.advanceTimeBy(16);ui.waitForIdle()
            val c=snapshot();val next=c[FiSheetOffset]
            assertTrue("Entrance reversed",next<=offset+.1f)
            if (offset-next>.1f) movingFrames++
            assertEquals("Content remeasured during entrance",initial,c[FiSheetViewport],.1f)
            offset=next
        }
        assertTrue("Expected intermediate positions",movingFrames>5)
        ui.onNodeWithText("暂不开启").performClick()
        repeat(12) {
            ui.mainClock.advanceTimeBy(16);ui.waitForIdle()
            val c=snapshot();val next=c[FiSheetOffset]
            assertTrue("Exit reversed",next>=offset-.1f)
            assertEquals(initial,c[FiSheetViewport],.1f)
            offset=next
        }
        ui.mainClock.advanceTimeBy(400);ui.waitForIdle()
        ui.onNodeWithTag("fi-disabled-sheet").assertDoesNotExist()
    }

    @Test fun dragCanInterruptEntranceAndDismissFromTheBody() {
        ui.mainClock.autoAdvance=false
        val shown=mutableStateOf(true)
        ui.setContent { Box(Modifier.fillMaxSize()) { if(shown.value) FiDisabledSheet({shown.value=false},{}) } }
        ui.mainClock.advanceTimeBy(160);ui.waitForIdle()
        val sheet=ui.onNodeWithTag("fi-disabled-sheet")
        val height=sheet.fetchSemanticsNode().config[FiSheetOffset]*2f
        sheet.performTouchInput { swipe(Offset(center.x,90f),Offset(center.x,height*.65f),250) }
        ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
        assertFalse(shown.value)
    }

    @Test fun backdropAndBackUseAnimatedCloseAndOnlyOneCallback() {
        val shown=mutableStateOf(true);var closes=0
        ui.setContent { Box(Modifier.fillMaxSize()) { if(shown.value) FiDisabledSheet({closes++;shown.value=false},{}) } }
        ui.onNodeWithTag("fi-disabled-sheet-scrim").performTouchInput { click(Offset(10f,10f)) }
        ui.waitForIdle();assertFalse(shown.value);assertEquals(1,closes)
    }

    @Test fun settingsGuideStaysInItsExistingWindowAndRestoresPage() {
        val shown=mutableStateOf(true)
        ui.setContent {
            OperationScreen("测试设置","返回",{},overlayVisible=shown.value,overlay={
                if(shown.value) FiGuideSheet({shown.value=false},{},{})
            }) { Text("后方设置内容",Modifier.testTag("settings-body")) }
        }
        ui.onAllNodes(isDialog()).assertCountEquals(1)
        ui.onNodeWithTag("settings-body").assertDoesNotExist()
        ui.onNodeWithText("关闭").performClick()
        ui.onAllNodes(isDialog()).assertCountEquals(1)
        ui.onNodeWithTag("settings-body").assertIsDisplayed()
    }

    @Test fun systemBackCollapsesExpandedDrawerThenClosesIt() {
        val shown=mutableStateOf(true)
        var owner: androidx.activity.OnBackPressedDispatcherOwner?=null
        ui.setContent {
            owner=LocalOnBackPressedDispatcherOwner.current
            Box(Modifier.fillMaxSize()) { if(shown.value) FiGuideSheet({shown.value=false},{},{}) }
        }
        val half=ui.onNodeWithTag("fi-guide-sheet").fetchSemanticsNode().config[FiSheetOffset]
        ui.onNodeWithTag("fi-guide-sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand)
        ui.waitForIdle()
        assertTrue(ui.onNodeWithTag("fi-guide-sheet").fetchSemanticsNode().config[FiSheetOffset]<half)
        ui.runOnIdle { owner!!.onBackPressedDispatcher.onBackPressed() };ui.waitForIdle()
        assertTrue(shown.value)
        assertEquals(half,ui.onNodeWithTag("fi-guide-sheet").fetchSemanticsNode().config[FiSheetOffset],1f)
        ui.runOnIdle { owner!!.onBackPressedDispatcher.onBackPressed() };ui.waitForIdle()
        assertFalse(shown.value)
    }
}
