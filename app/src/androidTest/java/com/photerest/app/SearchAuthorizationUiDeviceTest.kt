package com.photerest.app

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*

/** Generated authorization UI only; emulator evidence, no real search. */
class SearchAuthorizationUiDeviceTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun lockedNoticeMatchesAndDismisses() {
        var shown by mutableStateOf(true)
        ui.setContent { MaterialTheme { if (shown) SearchUnavailableDialog { shown = false } } }
        ui.onNodeWithText("联网搜索功能暂时不可用。").assertIsDisplayed()
        ui.onNodeWithText("知道了").performClick()
        ui.onNodeWithText("联网搜索功能暂时不可用。").assertDoesNotExist()
    }
    @Test fun settingsDark() = settings(true)
    @Test fun settingsLight() = settings(false)
    private fun settings(dark: Boolean) {
        SearchAuthorization.initialize(context)
        ui.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalAppAppearance provides AppSettings(dark = dark)) {
                    OnlineSearchSettings(SettingsStore(context)) { }
                }
            }
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("导出授权申请").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("导出授权申请").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("导入授权文件").assertExists()
        val folder = File(context.getExternalFilesDir(null), "search-auth-test").apply { mkdirs() }
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500) // Allow the system dialog surface to reach the compositor.
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder, if (dark) "settings-dark.png" else "settings-light.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screen.recycle()
    }
}
