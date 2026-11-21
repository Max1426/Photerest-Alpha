package com.photerest.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val ALL_ALBUMS = "__all__"

private data class ResultItem(
    val photo: Photo,
    val similarity: Float?,
    val highlight: Boolean = false,
)

/**
 * Option C "search workbench" page: engine status, album scope, search field and an aspect-aware
 * staggered result grid. Wide photos span the whole line (A/B-style banners); square/portrait photos
 * stay in one lane (C-style small tiles). No filenames are rendered anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoSearchScreen(
    state: PhotoState,
    search: LocalSearchViewModel,
    open: (Photo, PhotoMotionKey) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val ui by search.state.collectAsState()
    var scopeAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showScores by rememberSaveable { mutableStateOf(true) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val gridState = rememberLazyStaggeredGridState()

    val albums = remember(state.deviceAlbums, state.library) {
        state.deviceAlbums.mapNotNull { album ->
            val photos = state.library.values.filter { it.albumId == album.id && !it.isVideo && state.available(it) }
            if (photos.isEmpty()) null else album to photos.sortedByDescending { it.added }
        }
    }
    val scopePhotos = remember(albums, scopeAlbum, state.library) {
        if (scopeAlbum == null) {
            state.library.values.filter { !it.isVideo && state.available(it) }.sortedByDescending { it.added }
        } else {
            albums.firstOrNull { it.first.id == scopeAlbum }?.second.orEmpty()
        }
    }
    val scopeKey = scopeAlbum ?: ALL_ALBUMS
    val scopeName = albums.firstOrNull { it.first.id == scopeAlbum }?.first?.name ?: "全部相册"
    val analyzed = ui.analyzedScope == scopeKey && !ui.analyzing
    val engineReady = ui.engine == LocalSearchEngineState.READY

    val searchActivity = LocalContext.current as androidx.activity.ComponentActivity
    DisposableEffect(searchActivity, search) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) search.autoLoad()
            if (event == Lifecycle.Event.ON_STOP) search.pause()
        }
        searchActivity.lifecycle.addObserver(observer)
        onDispose { searchActivity.lifecycle.removeObserver(observer); search.pause() }
    }
    LaunchedEffect(Unit) { search.autoLoad() }

    LaunchedEffect(query, analyzed, scopeKey) {
        if (!analyzed) {
            search.clearResults()
            return@LaunchedEffect
        }
        if (query.isBlank()) {
            search.clearResults()
            return@LaunchedEffect
        }
        delay(350)
        search.search(query, scopePhotos)
    }

    val items = remember(ui.results, analyzed, query, scopePhotos) {
        if (!analyzed) emptyList()
        else if (query.isBlank()) {
            scopePhotos.map { ResultItem(it, null) }
        } else {
            val byUri = scopePhotos.associateBy { it.uri }
            ui.results.mapIndexedNotNull { index, match ->
                byUri[match.uri]?.let { ResultItem(it, match.similarity, highlight = index == 0) }
            }
        }
    }

    BrowserChrome(
        header = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("智能搜索", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BetaBadge()
                    }
                },
                actions = {
                    Box {
                        GlyphButton(Glyph.MORE, "搜索选项", action = { showMenu = true })
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (showScores) "隐藏匹配度" else "显示匹配度") },
                                onClick = { showScores = !showScores; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("清理本地索引") },
                                onClick = { search.clearIndex(); showMenu = false },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        navigation = bottomBar,
        scrollState = gridState,
        scrollLayout = { gridState.layoutInfo },
    ) { insets ->
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().testTag("local-search-results"),
            contentPadding = insets.listPadding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine, key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EngineCard(
                        ui = ui,
                        onDownload = search::downloadAndLoad,
                        onLoad = search::loadModel,
                        onRetry = search::retry,
                    )
                    SharedModelCard(ui, search)
                    AlbumCard(
                        engineReady = engineReady,
                        analyzed = analyzed,
                        analyzing = ui.analyzing,
                        analysisDone = ui.analysisDone,
                        analysisTotal = ui.analysisTotal,
                        scopeName = scopeName,
                        photos = scopePhotos,
                        onPickAlbum = { showAlbumPicker = true },
                        onAnalyze = { search.analyze(scopePhotos, scopeKey) },
                        onCancel = search::cancelAnalysis,
                    )
                    SearchField(
                        query = query,
                        enabled = analyzed,
                        onQueryChange = {
                            query = it
                            if (it.isNotBlank()) search.clearResults()
                        },
                        suggestions = listOf("海边", "夜景", "猫", "朋友"),
                        onSuggestion = { query = it },
                    )
                    if (ui.searching) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ResultHeader(
                        count = if (query.isBlank()) scopePhotos.size else items.size,
                        searched = query.isNotBlank(),
                        analyzed = analyzed,
                    )
                    ui.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!analyzed) {
                item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                    SearchEmptyState(
                        engineReady = engineReady,
                        analyzing = ui.analyzing,
                        progress = ui.analysisDone to ui.analysisTotal,
                    )
                }
            } else if (items.isEmpty() && query.isNotBlank() && !ui.searching) {
                item(span = StaggeredGridItemSpan.FullLine, key = "no-results") {
                    NoResultsCard(query)
                }
            } else {
                items(
                    count = items.size,
                    key = { index -> items[index].photo.uri },
                    span = { index ->
                        val style = searchResultStyle(items[index].photo.aspect)
                        if (style == SearchResultStyle.WIDE) StaggeredGridItemSpan.FullLine
                        else StaggeredGridItemSpan.SingleLane
                    },
                ) { index ->
                    val item = items[index]
                    SearchResultTile(
                        item = item,
                        showScore = showScores,
                        open = { photo, source -> open(photo, source) },
                    )
                }
            }
        }
    }

    if (showAlbumPicker) {
        ModalBottomSheet(onDismissRequest = { showAlbumPicker = false }) {
            AlbumPicker(
                albums = albums,
                selected = scopeAlbum,
                onSelect = {
                    scopeAlbum = it
                    showAlbumPicker = false
                    query = ""
                    search.clearResults()
                },
            )
        }
    }
}

@Composable
internal fun BetaBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Text(
            "BETA",
            fontSize = 7.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun EngineCard(
    ui: LocalSearchUiState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ui.engine) {
                LocalSearchEngineState.READY -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EngineOrb()
                    Column(Modifier.weight(1f)) {
                        Text("本地引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("EmbeddingGemma-2 · 共享中心 · 完全离线", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill("${ui.accelerator ?: "GPU"} 就绪")
                }
                LocalSearchEngineState.DOWNLOADING -> {
                    Text("正在下载本地模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { ui.downloadPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${ui.downloadPercent}% · 约 484MB · 仅首次下载", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LocalSearchEngineState.LOADING -> {
                    Text("正在加载本地引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("优先 GPU，加载失败会自动回退 CPU", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LocalSearchEngineState.FAILED -> {
                    Text("本地引擎不可用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error)
                    Text(ui.error ?: "模型加载失败", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onRetry) { Text("重试") }
                }
                LocalSearchEngineState.NEEDS_DOWNLOAD, LocalSearchEngineState.DOWNLOADED -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EngineOrb()
                        Column(Modifier.weight(1f)) {
                            Text("本地智能搜索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("照片不离开设备 · 由模型中心统一提供", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = { if (ui.engine == LocalSearchEngineState.DOWNLOADED) onLoad() else onDownload() },
                        modifier = Modifier.fillMaxWidth().testTag("local-search-load"),
                    ) {
                        Text(if (ui.engine == LocalSearchEngineState.DOWNLOADED) "连接模型中心" else "打开模型中心")
                    }
                }
                LocalSearchEngineState.UNSUPPORTED -> {
                    Text("此设备不支持本地智能搜索", style = MaterialTheme.typography.titleMedium)
                    Text("需要 Android 12 及以上、arm64 设备。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EngineOrb() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(14.dp)) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Symbol(Glyph.SEARCH)
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun AlbumCard(
    engineReady: Boolean,
    analyzed: Boolean,
    analyzing: Boolean,
    analysisDone: Int,
    analysisTotal: Int,
    scopeName: String,
    photos: List<Photo>,
    onPickAlbum: () -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    photos.take(3).forEach { photo ->
                        PhotoImage(
                            photo.uri,
                            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
                            revision = photo.added,
                            scale = ContentScale.Crop,
                        )
                    }
                    if (photos.isEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Symbol(Glyph.FOLDER) }
                        }
                    }
                }
                Column(Modifier.weight(1.6f)) {
                    Text(scopeName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            analyzing -> "分析中 $analysisDone / $analysisTotal"
                            analyzed -> "已分析 ${photos.size} 张"
                            else -> "${photos.size} 张照片"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (engineReady) {
                    TextButton(onClick = onPickAlbum, enabled = !analyzing) { Text("更换") }
                }
            }
            when {
                !engineReady -> Text("加载本地引擎后即可分析相册", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                analyzing -> {
                    LinearProgressIndicator(
                        progress = { if (analysisTotal == 0) 0f else analysisDone.toFloat() / analysisTotal },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("取消分析") }
                }
                analyzed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("可搜索")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onAnalyze) { Text("重新分析") }
                    }
                }
                else -> {
                    Button(
                        onClick = onAnalyze,
                        enabled = photos.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("local-search-analyze"),
                    ) { Text("分析 ${photos.size} 张照片") }
                    if (photos.size > 500) {
                        Text("照片较多，分析可能需要较长时间；可随时取消。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("local-search-query"),
        placeholder = { Text(if (enabled) "搜索照片，例如：海边、猫、夜景" else "先完成相册分析") },
        leadingIcon = { Symbol(Glyph.SEARCH) },
        trailingIcon = if (query.isNotEmpty()) {
            { GlyphButton(Glyph.CLOSE, "清除搜索", action = { onQueryChange("") }) }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
    if (query.isBlank() && enabled) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions) { suggestion ->
                AssistChip(onClick = { onSuggestion(suggestion) }, label = { Text(suggestion) })
            }
        }
    }
}

@Composable
private fun ResultHeader(count: Int, searched: Boolean, analyzed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (!analyzed) "本地照片" else if (searched) "结果" else "已索引照片",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (!analyzed) "等待分析" else "$count 张 · 按匹配度",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchEmptyState(engineReady: Boolean, analyzing: Boolean, progress: Pair<Int, Int>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Symbol(Glyph.SEARCH, Modifier.size(36.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when {
                analyzing -> "正在分析相册 ${progress.first}/${progress.second}"
                engineReady -> "完成相册分析后，这里会显示可搜索的照片"
                else -> "启用本地引擎后即可开始"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoResultsCard(query: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol(Glyph.SEARCH, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text("没有找到与“$query”匹配的照片", style = MaterialTheme.typography.bodyMedium)
        Text("试试海边、夜景、猫，或者换个说法", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AlbumPicker(albums: List<Pair<Album, List<Photo>>>, selected: String?, onSelect: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("选择相册", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp))
        AlbumPickerRow(
            title = "全部相册",
            count = albums.sumOf { it.second.size },
            photos = albums.flatMap { it.second }.take(3),
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        albums.forEach { (album, photos) ->
            AlbumPickerRow(
                title = album.name,
                count = photos.size,
                photos = photos.take(3),
                selected = selected == album.id,
                onClick = { onSelect(album.id) },
            )
        }
    }
}

@Composable
private fun AlbumPickerRow(title: String, count: Int, photos: List<Photo>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            photos.forEach { photo ->
                PhotoImage(photo.uri, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                    revision = photo.added, scale = ContentScale.Crop)
            }
        }
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Text("$count 张", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchResultTile(item: ResultItem, showScore: Boolean, open: (Photo, PhotoMotionKey) -> Unit) {
    val photo = item.photo
    val motion = LocalPhotoMotion.current
    val key = remember(photo.uri) { PhotoMotionKey(PhotoListOrigin.Search, photo.uri) }
    val tracked = remember(key, photo.added) { PhotoCardSource() }
    val style = remember(photo.aspect) { searchResultStyle(photo.aspect) }
    val displayAspect = remember(photo.aspect) { searchResultDisplayAspect(photo.aspect) }
    val percent = item.similarity?.let { (it * 100).toInt().coerceIn(0, 100) }
    val shape = if (style == SearchResultStyle.WIDE) RoundedCornerShape(18.dp) else RoundedCornerShape(14.dp)

    DisposableEffect(motion, key, tracked) {
        motion?.track(key, tracked)
        onDispose { motion?.untrack(key, tracked) }
    }

    Box(
        Modifier.fillMaxWidth().aspectRatio(displayAspect).clip(shape)
            .clickable { open(photo, key) }
            .onGloballyPositioned { tracked.coordinates = it }
            .semantics {
                contentDescription = if (percent != null && showScore) "搜索结果照片，匹配度 $percent%"
                else "搜索结果照片"
            }
            .testTag("search-result-${photo.uri}"),
    ) {
        Box(Modifier.fillMaxSize().drawWithContent {
            if (motion?.flight?.key != key) drawContent()
        }.background(MaterialTheme.colorScheme.surfaceVariant)) {
            PhotoImage(
                photo.uri,
                Modifier.fillMaxSize(),
                revision = photo.added,
                scale = ContentScale.Crop,
                onBitmap = { tracked.bitmap = it },
            )
        }
        if (showScore && percent != null) {
            Surface(
                color = if (item.highlight) MaterialTheme.colorScheme.primaryContainer
                else Color.Black.copy(alpha = 0.55f),
                contentColor = if (item.highlight) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SharedModelCard(ui: LocalSearchUiState, search: LocalSearchViewModel) {
    var confirm by remember { mutableStateOf(false) }
    if (ui.supported) ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("共享模型中心", style = MaterialTheme.typography.titleMedium)
            Text("Alpha 与搜索实验端共用中心的一份模型，现有索引保留。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = search::openCenter) { Text("打开模型中心") }
            if (ui.hasLegacyModel) {
                Button(onClick = search::migrateModel, enabled = !ui.analyzing && !ui.searching) { Text("迁移已有模型（无需重新下载）") }
                OutlinedButton(onClick = { confirm = true }, enabled = ui.sharedVerified && ui.engine == LocalSearchEngineState.READY && !ui.analyzing && !ui.searching) { Text("清理 Alpha 旧模型 · 约 485 MB") }
                Text("迁移后先加载并成功完成一次分析或搜索，再清理旧副本。不会自动删除。", style = MaterialTheme.typography.bodySmall)
            } else Text("Alpha 不保存模型副本。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理 Alpha 旧模型？") },
        text = { Text("将核对中心模型状态，只删除 Alpha 里的旧模型文件，保留照片、收藏和索引。之后本地搜索依赖模型中心。") },
        confirmButton = { TextButton(onClick = { confirm = false; search.cleanupLegacyModel() }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
