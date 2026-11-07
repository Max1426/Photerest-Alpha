package com.photerest.app

import android.net.Uri
import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoActionSheet(photo: Photo, state: PhotoState, dismiss: () -> Unit, share: () -> Unit, collect: () -> Unit) {
    OperationSheet(dismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.width(132.dp).height((132f / photo.aspect).coerceIn(88f, 200f).dp)
                    .clip(MaterialTheme.shapes.large).background(Panel),
                contentAlignment = Alignment.Center
            ) {
                if (state.available(photo)) PhotoImage(photo.uri, Modifier.fillMaxSize(), revision = photo.added, scale = ContentScale.Fit)
                else Text("原图无法访问", color = Muted)
            }
            Spacer(Modifier.height(16.dp))
            OperationGroup("照片操作") {
                OperationItem("分享", Modifier.tapClickable(onClick = share), leading = { Symbol(Glyph.SHARE) })
                OperationItem(if (state.isFavorite(photo)) "管理收藏" else "收藏", Modifier.tapClickable(onClick = collect),
                    leading = { Symbol(Glyph.BOOKMARK) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(state: PhotoState, dismiss: () -> Unit, authorize: () -> Unit, settings: () -> Unit, save: (Set<String>) -> Unit, unlink: (String) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var removal by remember { mutableStateOf<Album?>(null) }
    val feedback = LocalTapFeedback.current
    OperationSheet(dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 16.dp)) {
            OperationHeading("关联手机相册", Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    OperationItem("相册分类", summary = "关联后添加顶部相册分类。“全部”始终展示所有已授权照片。")
                }
                if (state.access != Access.FULL) item {
                    OperationItem(
                        "照片访问",
                        summary = if (state.access == Access.NONE) "需要照片访问权限才能列出相册。" else "当前只列出包含已授权照片的相册。"
                    )
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        TapTextButton(onClick = authorize) { Text(if (state.access == Access.NONE) "授权照片访问" else "选择更多照片") }
                        TapTextButton(onClick = settings) { Text("系统设置") }
                    }
                }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                val albums = state.deviceAlbums + state.imported.filter { saved -> state.deviceAlbums.none { it.id == saved.id } }
                if (albums.isEmpty() && state.access != Access.NONE && !state.busy) item { OperationItem("没有发现可访问的本地照片相册") }
                items(albums, key = { it.id }) { album ->
                    val linked = state.imported.any { it.id == album.id }
                    val selection = if (linked) Modifier else Modifier.toggleable(album.id in selected, role = Role.Checkbox) { checked ->
                        feedback.perform()
                        selected = if (checked) selected + album.id else selected - album.id
                    }
                    OperationItem(
                        album.name, selection,
                        summary = "${album.count} 张可访问照片${if (linked) " · 已关联" else ""}",
                        leading = {
                            Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(Panel), contentAlignment = Alignment.Center) {
                                album.cover?.let { PhotoImage(it.uri, Modifier.fillMaxSize()) } ?: Symbol(Glyph.FOLDER)
                            }
                        },
                        trailing = {
                            if (linked) GlyphButton(Glyph.CLOSE, "解除关联：${album.name}") { removal = album }
                            else Checkbox(album.id in selected, onCheckedChange = null)
                        }
                    )
                }
            }
            TapButton(onClick = { save(selected) }, enabled = selected.isNotEmpty() && !state.busy, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text(if (selected.isEmpty()) "选择要关联的相册" else "关联 ${selected.size} 个相册") }
        }
    }
    removal?.let { album -> AlertDialog(onDismissRequest = { removal = null }, title = { DialogThemeBars(); Text("解除关联“${album.name}”？") }, text = { Text("仅移除顶部相册分类。“全部”中的照片、手机原图和已有收藏关系均会保留。") }, confirmButton = { TapTextButton(onClick = { unlink(album.id); removal = null }) { Text("解除关联") } }, dismissButton = { TapTextButton(onClick = { removal = null }) { Text("取消") } }) }
}

@Composable
fun ProfileEditor(state: PhotoState, dismiss: () -> Unit, save: (String, Uri?, () -> Unit) -> Unit) {
    var name by remember { mutableStateOf(state.profileName ?: "") }
    var avatar by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { avatar = it }
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { DialogThemeBars(); Text(if (state.profileName == null) "创建个人档案" else "编辑个人资料") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(Modifier.tapClickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Avatar(avatar?.toString() ?: state.avatar, name, Modifier.size(82.dp)) }
            TapTextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("选择头像") }
            OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("昵称") }, singleLine = true, enabled = !saving,
                supportingText = { Text("个人资料和收藏只保存在这台设备上。") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TapTextButton(onClick = { saving = true; save(name.trim(), avatar) { saving = false } }, enabled = name.isNotBlank() && !saving) { Text(if (saving) "保存中…" else "保存") } }, dismissButton = { TapTextButton(onClick = dismiss, enabled = !saving) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteSheet(photo: Photo, state: PhotoState, dismiss: () -> Unit, create: (String) -> Unit, save: (Set<Long>) -> Unit) {
    val existing = state.memberships.filter { it.uri == photo.uri }.map { it.folderId }.toSet()
    var selected by remember(photo.uri) { mutableStateOf(existing.ifEmpty { setOf(1L) }) }
    var newFolder by remember { mutableStateOf(false) }
    val feedback = LocalTapFeedback.current
    OperationSheet(dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OperationHeading("保存到收藏夹", Modifier.weight(1f))
                GlyphButton(Glyph.PLUS, "新建收藏夹") { newFolder = true }
            }
            Text("可同时选择多个收藏夹", color = Muted, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(state.folders, key = { it.id }) { folder ->
                    OperationItem(
                        folder.name,
                        Modifier.toggleable(folder.id in selected, role = Role.Checkbox) { checked ->
                            feedback.perform()
                            selected = if (checked) selected + folder.id else selected - folder.id
                        },
                        leading = { Symbol(Glyph.FOLDER) },
                        trailing = { Checkbox(folder.id in selected, onCheckedChange = null) }
                    )
                }
            }
            if (existing.isNotEmpty()) TapTextButton(onClick = { selected = emptySet() }) { Text("取消所有收藏", color = Muted) }
            TapButton(onClick = { save(selected.intersect(state.folders.map { it.id }.toSet())) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp)) { Text(if (selected.isEmpty()) "确认取消收藏" else "保存") }
        }
    }
    if (newFolder) NameDialog("新建收藏夹", "", { newFolder = false }) { create(it); newFolder = false }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { DialogThemeBars(); Text(title) }, text = { OutlinedTextField(value, { value = it.take(30) }, label = { Text("名称") }, singleLine = true) }, confirmButton = { TapTextButton(onClick = { save(value.trim()) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { TapTextButton(onClick = dismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(photo: Photo, available: Boolean, revision: Int, dismiss: () -> Unit, location: () -> Unit) {
    val context = LocalContext.current
    var rows by remember(photo.uri, revision) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(photo.uri, revision) { rows = withContext(Dispatchers.IO) { Gallery.metadata(context, photo) } }
    OperationSheet(dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            OperationGroup("照片信息") {
                if (!available) OperationItem("原图无法访问", summary = "以下为收藏时保存的信息。")
                if (rows == null) CircularProgressIndicator(Modifier.padding(24.dp))
                rows?.forEach { (name, value) -> OperationItem(name, summary = value) }
            }
            if (available && Build.VERSION.SDK_INT >= 29 && !Gallery.granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) TapTextButton(onClick = location, modifier = Modifier.padding(top = 12.dp)) { Text("允许读取照片记录的位置") }
        }
    }
}
