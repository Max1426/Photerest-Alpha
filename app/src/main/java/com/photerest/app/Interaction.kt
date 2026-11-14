package com.photerest.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val haptics: Boolean = true,
    val dark: Boolean = true,
    val operationStyle: OperationStyle = OperationStyle.STANDARD,
    val dynamicColor: Boolean = false,
    val noTextMode: Boolean = true
)
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppSettings(
        haptics = prefs.getBoolean("haptics", true),
        dark = prefs.getBoolean("dark", true),
        operationStyle = OperationStyle.entries.firstOrNull { it.name == prefs.getString("operation_style", null) }
            ?: OperationStyle.STANDARD,
        dynamicColor = prefs.getBoolean("dynamic_color", false),
        noTextMode = prefs.getBoolean("no_text_mode", true)
    ))
    val state = mutable.asStateFlow()
    fun setHaptics(value: Boolean) { prefs.edit().putBoolean("haptics", value).apply(); mutable.value = mutable.value.copy(haptics = value) }
    fun setDark(value: Boolean) { prefs.edit().putBoolean("dark", value).apply(); mutable.value = mutable.value.copy(dark = value) }
    fun setOperationStyle(value: OperationStyle) {
        prefs.edit().putString("operation_style", value.name).apply()
        mutable.value = mutable.value.copy(operationStyle = value)
    }
    fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean("dynamic_color", value).apply()
        mutable.value = mutable.value.copy(dynamicColor = value)
    }
    fun setNoTextMode(value: Boolean) {
        prefs.edit().putBoolean("no_text_mode", value).apply()
        mutable.value = mutable.value.copy(noTextMode = value)
    }
}

class TapFeedback(private val enabled: () -> Boolean, private val emit: () -> Unit) {
    fun perform() { if (enabled()) emit() }
}
val LocalTapFeedback = staticCompositionLocalOf { TapFeedback({ false }, {}) }
fun Modifier.tapClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val feedback = LocalTapFeedback.current
    clickable(enabled = enabled) { feedback.perform(); onClick() }
}
@Composable fun TapButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(), contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    val feedback = LocalTapFeedback.current
    Button(onClick = { feedback.perform(); onClick() }, modifier = modifier, enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)
}
@Composable fun TapTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val feedback = LocalTapFeedback.current
    TextButton(onClick = { feedback.perform(); onClick() }, modifier = modifier, enabled = enabled, content = content)
}
@Composable fun TapIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val feedback = LocalTapFeedback.current
    IconButton(onClick = { feedback.perform(); onClick() }, modifier = modifier, content = content)
}

class TabViewport {
    val grid = LazyStaggeredGridState()
}

@Composable fun DialogThemeBars() {
    val view = LocalView.current
    val appearance = LocalAppAppearance.current
    val background = if (appearance == null) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface
    val light = appearance?.let { !it.dark } ?: (background != Color.Black)
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
            @Suppress("DEPRECATION")
            window.navigationBarColor = background.toArgb()
        }
    }
}


