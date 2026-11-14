package com.photerest.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.hideFromAccessibility
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.lifecycleScope

data class DailyOpenRequest(val sequence: Long, val photo: Photo)
private enum class SettingsRoute { MAIN, DAILY }

class MainActivity : ComponentActivity() {
    private val dailyOpen = MutableStateFlow<DailyOpenRequest?>(null)
    private var dailySequence = 0L
    private fun receiveDaily(intent: Intent?) {
        if (intent?.action != DailyRecommendations.OPEN_ACTION) return
        val record = intent.getStringExtra(DailyRecommendations.RECORD_EXTRA) ?: return
        lifecycleScope.launch {
            val photo = DailyRecommendations.get(this@MainActivity).recordPhoto(record)
            if (photo == null) Toast.makeText(this@MainActivity, "这条推荐记录已失效", Toast.LENGTH_LONG).show()
            else dailyOpen.value = DailyOpenRequest(++dailySequence, photo)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveDaily(intent) }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { DailyRecommendations.get(this@MainActivity).reconcile() }
    }
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        val dark = newBase.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("dark", true)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK))
        val model = ViewModelProvider(this)[PhotoModel::class.java]
        receiveDaily(intent)
        setContent {
            val recommendation by dailyOpen.collectAsState()
            val settings by model.settings.state.collectAsState()
            val view = LocalView.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (settings.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val feedback = remember(view, model) { TapFeedback({ model.settings.state.value.haptics }) {
                model.hapticRequests.incrementAndGet()
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            } }
            val colors = remember(settings.dark, settings.dynamicColor) { operationColors(this, settings) }
            val color = colors.background.toArgb()
            SideEffect {
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalTapFeedback provides feedback,
                LocalAppAppearance provides settings
            ) {
                MaterialTheme(colorScheme = colors) {
                    Surface(color = MaterialTheme.colorScheme.background) { PhoterestApp(model, recommendation) }
                }
            }
        }
    }
}

