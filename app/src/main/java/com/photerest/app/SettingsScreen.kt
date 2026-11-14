package com.photerest.app

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(store: SettingsStore, dismiss: () -> Unit, daily: () -> Unit, videoAccess: Access = Access.NONE, authorizeVideos: () -> Unit = {}) {
    val settings by store.state.collectAsState()
    val feedback = LocalTapFeedback.current
    val scroll = rememberScrollState()
    var chooseStyle by rememberSaveable { mutableStateOf(false) }
    val dynamicSupported = Build.VERSION.SDK_INT >= 31
    OperationScreen("设置", "返回个人主页", dismiss) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            OperationGroup("外观") {
                OperationItem("显示模式")
                Column(Modifier.selectableGroup()) {
                    listOf(true to "深色", false to "浅色").forEach { (dark, name) ->
                        OperationItem(
                            name,
                            Modifier.selectable(selected = settings.dark == dark, role = Role.RadioButton) {
                                feedback.perform()
                                store.setDark(dark)
                            },
                            trailing = { RadioButton(settings.dark == dark, onClick = null) }
                        )
                    }
                }
                OperationItem(
                    "界面风格", Modifier.tapClickable { chooseStyle = true },
                    summary = settings.operationStyle.label, trailing = { Text("›") }
                )
                val useDynamic = settings.dynamicColor && dynamicSupported
                OperationItem(
                    "跟随系统配色",
                    Modifier.testTag("dynamic-color-switch").toggleable(useDynamic, enabled = dynamicSupported, role = Role.Switch) {
                        feedback.perform()
                        store.setDynamicColor(it)
                    },
                    summary = if (dynamicSupported) "使用系统动态色" else "系统动态色需要 Android 12 或更新版本",
                    trailing = { Switch(useDynamic, onCheckedChange = null, enabled = dynamicSupported) }
                )
                OperationItem(
                    "无字模式",
                    Modifier.testTag("no-text-mode-switch").toggleable(settings.noTextMode, role = Role.Switch) {
                        feedback.perform()
                        store.setNoTextMode(it)
                    },
                    summary = if (settings.noTextMode) "隐藏照片下方的文件名和菜单，只显示照片。" else "照片下方显示文件名和“···”菜单。",
                    trailing = { Switch(settings.noTextMode, onCheckedChange = null) }
                )
            }
            OperationGroup("偏好") {
                OperationItem(
                    "震动反馈", Modifier.testTag("haptics-switch").toggleable(settings.haptics, role = Role.Switch) {
                        store.setHaptics(it)
                        feedback.perform()
                    },
                    trailing = { Switch(settings.haptics, onCheckedChange = null) }
                )
                OperationItem(
                    "自动静音预览视频",
                    Modifier.testTag("video-autoplay-switch").toggleable(settings.autoplayVideos, role = Role.Switch) {
                        store.setAutoplayVideos(it)
                        feedback.perform()
                    },
                    summary = "列表只自动预览一条视频，静音循环；滚出可见区、切换页面或应用进入后台时停止。",
                    trailing = { Switch(settings.autoplayVideos, onCheckedChange = null) }
                )
                OperationItem(
                    "在瀑布流中显示视频",
                    Modifier.testTag("show-videos-switch").toggleable(settings.showVideos, role = Role.Switch) {
                        store.setShowVideos(it)
                        feedback.perform()
                    },
                    summary = if (settings.showVideos) "关闭后首页、相册和收藏的瀑布流只显示照片；视频与收藏关系仍保留。" else "瀑布流当前只显示照片，打开后重新显示视频。",
                    trailing = { Switch(settings.showVideos, onCheckedChange = null) }
                )
                if (videoAccess != Access.FULL) {
                    OperationItem(
                        "视频访问权限",
                        summary = when (videoAccess) {
                            Access.NONE -> "尚未允许读取视频。已授权的照片和收藏不受影响。"
                            Access.PARTIAL -> "当前只显示已选择的视频。"
                            Access.FULL -> ""
                        },
                        trailing = { TapTextButton(onClick = authorizeVideos) { Text("授权视频访问") } }
                    )
                }
                OperationItem(
                    "每日精选", Modifier.tapClickable(onClick = daily),
                    summary = "定时与你重逢，每次一张本机照片", trailing = { Text("›") }
                )
            }
        }
    }
    if (chooseStyle) {
        AlertDialog(
            onDismissRequest = { chooseStyle = false },
            title = { Text("界面风格") },
            text = {
                Column(Modifier.selectableGroup()) {
                    OperationStyle.entries.forEach { style ->
                        val selected = settings.operationStyle == style
                        OperationItem(
                            style.label,
                            Modifier.testTag("operation-style-${style.name}").selectable(selected, role = Role.RadioButton) {
                                chooseStyle = false
                                feedback.perform()
                                store.setOperationStyle(style)
                            },
                            trailing = { RadioButton(selected, onClick = null) }
                        )
                    }
                }
            },
            confirmButton = { TapTextButton(onClick = { chooseStyle = false }) { Text("关闭") } }
        )
    }
}
