package com.photerest.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.text.DateFormat
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/** New online-search surfaces always use Expressive, without changing the app-wide preference. */
@Composable
internal fun OnlineTheme(content: @Composable () -> Unit) {
    val settings = LocalAppAppearance.current ?: AppSettings()
    CompositionLocalProvider(LocalAppAppearance provides settings.copy(operationStyle = OperationStyle.EXPRESSIVE)) {
        OperationTheme(content)
    }
}

@Composable
private fun GoogleMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.search_google), "Google", modifier.size(32.dp), contentScale = ContentScale.Fit)
}

@Composable
internal fun OnlineSearchSettings(store: SettingsStore, back: () -> Unit) {
    val settings by store.state.collectAsState()
    val feedback = LocalTapFeedback.current
    val scope = rememberCoroutineScope()
    val supported = Build.VERSION.SDK_INT >= 31
    var quota by remember { mutableStateOf<SearchQuota?>(null) }
    var quotaBusy by remember { mutableStateOf(false) }
    var quotaFailure by remember { mutableStateOf<String?>(null) }
    suspend fun loadQuota() {
        if (quotaBusy) return
        quotaBusy = true
        quotaFailure = null
        try { quota = ImageSearchClient.quotas()[SearchEngine.GOOGLE] }
        catch (error: Exception) { if (error is CancellationException) throw error; quotaFailure = searchMessage(error) }
        finally { quotaBusy = false }
    }
    LaunchedEffect(Unit) { loadQuota() }
    OnlineTheme {
        OperationScreen("联网搜图", "返回设置", back) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("用照片发现更多", style = MaterialTheme.typography.headlineMedium)
                Text("Google 联网搜图独立于本地 AI 搜索。每次新搜索前均需确认上传，历史结果保存在本机，重复查看免费。",
                    style = MaterialTheme.typography.bodyLarge)
                OnlineButtonPreview(settings)
                OperationGroup("照片上的搜索按钮") {
                    OperationItem("显示联网搜图按钮",
                        Modifier.toggleable(settings.showOnlineSearch, role = Role.Switch) { feedback.perform(); store.setOnlineSearchVisible(it) },
                        summary = "在照片详情右下角显示，不用于视频。",
                        trailing = { Switch(settings.showOnlineSearch, onCheckedChange = null) })
                    OperationItem("按钮毛玻璃",
                        Modifier.toggleable(settings.onlineSearchGlass, role = Role.Switch) { feedback.perform(); store.setOnlineSearchGlass(it) },
                        summary = if (supported) "从 Material 配色取色，只模糊按钮背后的照片。" else "当前系统使用实色 Material 按钮；实时模糊需要 Android 12 及以上。",
                        trailing = { Switch(settings.onlineSearchGlass, onCheckedChange = null) })
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("模糊程度 · ${settings.onlineSearchBlurDp} dp", style = MaterialTheme.typography.titleMedium)
                        Slider(value = settings.onlineSearchBlurDp.toFloat(), onValueChange = { store.previewOnlineSearchBlur(it.roundToInt()) },
                            onValueChangeFinished = { store.saveOnlineSearchBlur(); feedback.perform() }, valueRange = 0f..30f,
                            enabled = supported && settings.onlineSearchGlass)
                        Text("0 dp 保留透明取色，不进行模糊。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OperationGroup("Google 共享额度") {
                    OperationItem("Google Cloud Vision", leading = { GoogleMark() },
                        summary = "与经典版共用服务端额度，所有用户共享。")
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val current = quota
                        if (current != null) {
                            Text("剩余 ${current.remaining} / ${current.limit} 次", style = MaterialTheme.typography.titleLarge)
                            LinearProgressIndicator(progress = { if (current.limit > 0) (current.remaining.toFloat() / current.limit).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                            current.resetsAt?.let { Text("额度重置：" + DateFormat.getDateTimeInstance().format(Date(it)), style = MaterialTheme.typography.bodySmall) }
                        }
                        if (quotaBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        quotaFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TapTextButton(onClick = { scope.launch { loadQuota() } }, enabled = !quotaBusy) { Text("刷新共享额度") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OnlineButtonPreview(settings: AppSettings) {
    val backdrop = rememberOnlineBackdrop(settings.onlineSearchGlass)
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.extraLarge)) {
        OnlinePhotoBackdrop(backdrop, Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.linearGradient(listOf(Color(0xffcadfff), Color(0xffa594da), Color(0xffffcbab))))
                repeat(9) { index ->
                    drawRect(if (index % 2 == 0) Color.White.copy(alpha = .85f) else Color(0xff243b70),
                        Offset(size.width * .48f + index * 13.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
                }
                drawCircle(Color(0xfff3d85b), 52.dp.toPx(), Offset(45.dp.toPx(), size.height * .85f))
            }
        }
        OnlineSearchFab(settings, backdrop, Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            Toast.makeText(context, "这是按钮效果预览，不会联网或消耗额度", Toast.LENGTH_SHORT).show()
        }
        Text("效果预览", Modifier.align(Alignment.TopStart).padding(16.dp), color = Color(0xff182e50), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineSearchSheet(
    photo: Photo,
    searchRequest: suspend (Context, Photo) -> List<SearchResult> = { context, item -> ImageSearchClient.search(context, SearchEngine.GOOGLE, item) },
    thumbnailRequest: suspend (String) -> Bitmap? = ::onlineThumbnail,
    dismiss: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // Measure against the window, never the sheet's changing inset-reduced content area.
    // Otherwise consuming the top inset changes the height and moves the expanded anchor again.
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val topClearance = maxOf(WindowInsets.safeDrawing.getTop(density).toFloat(), windowHeight * .04f)
    val sheetHeight = with(density) { (windowHeight - topClearance).coerceAtLeast(1f).toDp() }
    var key by remember(photo.uri, photo.added) { mutableStateOf<SearchIdentity?>(null) }
    var checking by remember(photo.uri) { mutableStateOf(true) }
    var loading by remember(photo.uri) { mutableStateOf(false) }
    var saved by remember(photo.uri) { mutableStateOf<SavedSearch?>(null) }
    var failure by remember(photo.uri) { mutableStateOf<String?>(null) }
    var refresh by remember(photo.uri) { mutableStateOf(false) }
    val images = remember(photo.uri) { mutableStateMapOf<String, Bitmap?>() }
    val pending = remember(photo.uri) { mutableSetOf<String>() }
    val permits = remember { Semaphore(4) }
    val grid = rememberLazyGridState()
    suspend fun awaitResult(job: Deferred<SavedSearch>) {
        loading = true
        failure = null
        try {
            saved = job.await()
            refresh = false
            // The results grid is not composed while loading. Awaiting scrollToItem here
            // waits for that first layout forever and prevents loading from becoming false.
            loading = false
            grid.requestScrollToItem(0)
        }
        catch (error: Exception) { if (error is CancellationException) throw error; failure = searchMessage(error) }
        finally { loading = false }
    }
    LaunchedEffect(photo.uri, photo.added) {
        try {
            val identity = SearchHistoryRepository.identify(appContext, photo, SearchEngine.GOOGLE)
            key = identity
            saved = SearchHistoryRepository.read(appContext, identity)
            val pendingJob = SearchHistoryRepository.pending(identity)
            if (pendingJob != null) awaitResult(pendingJob)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = "无法读取照片或本地记录，请检查原图与照片权限。"
        } finally { checking = false }
    }
    fun approve() {
        val identity = key ?: return
        if (checking || loading) return
        // This is the only entry point for a new billable request. Its owner survives sheet dismissal.
        val job = SearchHistoryRepository.search(appContext, identity, refresh) {
            searchRequest(appContext, photo)
        }
        loading = true
        scope.launch { awaitResult(job) }
    }
    fun close() { scope.launch { state.hide(); dismiss() } }
    OnlineTheme {
        ModalBottomSheet(
            onDismissRequest = dismiss, sheetState = state,
            modifier = Modifier.height(sheetHeight).testTag("online-search-sheet"),
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            DialogThemeBars()
            Column(Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            ).testTag("online-search-body")) {
                Row(Modifier.fillMaxWidth().testTag("online-search-header").padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        TapTextButton(onClick = ::close) { Text("关闭") }
                    }
                    PhotoImage(photo.uri, Modifier.size(52.dp).clip(MaterialTheme.shapes.medium), revision = photo.added)
                    Box(Modifier.weight(1f).padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) { GoogleMark() }
                }
                if (checking || loading) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (loading) "正在搜图" else "正在查找本机历史", style = MaterialTheme.typography.headlineSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (loading) "可以关闭面板，成功结果仍会保存到这张照片。" else "仅在你确认后上传照片。")
                    }
                } else if (saved == null || refresh) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(if (refresh) "重新使用 Google 搜图" else "使用 Google 搜图", style = MaterialTheme.typography.headlineMedium)
                        Text("允许上传这张照片的缩放副本到 Google Cloud Vision，并消耗 1 次共享额度。", style = MaterialTheme.typography.bodyLarge)
                        Text("请确认照片适合上传。已保存的历史结果无需再次上传。", style = MaterialTheme.typography.bodyMedium)
                        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TapButton(onClick = ::approve, enabled = key != null && ImageSearchClient.configured, modifier = Modifier.fillMaxWidth()) {
                            Text("允许搜图（消耗 1 次）")
                        }
                        if (refresh && saved != null) TapTextButton(onClick = { refresh = false; failure = null }) { Text("返回已有结果 · 免费") }
                    }
                } else {
                    val result = saved!!
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("已保存的搜索结果", style = MaterialTheme.typography.titleLarge)
                            Text("再次查看不消耗额度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TapTextButton(onClick = { refresh = true; failure = null }) { Text("重新搜图") }
                    }
                    if (!result.persisted) Text("本次结果未能保存，请检查存储空间。", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                    LazyVerticalGrid(columns = GridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxWidth().weight(1f).testTag("online-search-grid"),
                        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (result.results.isEmpty()) item(span = { GridItemSpan(2) }) {
                            Text("没有找到相关图片。你可以稍后重新搜图。", Modifier.padding(20.dp))
                        }
                        itemsIndexed(result.results, key = { index, item -> "$index:${item.image}" }) { index, item ->
                            val url = item.thumbnail
                            LaunchedEffect(url) {
                                if (!images.containsKey(url) && pending.add(url)) {
                                    // Owned by the sheet, rather than a grid cell: scrolling doesn't cancel/restart image loading.
                                    scope.launch {
                                        try { images[url] = permits.withPermit { thumbnailRequest(url) } }
                                        finally { pending.remove(url) }
                                    }
                                }
                            }
                            Card(onClick = { openOnlineSource(context, item.page ?: item.image) }, shape = MaterialTheme.shapes.large) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.8f).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                    val bitmap = images[url]
                                    if (bitmap != null) Image(bitmap.asImageBitmap(), item.title.ifBlank { "相关图片" }, Modifier.fillMaxSize().testTag("online-result-image:$index"), contentScale = ContentScale.Crop)
                                    else if (images.containsKey(url)) Text("查看图片来源", Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge)
                                    else CircularProgressIndicator(Modifier.size(28.dp))
                                }
                                Text(item.title.ifBlank { "查看图片来源" }, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openOnlineSource(context: Context, address: String) {
    val uri = Uri.parse(address)
    if (uri.scheme != "https" && uri.scheme != "http") return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    catch (_: Exception) { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
}

private suspend fun onlineThumbnail(address: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        var url = URL(address)
        var bytes: ByteArray? = null
        for (redirect in 0..4) {
            if (url.protocol != "https") return@withContext null
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 15_000; instanceFollowRedirects = false
            }
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: return@withContext null
                    url = URL(url, location)
                    continue
                }
                if (code !in 200..299) return@withContext null
                bytes = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > 8 * 1024 * 1024) throw IOException("Thumbnail too large")
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                break
            } finally { connection.disconnect() }
        }
        val data = bytes ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (error: Exception) { if (error is CancellationException) throw error; null }
}
