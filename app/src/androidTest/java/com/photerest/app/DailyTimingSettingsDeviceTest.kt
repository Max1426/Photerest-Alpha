package com.photerest.app

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The permission is revoked externally because Android kills the app when it is revoked. */
@PermissionScenario
class DailyTimingSettingsDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun permissionEntryOpensSystemSettings() {
        assertTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        Thread.sleep(750)
        assertFalse(DailyRecommendations.get(context).exactAlarmsAllowed())
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("个人账号").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("个人账号").performClick()
            ui.onNodeWithContentDescription("设置").performClick()
            ui.onNodeWithText("每日精选").performClick()
            ui.onNodeWithTag("daily-exact-access").performScrollTo().assertIsDisplayed()
            val output = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            fun capture(name: String) {
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(output, name).outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                    bitmap.recycle()
                }
            }
            capture("daily-exact-access-explanation.png")
            ui.onNodeWithTag("daily-exact-access").performClick()
            val deadline = System.currentTimeMillis() + 10_000
            while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.settings" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("com.android.settings", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            Thread.sleep(750)
            capture("daily-exact-access-system-page.png")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}
