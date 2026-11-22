package com.photerest.app

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Dedicated emulator only. Only selected appearance keys are changed, no model or gallery operation. */
class FiDeviceTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    private val keys = listOf("fi_enabled","fi_google_enabled","fi_setup_seen","online_search_blur_dp","online_search_visible")
    private lateinit var before: Map<String,Any?>
    @Before fun prepare() {
        before = keys.associateWith { prefs.all[it] }
        prefs.edit().remove("fi_enabled").remove("fi_google_enabled").remove("fi_setup_seen").commit()
        SearchAuthorization.initialize(context); FiAccess.initialize(context)
    }
    @After fun restore() {
        val editor=prefs.edit()
        before.forEach { (key,value) -> when(value) {
            is Boolean -> editor.putBoolean(key,value)
            is Int -> editor.putInt(key,value)
            else -> editor.remove(key)
        } }
        editor.commit()
    }
    private fun screenshot(name: String) {
        ui.mainClock.advanceTimeBy(1000); ui.waitForIdle()
        android.os.SystemClock.sleep(400)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(context.getExternalFilesDir(null),"fi-evidence").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun upgradeDefaultsOffAndSwitchesPreserveExistingPreferences() {
        prefs.edit().putInt("online_search_blur_dp",23).putBoolean("online_search_visible",false).commit()
        val store=SettingsStore(context)
        assertFalse(store.state.value.fiEnabled)
        assertEquals(23,store.state.value.onlineSearchBlurDp)
        store.setFiEnabled(true);store.setGoogleExtensionEnabled(false);store.setFiEnabled(false)
        val recreated=SettingsStore(context).state.value
        assertFalse(recreated.fiEnabled);assertFalse(recreated.googleExtensionEnabled)
        assertEquals(23,recreated.onlineSearchBlurDp);assertFalse(recreated.showOnlineSearch)
    }
    @Test fun requestBoundaryRejectsOffAndDisabledExtensionBeforeNetworking() = runBlocking {
        val store=SettingsStore(context)
        try { ImageSearchClient.quotas(); fail("Off state reached the network") }
        catch (error: FiUnavailable) { assertTrue(error.message!!.contains("尚未开启")) }
        store.setFiEnabled(true);store.setGoogleExtensionEnabled(false)
        try { ImageSearchClient.quotas(); fail("Disabled extension reached the network") }
        catch (error: FiUnavailable) { assertEquals("Google 扩展尚未开启",error.message) }
    }
    @Test fun everyEnableInvokesGuideButDisableDoesNot() {
        val store=SettingsStore(context);var guides=0
        ui.setContent { FiSettingsScreen(store,{}, {}, {}, { guides++ }) }
        ui.onNodeWithTag("fi-master-switch").performClick();ui.runOnIdle { assertEquals(1,guides) }
        ui.onNodeWithTag("fi-master-switch").performClick();ui.runOnIdle { assertEquals(1,guides) }
        ui.onNodeWithTag("fi-master-switch").performClick();ui.runOnIdle { assertEquals(2,guides) }
        assertTrue(SettingsStore(context).state.value.fiEnabled)
        screenshot("fi-settings-dark")
    }
    @Test fun disabledDrawerRoutesToSettingsWithoutEnabling() {
        val store=SettingsStore(context);var settings=false;val shown=mutableStateOf(true)
        ui.setContent { if(shown.value) FiDisabledSheet({ shown.value=false }) { settings=true;shown.value=false } }
        ui.onNodeWithText("φ 智能尚未开启").assertExists()
        screenshot("fi-disabled")
        ui.onNodeWithTag("fi-go-settings").performClick()
        ui.runOnIdle { assertTrue(settings);assertFalse(store.state.value.fiEnabled) }
    }
    @Test fun guideKeepsAnchorAcrossPagesAndSupportsDragDismiss() {
        val shown=mutableStateOf(true)
        ui.setContent { if(shown.value) FiGuideSheet({ shown.value=false }, {}, {}) }
        ui.waitForIdle()
        val top=ui.onNodeWithTag("fi-guide-heading").fetchSemanticsNode().boundsInRoot.top
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val height = screen.height; screen.recycle()
        assertTrue("Guide must open half-screen: top=$top height=$height",top > height*.35f && top < height*.65f)
        screenshot("fi-guide-1")
        ui.onNodeWithTag("fi-guide-next").performClick()
        ui.onNodeWithText("用文字寻找照片").assertExists()
        val next=ui.onNodeWithTag("fi-guide-heading").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Guide anchor moved",kotlin.math.abs(top-next)<3f)
        screenshot("fi-guide-2")
        ui.onNodeWithTag("fi-guide-next").performClick()
        ui.onNodeWithText("Google 联网搜图 · Beta").assertExists()
        screenshot("fi-guide-3")
        ui.onNodeWithTag("fi-guide-sheet").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(center.x,height*.64f),androidx.compose.ui.geometry.Offset(center.x,height*.98f),450)
        }
        ui.waitUntil(5000) { !shown.value }
    }
    @Test fun unauthorizedGoogleExtensionIsGreyWithReason() {
        val store=SettingsStore(context);store.setFiEnabled(true)
        ui.setContent { OnlineSearchSettings(store) {} }
        ui.onNodeWithTag("fi-google-switch").assertIsNotEnabled()
        ui.onAllNodesWithText("联网搜图仍在测试，仅向已授权设备开放。").assertCountEquals(2)
        screenshot("fi-google-locked")
    }
    @Test fun localManagementKeepsCleanupBehindConfirmation() {
        val store=SettingsStore(context)
        val model=LocalSearchViewModel(context.applicationContext as android.app.Application)
        ui.setContent { FiLocalSettingsScreen(store,model) {} }
        ui.onNodeWithTag("fi-clear-index").performScrollTo().performClick()
        ui.onNodeWithText("清理 Alpha 索引？").assertIsDisplayed()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("清理 Alpha 索引？").assertDoesNotExist()
        screenshot("fi-local-management")
    }
    @Test fun settingsLightAndGuideUseExpressiveColors() {
        val store=SettingsStore(context)
        ui.setContent {
            CompositionLocalProvider(LocalAppAppearance provides AppSettings(dark=false)) {
                FiSettingsScreen(store,{}, {}, {}, {})
            }
        }
        ui.onAllNodesWithText("φ 智能").assertCountEquals(2)
        screenshot("fi-settings-light")
    }
}
