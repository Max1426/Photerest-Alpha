package com.photerest.app

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySettingsScreen(model: PhotoModel, dismiss: () -> Unit, authorizePhotos: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DailyRecommendations.get(context) }
    val config by repository.state.collectAsState()
    val status by repository.status.collectAsState()
    val planned by repository.planned.collectAsState()
    val photos by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = LocalTapFeedback.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }
    var scheduleEditor by remember { mutableStateOf(false) }
    var savingSchedule by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        scope.launch { repository.reconcile() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRevision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val allowed = remember(permissionRevision, config.enabled) { repository.notificationsAllowed() }
    val exact = remember(permissionRevision) { repository.exactAlarmsAllowed() }
    val restricted = remember(permissionRevision) { repository.backgroundRestricted() }
    fun change(block: (DailyConfig) -> DailyConfig) { scope.launch { repository.update(block) } }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Gallery.granted(context, Manifest.permission.POST_NOTIFICATIONS)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else repository.openSystemSettings()
    }
    OperationScreen("每日精选", "返回设置", dismiss) {
        OperationPageBody {
            OperationGroup("提醒") {
                OperationItem(
                    "开启每日精选",
                    Modifier.testTag("daily-enabled").toggleable(config.enabled, role = Role.Switch) { enabled ->
                        feedback.perform()
                        change { it.copy(enabled = enabled) }
                        if (enabled && !allowed) requestNotifications()
                    },
                    summary = "让照片每天与你重逢",
                    trailing = { Switch(config.enabled, onCheckedChange = null) }
                )
                if (!allowed) {
                    OperationItem(
                        "系统通知未开启",
                        summary = "推荐发送已暂停。",
                        trailing = { TapTextButton(onClick = ::requestNotifications) { Text("允许通知") } }
                    )
                }
                if (Build.VERSION.SDK_INT >= 31 && !exact) {
                    OperationItem("准时提醒尚未授权", summary = "系统可能推迟发送。允许后，熄屏和返回桌面时也会按所选时间唤醒。")
                    TapTextButton(onClick = { repository.openTimingSettings() }, modifier = Modifier.padding(start = 8.dp).testTag("daily-exact-access")) {
                        Text("允许准时提醒")
                    }
                } else OperationItem("已允许准时提醒", summary = "开启每日精选后可在后台和熄屏时发送。")
                if (restricted) {
                    OperationItem("后台运行受限", summary = "请在应用电池设置中解除限制。")
                    TapTextButton(onClick = { repository.openBackgroundSettings() }, modifier = Modifier.padding(start = 8.dp)) { Text("管理后台运行") }
                }
                if (photos.access == Access.NONE) {
                    OperationItem("需要照片访问权限", summary = "授权后才能推荐。")
                    TapTextButton(onClick = authorizePhotos, modifier = Modifier.padding(start = 8.dp)) { Text("管理照片权限") }
                } else if (photos.access == Access.PARTIAL) OperationItem("仅推荐已授权的照片")
            }
            OperationGroup("推荐范围") {
                Column(Modifier.selectableGroup()) {
                    DailySource.entries.forEach { source ->
                        OperationItem(
                            source.label,
                            Modifier.selectable(config.source == source, role = Role.RadioButton) {
                                feedback.perform()
                                change { it.copy(source = source) }
                            },
                            trailing = { RadioButton(config.source == source, onClick = null) }
                        )
                    }
                }
                if (config.source == DailySource.ALBUMS) {
                    val names = photos.deviceAlbums.filter { it.id in config.albums }.map { it.name }
                    OperationItem(
                        "选择推荐相册", Modifier.tapClickable { albumPicker = true },
                        summary = if (config.albums.isEmpty()) "尚未选择相册，不会发送推荐" else "已选 ${config.albums.size} 个相册：${names.joinToString("、").ifEmpty { "当前不可访问" }}",
                        trailing = { Text("›") }
                    )
                }
                OperationItem("推荐顺序", summary = "随机挑选，优先推荐本轮尚未推荐的照片。全部推荐过后开始新一轮。")
            }
            OperationGroup("推荐安排") {
                OperationItem(
                    config.mode.label,
                    summary = if (config.mode == DailyMode.INTERVAL) "每隔 ${config.intervalMinutes} 分钟推荐一张" else "每天设置 ${config.times.size} 个时点，每个时间一张"
                )
                if (config.mode == DailyMode.FIXED) OperationItem(config.times.joinToString("、", transform = DailyRules::timeLabel))
                val days = config.week.indices.filter { config.week[it].enabled }.joinToString("、") { weekLabels[it] }
                OperationItem(
                    "编辑推荐安排", Modifier.testTag("daily-edit-schedule").tapClickable { scheduleEditor = true },
                    summary = if (days.isEmpty()) "所有星期均已停用" else "启用日期：$days", trailing = { Text("›") }
                )
                if (config.enabled && allowed) {
                    OperationItem(
                        planned?.let { "下一次计划：" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it) }
                            ?: "当前安排已暂停推荐",
                        Modifier.testTag("daily-next")
                    )
                }
                OperationItem(
                    "后台提醒",
                    summary = "按手机本地时间唤醒，无需保持应用打开。系统强行停止或厂商冻结后台会阻止提醒。请允许 Photerest 自启动和后台运行，vivo 系统中可允许后台高耗电。密集时点可能被系统延后，建议至少间隔 15 分钟。"
                )
            }
            OperationGroup("通知与隐私") {
                OperationItem(
                    "锁屏显示照片",
                    Modifier.testTag("daily-preview").toggleable(config.lockscreenPreview, role = Role.Switch) {
                        feedback.perform()
                        change { old -> old.copy(lockscreenPreview = it) }
                    },
                    summary = "关闭后隐藏锁屏版中的照片和文件名。实际显示由系统锁屏隐私设置控制。更改后会清除已有精选通知。",
                    trailing = { Switch(config.lockscreenPreview, onCheckedChange = null) }
                )
                OperationItem("系统通知设置", Modifier.tapClickable { repository.openSystemSettings() },
                    summary = "通知声音与震动由系统管理，与应用内点按震动分别设置。", trailing = { Text("›") })
                TapButton(onClick = {
                    if (!allowed) { requestNotifications(); return@TapButton }
                    sending = true
                    scope.launch {
                        try { Toast.makeText(context, repository.testNotification(), Toast.LENGTH_LONG).show() }
                        catch (_: Exception) { Toast.makeText(context, "未能发送，请检查照片和通知权限", Toast.LENGTH_LONG).show() }
                        finally { sending = false }
                    }
                }, enabled = !sending, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(if (sending) "正在准备照片…" else "发送测试通知")
                }
                OperationItem(status, summary = "测试不会消耗推荐轮次，也不会打开每日定时开关。")
            }
        }
    }
    if (scheduleEditor) {
        DailyScheduleEditor(config, savingSchedule, dismiss = { scheduleEditor = false }) { draft ->
            savingSchedule = true
            scope.launch {
                try {
                    repository.update { it.copy(mode = draft.mode, intervalMinutes = draft.intervalMinutes, times = draft.times, week = draft.week) }
                    scheduleEditor = false
                } catch (_: Exception) { Toast.makeText(context, "安排未能保存，请重试", Toast.LENGTH_LONG).show() }
                finally { savingSchedule = false }
            }
        }
    }
    if (albumPicker) {
        var selected by remember { mutableStateOf(config.albums) }
        AlertDialog(onDismissRequest = { albumPicker = false }, title = { Text("选择推荐相册") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (photos.deviceAlbums.isEmpty()) Text("暂无可访问相册，请检查照片权限。")
                photos.deviceAlbums.forEach { album ->
                    OperationItem(
                        "${album.name}（${album.count}）",
                        Modifier.toggleable(album.id in selected, role = Role.Checkbox) { checked ->
                            feedback.perform()
                            selected = if (checked) selected + album.id else selected - album.id
                        },
                        trailing = { Checkbox(album.id in selected, onCheckedChange = null) }
                    )
                }
            }
        }, confirmButton = { TapTextButton(onClick = { change { it.copy(albums = selected) }; albumPicker = false }) { Text("保存") } }, dismissButton = { TapTextButton(onClick = { albumPicker = false }) { Text("取消") } })
    }
}
