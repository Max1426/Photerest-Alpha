package com.photerest.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: PhotoState, viewport: TabViewport, add: () -> Unit, authorize: () -> Unit,
    settings: () -> Unit, select: (String?) -> Unit, refresh: () -> Unit,
    open: (Photo, PhotoMotionKey) -> Unit, menu: (Photo) -> Unit
) {
    val feedback = LocalTapFeedback.current
    val selectedIndex = state.imported.indexOfFirst { it.id == state.selectedAlbum } + 1
    Column(Modifier.fillMaxSize()) {
        LabTopBar(stringResource(R.string.app_name), actions = { GlyphButton(Glyph.PLUS, "导入相册", action = add) })
        PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 8.dp) {
            AlbumTab("全部", selectedIndex == 0, state.switching && state.pendingAlbum == null) {
                feedback.perform()
                select(null)
            }
            state.imported.forEachIndexed { index, album ->
                AlbumTab(album.name, selectedIndex == index + 1, state.switching && state.pendingAlbum == album.id) {
                    feedback.perform()
                    select(album.id)
                }
            }
        }
        if (state.access == Access.PARTIAL) {
            TapTextButton(onClick = authorize) { Text("仅显示已授权照片 · 管理访问范围") }
        }
        if (state.ready && state.access != Access.NONE && !state.readError && state.feed.isNotEmpty()) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2), state = viewport.grid,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize().testTag("home-feed")
                ) {
                    items(state.feed, key = { it.uri }) { photo ->
                        val source = PhotoMotionKey(PhotoListOrigin.Album(state.selectedAlbum), photo.uri)
                        PhotoCard(photo, source, open = { open(photo, source) }, menu = { menu(photo) })
                    }
                }
            }
        } else if (!state.ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    state.access == Access.NONE -> {
                        EmptyState("让照片在这里相遇", "允许读取照片后，“全部”会直接展示手机中的图片，无需关联相册。", "允许访问照片", authorize)
                        TapTextButton(onClick = settings) { Text("前往系统设置") }
                    }
                    state.readError -> EmptyState("读取照片失败", "请检查照片权限后重新读取。", "重新读取", refresh)
                    state.selectedAlbum == null -> EmptyState("还没有可查看的照片", "手机中暂无可访问的图片。新增照片后刷新，或检查照片访问权限。", "刷新照片", refresh)
                    else -> EmptyState("该相册暂无可查看的照片", "相册可能为空，或其中的照片尚未获得访问授权。", "管理相册", add)
                }
            }
        }
    }
}

