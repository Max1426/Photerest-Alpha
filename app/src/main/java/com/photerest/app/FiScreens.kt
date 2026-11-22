package com.photerest.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.photerest.ai.AiClient
import com.photerest.ai.ModelSpec
import kotlinx.coroutines.*

@Composable internal fun rememberFiForeground(): Boolean {
    val lifecycle = (LocalContext.current as? LifecycleOwner)?.lifecycle
    var visible by remember(lifecycle) { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> visible = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return visible
}

@Composable
internal fun FiSettingsScreen(store: SettingsStore, back: () -> Unit, local: () -> Unit, google: () -> Unit, guide: () -> Unit, guideOpen: Boolean = false, dismissGuide: () -> Unit = {}) {
    val settings by store.state.collectAsState()
    val authorization by SearchAuthorization.status.collectAsState()
    val context = LocalContext.current
    val feedback = LocalTapFeedback.current
    val masterFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf(masterFocus) }
    var guideWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(guideOpen) {
        if (guideWasOpen && !guideOpen) {
            withFrameNanos { }
            runCatching { returnTo.requestFocus() }
        }
        guideWasOpen = guideOpen
    }
    val onlineReason = fiOnlineReason(settings.fiEnabled,settings.googleExtensionEnabled,authorization == SearchAuthorizationStatus.READY)
    OnlineTheme {
        OperationScreen("φ 智能", "返回设置", back, canDismiss = !guideOpen,
            overlayVisible = guideOpen, overlay = {
                if (guideOpen) FiGuideSheet(dismissGuide,
                    local = local, google = google)
            }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OperationGroup("在你的照片里发现更多") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FiIcon(Modifier.size(80.dp), disabled = !settings.fiEnabled)
                        Column(Modifier.weight(1f)) {
                            Text("φ 智能", style = MaterialTheme.typography.headlineMedium)
                            Text("用文字寻找本机照片，或通过 Google 发现相关图片。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OperationItem("启用 φ 智能", Modifier.testTag("fi-master-switch").focusRequester(masterFocus).toggleable(settings.fiEnabled,role = Role.Switch) {
                        feedback.perform(); store.setFiEnabled(it)
                        if (it) { returnTo = masterFocus; store.markFiSetupSeen(); guide() }
                    }, summary = if (settings.fiEnabled) "各项能力依照组件和授权状态分别可用。" else "关闭后暂停搜索，模型、索引和历史仍保留。",
                        trailing = { Switch(settings.fiEnabled,onCheckedChange = null) })
                }
                OperationGroup("本地能力") {
                    OperationItem("本地 AI", Modifier.testTag("fi-local-entry").tapClickable(onClick = local),
                        summary = when {
                            !ModelSpec.supported() -> "此设备不支持本地 AI，需 Android 12+ ARM64。"
                            !ModelSpec.installed(context) -> "准备共享模型中心和本地模型"
                            else -> "管理模型中心、查看状态与清理索引"
                        }, trailing = { Text("›") })
                }
                OperationGroup("联网扩展") {
                    OperationItem("Google 联网搜图", Modifier.testTag("fi-google-entry").tapClickable(onClick = google),
                        summary = onlineReason ?: "已启用 · 每次上传前仍需确认",
                        leading = { BetaBadge() }, trailing = { Text("›") })
                    Text("联网搜图仍在测试。未获授权的设备不能上传照片或查询共享额度。", Modifier.padding(horizontal = 16.dp,vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
                OperationGroup("了解 φ 智能") {
                    OperationItem("使用指南", Modifier.testTag("fi-guide-entry").focusRequester(guideFocus).tapClickable { returnTo = guideFocus; guide() }, summary = "查看可重播的功能演示", trailing = { Text("›") })
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
internal fun FiLocalSettingsScreen(store: SettingsStore, search: LocalSearchViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val settings by store.state.collectAsState()
    val ui by search.state.collectAsState()
    val installer = remember(activity) { ViewModelProvider(activity)[FiComponentInstaller::class.java] }
    val install by installer.state.collectAsState()
    val foreground = rememberFiForeground()
    val supported = ModelSpec.supported()
    var refresh by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(ModelSpec.installed(context)) }
    var status by remember { mutableStateOf<Bundle?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var uninstall by remember { mutableStateOf(false) }
    var cleanupLegacy by remember { mutableStateOf(false) }
    LaunchedEffect(foreground,refresh,settings.fiEnabled) {
        if (!foreground) return@LaunchedEffect
        installed = ModelSpec.installed(context)
        status = null; statusError = null
        if (!settings.fiEnabled || !supported || !installed) return@LaunchedEffect
        val client = AiClient(context)
        try {
            while (isActive) {
                try { status = client.status(); statusError = null }
                catch (error: Exception) { if (error is CancellationException) throw error; statusError = if (error is SecurityException) "中心签名或权限不匹配，请安装配套组件。" else error.message ?: "中心连接失败" }
                delay(1500)
            }
        } finally { client.close() }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) installer.prepare(uri) }
    val installResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++; installer.message(if (ModelSpec.installed(context)) "共享中心已安装，可继续准备模型。" else "尚未完成安装，已保留准备好的安装包。")
    }
    fun launchInstall() {
        try { installResult.launch(installer.installIntent()) }
        catch (error: Exception) { installer.message(error.message ?: "无法打开系统安装页面") }
    }
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) launchInstall()
        else installer.message("尚未允许此来源安装应用。允许后可再次点击安装。")
    }
    val deleteResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++; search.pause()
    }
    OnlineTheme {
        OperationScreen("本地 AI", "返回 φ 智能", back) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OperationGroup("共享模型中心") {
                    OperationItem("Photerest AI Hub", summary = when {
                        !supported -> "此设备不支持本地 AI，需要 Android 12 及以上 ARM64。"
                        !installed -> "尚未安装共享组件"
                        !settings.fiEnabled -> "已安装 · 开启 φ 智能后可检查模型"
                        status?.getBoolean("modelReady") == true && status?.getString("sha256") != ModelSpec.SHA -> "中心模型不匹配，请使用配套模型。"
                        status?.getBoolean("modelReady") == true -> "模型已就绪 · 复用中心里的同一份模型"
                        statusError != null -> statusError!!
                        status == null -> "正在检查中心状态"
                        else -> "组件已安装，模型尚未准备好"
                    })
                    Text("Alpha、Search Lab 和其他客户端可复用中心。关闭 φ 智能不会卸载组件或删除模型。", Modifier.padding(horizontal = 16.dp,vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("本轮采用本机安装验证，尚未配置公开组件下载地址。", style = MaterialTheme.typography.bodyMedium)
                        TapButton(onClick = { pick.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream")) },
                            enabled = supported && !install.busy, modifier = Modifier.testTag("fi-pick-component")) {
                            Text(if (installed) "选择组件更新包" else "选择本地组件安装包")
                        }
                        if (install.busy) {
                            install.percent?.let { value ->
                                Text("正在准备本地安装包 · $value%")
                                LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth())
                            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        install.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (install.prepared) TapButton(onClick = {
                            if (context.packageManager.canRequestPackageInstalls()) launchInstall()
                            else try { allowInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))) }
                            catch (_: Exception) { installer.message("无法打开安装来源设置，请从系统设置允许此来源安装。") }
                        }, enabled = !install.busy, modifier = Modifier.testTag("fi-install-component")) { Text("请求系统安装") }
                        if (installed) TapTextButton(onClick = search::openCenter) { Text("进入中心下载或导入模型") }
                        status?.getString("progress")?.takeIf { it.isNotBlank() }?.let { progress ->
                            Text("中心最近进度：$progress", style = MaterialTheme.typography.bodyMedium)
                            val percent = Regex("(\\d{1,3})%").find(progress)?.groupValues?.get(1)?.toIntOrNull()
                            if (percent != null) LinearProgressIndicator(progress = { percent.coerceIn(0,100)/100f }, modifier = Modifier.fillMaxWidth())
                        }
                        Text("模型下载或导入请在中心前台完成，返回这里会刷新状态。已有模型不需要再下载。", style = MaterialTheme.typography.bodySmall)
                        if (ui.hasLegacyModel) {
                            TapTextButton(onClick = search::migrateModel, enabled = installed && settings.fiEnabled) { Text("迁移 Alpha 已有模型") }
                            TapTextButton(onClick = { cleanupLegacy = true }, enabled = settings.fiEnabled && ui.sharedVerified) { Text("清理已迁移的 Alpha 旧模型副本") }
                        }
                        ui.error?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
                    }
                }
                OperationGroup("索引与组件管理") {
                    OperationItem("清理 Alpha 索引", Modifier.testTag("fi-clear-index").tapClickable { clear = true }, summary = "只清理本应用向量，保留照片、收藏及共享模型")
                    OperationItem("卸载共享 AI 中心", Modifier.testTag("fi-uninstall-hub").tapClickable(enabled = installed) { uninstall = true },
                        summary = "会移除中心及其模型，影响其他使用中心的应用")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (cleanupLegacy) AlertDialog(onDismissRequest = { cleanupLegacy = false }, title = { Text("清理 Alpha 旧模型副本？") },
            text = { Text("仅移除已经迁移并验证过的 Alpha 私有旧副本；共享中心的模型和索引不会删除。") },
            confirmButton = { TapTextButton(onClick = { cleanupLegacy = false; search.cleanupLegacyModel() }) { Text("清理旧副本") } },
            dismissButton = { TapTextButton(onClick = { cleanupLegacy = false }) { Text("取消") } })
        if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理 Alpha 索引？") },
            text = { Text("将停止本应用的分析与搜索，并清理索引。照片、收藏、联网搜索历史和中心模型都会保留。") },
            confirmButton = { TapTextButton(onClick = { clear = false; search.clearIndex() }) { Text("清理索引") } },
            dismissButton = { TapTextButton(onClick = { clear = false }) { Text("取消") } })
        if (uninstall) AlertDialog(onDismissRequest = { uninstall = false }, title = { Text("卸载共享 AI 中心？") },
            text = { Text("中心和它保存的模型会被移除。Search Lab 及其他客户端也将无法使用本地 AI，直到重新安装并准备模型。Alpha 的照片、收藏与索引会保留。") },
            confirmButton = { TapTextButton(onClick = {
                uninstall = false
                try { deleteResult.launch(Intent(Intent.ACTION_DELETE,Uri.parse("package:${ModelSpec.HUB}")).putExtra(Intent.EXTRA_RETURN_RESULT,true)) }
                catch (_: Exception) { installer.message("无法打开系统卸载页面，请从系统应用管理操作。") }
            }) { Text("前往系统确认") } }, dismissButton = { TapTextButton(onClick = { uninstall = false }) { Text("取消") } })
    }
}