@Composable
private fun AppWindowBars(browser: Boolean) {
    val activity = LocalContext.current as ComponentActivity
    val dark = LocalAppAppearance.current?.dark ?: true
    val color = if (browser) android.graphics.Color.TRANSPARENT else MaterialTheme.colorScheme.background.toArgb()
    SideEffect {
        val bars = if (dark) SystemBarStyle.dark(color) else SystemBarStyle.light(color, color)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoterestApp(model: PhotoModel, dailyOpen: DailyOpenRequest? = null) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profilePage by rememberSaveable { mutableStateOf(false) }
    val motion = remember(scope) { PhotoMotion(scope) }
    val detail = motion.detail
    var actions by remember { mutableStateOf<Photo?>(null) }
    var favorite by remember { mutableStateOf<Photo?>(null) }
    var pendingFavorite by remember { mutableStateOf<Photo?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Photo?>(null) }
    var infoRevision by remember { mutableIntStateOf(0) }
    val viewport = model.viewport(state.selectedAlbum)
    val homeScroll = viewport.grid
    var settingsRoute by rememberSaveable { mutableStateOf<SettingsRoute?>(null) }
    var handledDaily by remember { mutableStateOf<Long?>(null) }
    var scrollRequest by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.reload() }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { infoRevision++ }
    val onboarding = remember(context) { context.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    fun authorize() {
        onboarding.edit().putBoolean("photo_permission_requested", true).apply()
        permission.launch(Gallery.permissions())
    }
    LaunchedEffect(state.ready, state.access) {
        if (state.ready && state.access == Access.NONE && !onboarding.getBoolean("photo_permission_requested", false)) authorize()
    }
    LaunchedEffect(dailyOpen, state.ready, state.busy) {
        if (dailyOpen != null && state.ready && !state.busy && handledDaily != dailyOpen.sequence) {
            handledDaily = dailyOpen.sequence
            settingsRoute = null; actions = null; info = null; favorite = null; showImport = false
            editProfile = false; pendingFavorite = null
            motion.open(dailyOpen.photo, null, fromNotification = true)
        }
    }
    fun refreshHome() { scope.launch { model.reload(true).join(); scrollRequest++ } }
    LaunchedEffect(scrollRequest) { if (scrollRequest > 0) { homeScroll.scrollToItem(0) } }
    fun openSettings() { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    fun collect(photo: Photo) {
        if (state.profileName == null) { pendingFavorite = photo; editProfile = true }
        else favorite = photo
    }
    fun share(photo: Photo) {
        scope.launch {
            if (!state.available(photo)) { model.message("原图无法访问，请检查权限或原相册"); return@launch }
            try { Gallery.share(context, photo) } catch (_: Exception) { model.message("无法分享这张照片，请检查原图是否仍可访问") }
        }
    }
    var openingOriginal by remember { mutableStateOf(false) }
    fun openOriginal(photo: Photo) {
        if (openingOriginal) return
        openingOriginal = true
        scope.launch {
            try { Gallery.openOriginal(context, photo)?.let(model::message) }
            finally { openingOriginal = false }
        }
    }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.message) { state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); model.message(null) } }
    val saveableState = rememberSaveableStateHolder()
    val feedback = LocalTapFeedback.current
    fun sourceAvailable(scene: PhotoDetail): Boolean {
        if (!state.available(scene.photo)) return false
        return when (val origin = scene.source?.origin) {
            is PhotoListOrigin.Album -> !profilePage && state.selectedAlbum == origin.id && state.feed.any { it.uri == scene.photo.uri }
            is PhotoListOrigin.Favorites -> profilePage && state.isFavorite(scene.photo) &&
                (origin.folderId == null || state.memberships.any { it.uri == scene.photo.uri && it.folderId == origin.folderId })
            null -> false
        }
    }
    val sourceCheck by rememberUpdatedState(::sourceAvailable)
    val modalOpen = settingsRoute != null || showImport || actions != null || editProfile || favorite != null || info != null
    val modalVisible by rememberUpdatedState(modalOpen)
    AppWindowBars(browser = detail == null || motion.busy)
    BackHandler(enabled = detail == null && profilePage && !modalOpen) { profilePage = false }
    PredictiveBackHandler(enabled = detail != null && !modalOpen) { events ->
        val scene = motion.detail ?: return@PredictiveBackHandler
        if (modalVisible) return@PredictiveBackHandler
        try {
            events.collect { motion.seekBack(scene, it.progress, sourceCheck(scene)) }
            motion.close(scene, sourceCheck(scene))
        } catch (cancelled: CancellationException) {
            motion.cancelBack(scene)
            throw cancelled
        }
    }
    fun openPhoto(photo: Photo, source: PhotoMotionKey) {
        motion.open(photo, source.takeIf { state.available(photo) })
    }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    OperationTheme {
        Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
            CompositionLocalProvider(LocalPhotoMotion provides motion) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (motion.progress == 1f && motion.flight == null) 0f else 1f }
                    .semantics { if (detail != null) hideFromAccessibility() }) {
                    val bottomBar: @Composable () -> Unit = {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Bottom)
                        ) {
                            NavigationBarItem(
                                selected = !profilePage,
                                onClick = {
                                    feedback.perform()
                                    if (profilePage) profilePage = false else refreshHome()
                                },
                                icon = { Symbol(Glyph.HOME) },
                                label = { Text("主页") },
                                modifier = Modifier.semantics { contentDescription = "主页，刷新照片" }
                            )
                            NavigationBarItem(
                                selected = profilePage,
                                onClick = { feedback.perform(); profilePage = true },
                                icon = {
                                    if (state.avatar == null) Symbol(Glyph.USER)
                                    else Avatar(state.avatar, state.profileName, Modifier.size(24.dp))
                                },
                                label = { Text("个人") },
                                modifier = Modifier.semantics { contentDescription = "个人账号" }
                            )
                        }
                    }
                    if (!profilePage) key(state.selectedAlbum) {
                        HomeScreen(state, viewport, { showImport = true }, { authorize() }, { openSettings() },
                            model::selectAlbum, { refreshHome() }, ::openPhoto, { actions = it }, bottomBar)
                    } else saveableState.SaveableStateProvider("profile") {
                        ProfileScreen(state, { editProfile = true }, { settingsRoute = SettingsRoute.MAIN },
                            ::openPhoto, { actions = it }, model, bottomBar)
                    }
                }
            }
            if (detail != null) key(detail) {
                val photo = detail.photo
                val available = state.available(photo)
                val returnTargetAvailable = sourceAvailable(detail)
                LaunchedEffect(detail, detail.viewport != null, available) {
                    if (detail.viewport != null || !available) motion.enter(detail, available)
                }
                LaunchedEffect(detail, returnTargetAvailable) {
                    if (!returnTargetAvailable) motion.invalidateSource(detail)
                }
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }) {
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = motion.progress }
                        .background(MaterialTheme.colorScheme.background))
                    DetailScreen(detail, motion, available, state.isFavorite(photo),
                        { motion.close(detail, sourceAvailable(detail)) },
                        { if (!motion.busy) collect(photo) }, { if (!motion.busy) share(photo) },
                        { if (!motion.busy) info = photo }, { if (!motion.busy) openOriginal(photo) },
                        if (state.access == Access.FULL) "原图已删除或不可读取" else "照片访问受限，请重新授权",
                        { authorize() })
                }
                motion.flight?.let { PhotoFlightOverlay(it, { motion.progress }, { rootOrigin }) }
            }
        }

        when (settingsRoute) {
            SettingsRoute.MAIN -> SettingsScreen(model.settings, { settingsRoute = null }, { settingsRoute = SettingsRoute.DAILY })
            SettingsRoute.DAILY -> {
                DailySettingsScreen(model, { settingsRoute = SettingsRoute.MAIN }, { authorize() })
            }
            null -> Unit
        }
        if (showImport) ImportSheet(state, { showImport = false }, { authorize() }, { openSettings() }, { ids -> model.importAlbums(ids); showImport = false }, model::unlinkAlbum)
        actions?.let { photo ->
            PhotoActionSheet(photo, state, { actions = null }, {
                actions = null
                share(photo)
            }, {
                actions = null
                collect(photo)
            })
        }
        if (editProfile) ProfileEditor(state, {
            editProfile = false; pendingFavorite = null
        }) { name, avatar, failed ->
            model.saveProfile(name, avatar, onFailure = failed) {
                editProfile = false
                favorite = pendingFavorite
                pendingFavorite = null
            }
        }
        favorite?.let { photo -> FavoriteSheet(photo, state, { favorite = null }, model::createFolder) { ids -> model.saveFavorite(photo, ids) { favorite = null } } }
        info?.let { photo -> InfoSheet(photo, state.available(photo), infoRevision, { info = null }) {
            if (Build.VERSION.SDK_INT >= 29) locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } }
    }
}
