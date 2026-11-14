package com.photerest.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
    open: (Photo, PhotoMotionKey) -> Unit, menu: (Photo) -> Unit,
    bottomBar: @Composable () -> Unit
) {
    val feedback = LocalTapFeedback.current
    val selectedIndex = state.imported.indexOfFirst { it.id == state.selectedAlbum } + 1
    BrowserChrome(
        header = {
            Column {
                LabTopBar(stringResource(R.string.app_name), transparent = true, actions = { GlyphButton(Glyph.PLUS, "导入相册", action = add) })
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedIndex, edgePadding = 8.dp,
                    containerColor = Color.Transparent, divider = {}
                ) {
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
            }
        },
        navigation = bottomBar,
        scrollState = viewport.grid,
        scrollLayout = { viewport.grid.layoutInfo }
    ) { insets ->
        if (state.ready && state.access != Access.NONE && !state.readError && state.feed.isNotEmpty()) {
            val refreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.refreshing, onRefresh = refresh, state = refreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = insets.top + insets.fade),
                        isRefreshing = state.refreshing, state = refreshState
                    )
                }
            ) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2), state = viewport.grid,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
                    contentPadding = insets.listPadding(8.dp),
                    modifier = Modifier.fillMaxSize().testTag("home-feed")
                ) {
                    items(state.feed, key = { it.uri }) { photo ->
                        val source = PhotoMotionKey(PhotoListOrigin.Album(state.selectedAlbum), photo.uri)
                        PhotoCard(photo, source, open = { open(photo, source) }, menu = { menu(photo) })
                    }
                }
            }
        } else if (!state.ready) {
            Box(Modifier.fillMaxSize().padding(insets.bars), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                Modifier.fillMaxSize().padding(insets.bars).verticalScroll(rememberScrollState()),
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
    transparent: Boolean = false,
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
        actions = actions,
        colors = if (transparent) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        else TopAppBarDefaults.topAppBarColors()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: PhotoState, edit: () -> Unit, settings: () -> Unit, open: (Photo, PhotoMotionKey) -> Unit,
    menu: (Photo) -> Unit, model: PhotoModel, bottomBar: @Composable () -> Unit
) {
    var foldersTab by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<Folder?>(null) }
    var rename by remember { mutableStateOf<Folder?>(null) }
    var delete by remember { mutableStateOf<Folder?>(null) }
    LaunchedEffect(state.folders) { if (selected != null && state.folders.none { it.id == selected }) selected = null }
    val visible = if (selected == null) state.favorites else state.favorites.filter { p -> state.memberships.any { it.uri == p.uri && it.folderId == selected } }
    val feedback = LocalTapFeedback.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val compact = with(LocalDensity.current) { windowHeight.toDp() < 420.dp }
    val folderScroll = rememberLazyListState()
    val photoScroll = key(selected) { rememberLazyStaggeredGridState() }
    BrowserChrome(
        header = {
            Column {
                LabTopBar("个人主页", transparent = true, actions = {
                    GlyphButton(Glyph.EDIT, if (state.profileName == null) "创建档案" else "编辑资料", action = edit)
                    GlyphButton(Glyph.SETTINGS, "设置", action = settings)
                })
                ElevatedCard(
                    onClick = { feedback.perform(); edit() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(if (compact) 8.dp else 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Avatar(state.avatar, state.profileName, Modifier.size(if (compact) 36.dp else 56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.profileName ?: "你的 Photerest", style = MaterialTheme.typography.titleLarge, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                            Text("${state.favorites.size} 张收藏 · ${state.folders.size} 个收藏夹", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                PrimaryTabRow(
                    selectedTabIndex = if (foldersTab) 1 else 0, containerColor = Color.Transparent, divider = {}
                ) {
                    Tab(selected = !foldersTab, onClick = { feedback.perform(); foldersTab = false; selected = null }, text = { Text("我收藏的图片", maxLines = 2) })
                    Tab(selected = foldersTab, onClick = { feedback.perform(); foldersTab = true }, text = { Text("收藏夹", maxLines = 2) })
                }
                if (!foldersTab) selected?.let { id ->
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.folders.find { it.id == id }?.name ?: "收藏夹", fontSize = 15.sp, color = Muted, modifier = Modifier.weight(1f))
                        TapTextButton(onClick = { selected = null }) { Text("查看全部") }
                    }
                }
            }
        },
        navigation = bottomBar,
        scrollState = if (foldersTab) folderScroll else photoScroll,
        scrollLayout = { if (foldersTab) folderScroll.layoutInfo else photoScroll.layoutInfo }
    ) { insets ->
        if (state.profileName == null || (!foldersTab && visible.isEmpty())) {
            Column(Modifier.fillMaxSize().padding(insets.bars).verticalScroll(rememberScrollState())) {
                if (state.profileName == null) {
                    EmptyState("建立你的照片收藏", "设置昵称和头像，开始整理自己的收藏夹。", "创建个人档案", edit)
                } else {
                    EmptyState("还没有收藏照片", "在照片菜单或大图页点击收藏，照片就会出现在这里。")
                }
            }
        } else if (foldersTab) {
            LazyColumn(state = folderScroll, contentPadding = insets.listPadding(12.dp), modifier = Modifier.fillMaxSize()) {
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
            LazyVerticalStaggeredGrid(
                state = photoScroll, columns = StaggeredGridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
                contentPadding = insets.listPadding(8.dp), modifier = Modifier.fillMaxSize().testTag("profile-photos")
            ) {
                items(visible, key = { it.uri }) { photo ->
                    val source = PhotoMotionKey(PhotoListOrigin.Favorites(selected), photo.uri)
                    PhotoCard(photo, source, state.available(photo),
                        if (state.access == Access.FULL) "原图已删除或不可读取" else "照片未授权或原图已删除",
                        { open(photo, source) }, { menu(photo) })
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
    detail: PhotoDetail, motion: PhotoMotion, available: Boolean, favorite: Boolean, back: () -> Unit,
    collect: () -> Unit, share: () -> Unit, info: () -> Unit,
    openOriginal: () -> Unit, missing: String, authorize: () -> Unit
) {
    val photo = detail.photo
    val navigating = motion.busy
    val toolbar = Modifier.graphicsLayer { alpha = motion.chromeAlpha }
    val feedback = LocalTapFeedback.current
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Box(toolbar) {
                LabTopBar(
                    photo.name,
                    navigationIcon = { GlyphButton(Glyph.BACK, "返回照片流", action = back) },
                    actions = { GlyphButton(Glyph.MORE, "照片信息", action = info) }
                )
            }
        },
        bottomBar = {
            BottomAppBar(modifier = toolbar, contentPadding = PaddingValues(horizontal = 8.dp)) {
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds()
            .onGloballyPositioned { detail.viewport = it.boundsInRoot() }
            .graphicsLayer { alpha = if (motion.flight == null) motion.progress else 0f }) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            if (available && widthPx >= 1f && heightPx >= 1f) {
                val fitWidth = minOf(widthPx, heightPx * photo.aspect)
                val fitHeight = minOf(heightPx, widthPx / photo.aspect)
                Box(Modifier.fillMaxSize().pointerInput(photo.uri, fitWidth, fitHeight, navigating) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (navigating) return@detectTransformGestures
                        detail.scale = (detail.scale * zoom).coerceIn(1f, 5f)
                        val maxX = ((fitWidth * detail.scale - widthPx) / 2).coerceAtLeast(0f)
                        val maxY = ((fitHeight * detail.scale - heightPx) / 2).coerceAtLeast(0f)
                        detail.pan = Offset((detail.pan.x + pan.x).coerceIn(-maxX, maxX), (detail.pan.y + pan.y).coerceIn(-maxY, maxY))
                    }
                }.pointerInput(photo.uri, navigating) {
                    detectTapGestures(onDoubleTap = {
                        if (navigating) return@detectTapGestures
                        detail.scale = if (detail.scale > 1f) 1f else 2.5f
                        detail.pan = Offset.Zero
                    })
                }) {
                    val imageWidth = fitWidth * detail.scale
                    val imageHeight = fitHeight * detail.scale
                    val imageLeft = (widthPx - imageWidth) / 2 + detail.pan.x
                    val imageTop = (heightPx - imageHeight) / 2 + detail.pan.y
                    val left = imageLeft.coerceIn(0f, widthPx - 1f)
                    val top = imageTop.coerceIn(0f, heightPx - 1f)
                    val visibleWidth = (minOf(imageLeft + imageWidth, widthPx) - left).coerceAtLeast(1f)
                    val visibleHeight = (minOf(imageTop + imageHeight, heightPx) - top).coerceAtLeast(1f)
                    // Animate the visible crop itself, including the current zoom and pan on return.
                    PhotoImage(
                        photo.uri,
                        Modifier.absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .size(with(density) { visibleWidth.toDp() }, with(density) { visibleHeight.toDp() })
                            .clipToBounds(),
                        large = true, revision = photo.added, scale = ContentScale.FillBounds,
                        thumbnail = detail.thumbnail, onBitmap = { detail.bitmap = it },
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