@Composable
private fun AlbumTab(name: String, selected: Boolean, loading: Boolean, click: () -> Unit) {
    Tab(selected = selected, onClick = click, text = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (LocalExpressive.current) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = if (LocalExpressive.current) FontWeight.Bold else FontWeight.Normal
            )
        },
        navigationIcon = navigationIcon,
        actions = actions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: PhotoState, edit: () -> Unit, settings: () -> Unit, open: (Photo, PhotoMotionKey) -> Unit, menu: (Photo) -> Unit, model: PhotoModel) {
    var foldersTab by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<Folder?>(null) }
    var rename by remember { mutableStateOf<Folder?>(null) }
    var delete by remember { mutableStateOf<Folder?>(null) }
    LaunchedEffect(state.folders) { if (selected != null && state.folders.none { it.id == selected }) selected = null }
    val visible = if (selected == null) state.favorites else state.favorites.filter { p -> state.memberships.any { it.uri == p.uri && it.folderId == selected } }
    Column(Modifier.fillMaxSize()) {
        val feedback = LocalTapFeedback.current
        LabTopBar("个人主页", actions = {
            GlyphButton(Glyph.EDIT, if (state.profileName == null) "创建档案" else "编辑资料", action = edit)
            GlyphButton(Glyph.SETTINGS, "设置", action = settings)
        })
        ElevatedCard(
            onClick = { feedback.perform(); edit() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Avatar(state.avatar, state.profileName, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.profileName ?: "你的 Photerest", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${state.favorites.size} 张收藏 · ${state.folders.size} 个收藏夹", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        PrimaryTabRow(selectedTabIndex = if (foldersTab) 1 else 0) {
            Tab(selected = !foldersTab, onClick = { feedback.perform(); foldersTab = false; selected = null }, text = { Text("我收藏的图片", maxLines = 2) })
            Tab(selected = foldersTab, onClick = { feedback.perform(); foldersTab = true }, text = { Text("收藏夹", maxLines = 2) })
        }
        Spacer(Modifier.height(8.dp))
        if (state.profileName == null) EmptyState("建立你的照片收藏", "设置昵称和头像，开始整理自己的收藏夹。", "创建个人档案", edit)
        else if (foldersTab) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TapTextButton(onClick = { newFolder = true }) {
                            Symbol(Glyph.PLUS)
                            Spacer(Modifier.width(8.dp))
                            Text("新建收藏夹")
                        }
                    }
                }
                items(state.folders, key = { it.id }) { folder ->
                    val photos = state.favorites.filter { p -> state.memberships.any { it.uri == p.uri && it.folderId == folder.id } }
                    OperationItem(
                        folder.name, Modifier.tapClickable { selected = folder.id; foldersTab = false },
                        summary = "${photos.size} 张照片",
                        leading = {
                            val cover = photos.firstOrNull { state.available(it) }
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (cover != null) PhotoImage(cover.uri, Modifier.fillMaxSize()) else Symbol(Glyph.FOLDER)
                                }
                            }
                        },
                        trailing = { GlyphButton(Glyph.MORE, "管理收藏夹：${folder.name}") { manage = folder } }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            selected?.let { id -> Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.folders.find { it.id == id }?.name ?: "收藏夹", fontSize = 15.sp, color = Muted, modifier = Modifier.weight(1f))
                TapTextButton(onClick = { selected = null }) { Text("查看全部") }
            } }
            if (visible.isEmpty()) EmptyState("还没有收藏照片", "在照片菜单或大图页点击收藏，照片就会出现在这里。")
            else {
                val photoScroll = rememberLazyStaggeredGridState()

                LazyVerticalStaggeredGrid(state = photoScroll, columns = StaggeredGridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.uri }) { photo ->
                        val source = PhotoMotionKey(PhotoListOrigin.Favorites(selected), photo.uri)
                        PhotoCard(photo, source, state.available(photo),
                            if (state.access == Access.FULL) "原图已删除或不可读取" else "照片未授权或原图已删除",
                            { open(photo, source) }, { menu(photo) })
                    }
                }
            }
        }
    }
    if (newFolder || manage != null || rename != null || delete != null) OperationTheme {
        if (newFolder) NameDialog("新建收藏夹", "", { newFolder = false }) { model.createFolder(it); newFolder = false }
        manage?.let { folder ->
            AlertDialog(
                onDismissRequest = { manage = null },
                title = { Text(folder.name) },
                text = {
                    Column {
                        OperationItem("重命名", Modifier.tapClickable { manage = null; rename = folder }, leading = { Symbol(Glyph.EDIT) })
                        if (folder.id != 1L) OperationItem(
                            "删除", Modifier.tapClickable { manage = null; delete = folder },
                            leading = { Symbol(Glyph.TRASH, color = MaterialTheme.colorScheme.error) }
                        )
                    }
                },
                confirmButton = { TapTextButton(onClick = { manage = null }) { Text("关闭") } }
            )
        }
        rename?.let { folder -> NameDialog("重命名收藏夹", folder.name, { rename = null }) { model.renameFolder(folder.id, it); rename = null } }
        delete?.let { folder ->
            AlertDialog(
                onDismissRequest = { delete = null },
                title = { Text("删除“${folder.name}”？") },
                text = { Text("该收藏夹内的收藏关系会被移除，手机原照片不会被删除。其他收藏夹中的同一照片仍会保留。") },
                confirmButton = { TapTextButton(onClick = { model.deleteFolder(folder.id); delete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TapTextButton(onClick = { delete = null }) { Text("取消") } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScreen(
    detail: PhotoDetail, available: Boolean, favorite: Boolean, back: () -> Unit,
    collect: () -> Unit, share: () -> Unit, info: () -> Unit,
    openOriginal: () -> Unit, missing: String, authorize: () -> Unit, navigating: Boolean
) {
    val photo = detail.photo
    var scale by remember(photo.uri) { mutableFloatStateOf(1f) }
    var offset by remember(photo.uri) { mutableStateOf(Offset.Zero) }
    var entered by remember(detail) { mutableStateOf(false) }
    LaunchedEffect(navigating) { if (!navigating) entered = true }
    val feedback = LocalTapFeedback.current
    Scaffold(
        topBar = {
            LabTopBar(
                photo.name,
                navigationIcon = { GlyphButton(Glyph.BACK, "返回照片流", action = back) },
                actions = { GlyphButton(Glyph.MORE, "照片信息", action = info) }
            )
        },
        bottomBar = {
            BottomAppBar(contentPadding = PaddingValues(horizontal = 8.dp)) {
                GlyphButton(Glyph.SHARE, "分享照片", action = share)
                GlyphButton(Glyph.IMAGE_UP, "在相册中打开原图", action = openOriginal)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { feedback.perform(); collect() }) {
                    Symbol(Glyph.BOOKMARK)
                    Spacer(Modifier.width(8.dp))
                    Text(if (favorite) "已收藏" else "收藏", maxLines = 1)
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            if (available && widthPx >= 1f && heightPx >= 1f) {
                val fitWidth = minOf(widthPx, heightPx * photo.aspect)
                val fitHeight = minOf(heightPx, widthPx / photo.aspect)
                Box(Modifier.fillMaxSize().pointerInput(photo.uri, fitWidth, fitHeight, navigating) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (navigating) return@detectTransformGestures
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val maxX = ((fitWidth * scale - widthPx) / 2).coerceAtLeast(0f)
                        val maxY = ((fitHeight * scale - heightPx) / 2).coerceAtLeast(0f)
                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    }
                }.pointerInput(photo.uri, navigating) {
                    detectTapGestures(onDoubleTap = {
                        if (navigating) return@detectTapGestures
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    })
                }) {
                    val imageWidth = fitWidth * scale
                    val imageHeight = fitHeight * scale
                    val imageLeft = (widthPx - imageWidth) / 2 + offset.x
                    val imageTop = (heightPx - imageHeight) / 2 + offset.y
                    val left = imageLeft.coerceIn(0f, widthPx - 1f)
                    val top = imageTop.coerceIn(0f, heightPx - 1f)
                    val visibleWidth = (minOf(imageLeft + imageWidth, widthPx) - left).coerceAtLeast(1f)
                    val visibleHeight = (minOf(imageTop + imageHeight, heightPx) - top).coerceAtLeast(1f)
                    // Animate the visible crop itself, including the current zoom and pan on return.
                    PhotoImage(
                        photo.uri,
                        Modifier.absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .size(with(density) { visibleWidth.toDp() }, with(density) { visibleHeight.toDp() })
                            .then(photoBounds(detail.source)).clipToBounds(),
                        large = true, revision = photo.added, scale = ContentScale.FillBounds,
                        thumbnail = detail.thumbnail, preferThumbnail = !entered,
                        imageModifier = Modifier.graphicsLayer {
                            scaleX = imageWidth / visibleWidth
                            scaleY = imageHeight / visibleHeight
                            translationX = (imageLeft - left + (imageWidth - visibleWidth) / 2) * size.width / visibleWidth
                            translationY = (imageTop - top + (imageHeight - visibleHeight) / 2) * size.height / visibleHeight
                        }
                    )
                }
            } else if (!available) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyState(missing, "请检查原照片或访问权限，已有收藏记录仍会保留。", "管理照片权限", authorize)
                }
            }
        }
    }
}
