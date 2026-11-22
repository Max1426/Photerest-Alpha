@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.photerest.app

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

internal val LocalVideoPlayback = staticCompositionLocalOf<VideoPlayback?> { null }

/**
 * One shared ExoPlayer for the whole app: the grid preview and the detail screen exchange the same
 * engine and only the rendered surface moves. Preview playback is always muted and never touches
 * audio focus; the detail screen owns sound while it is foreground.
 */
internal class VideoPlayback(private val context: Context) {
    private data class Card(val owner: PhotoCardSource, val photo: Photo, val coordinates: LayoutCoordinates)

    private val cards = mutableMapOf<PhotoMotionKey, Card>()
    private var engine: ExoPlayer? = null
    private var surface: PlayerView? = null
    private data class SurfaceOwner(val uri: String, val revision: Long, val key: PhotoMotionKey?, val preview: Boolean)
    private var surfaceOwner: SurfaceOwner? = null
    private var frameListener: AnalyticsListener? = null
    private var bindingGeneration = 0L
    private var mediaGeneration = 0L
    private var mediaId = ""
    private var source: PhotoMotionKey? = null
    private var resume: Pair<PhotoMotionKey, Long>? = null
    private var current: Photo? = null
    private var foreground = false
    private var wantsPlay = true
    private var detailReady = false
    private var detailRevealed = true

    var viewport: Rect? = null
    var detail by mutableStateOf<Photo?>(null)
        private set
    var preview by mutableStateOf<PhotoMotionKey?>(null)
        private set
    var firstFrame by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    val position get() = engine?.currentPosition ?: 0L
    val duration get() = engine?.duration?.takeIf { it > 0 } ?: detail?.durationMs ?: 0L

    private fun player(): ExoPlayer = engine ?: ExoPlayer.Builder(context).build().also { player ->
        engine = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) { ended = playbackState == Player.STATE_ENDED }

        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (eventMatches(eventTime)) failed = true
            }
        })
    }

    private fun eventMatches(event: AnalyticsListener.EventTime): Boolean {
        if (mediaId.isEmpty() || event.windowIndex !in 0 until event.timeline.windowCount) return false
        return event.timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem.mediaId == mediaId
    }

    private fun samePhoto(left: Photo?, right: Photo) = left?.uri == right.uri && left.added == right.added

    private fun owns(photo: Photo, key: PhotoMotionKey?, previewMode: Boolean): Boolean =
        samePhoto(current, photo) && if (previewMode) detail == null && preview == key && key != null
        else samePhoto(detail, photo) && source == key

    fun hasFrame(photo: Photo, key: PhotoMotionKey?, previewMode: Boolean): Boolean =
        firstFrame && !failed && owns(photo, key, previewMode) &&
            surfaceOwner == SurfaceOwner(photo.uri, photo.added, key, previewMode) &&
            surface?.player === engine && engine?.currentMediaItem?.mediaId == mediaId

    private fun audio(player: ExoPlayer, enabled: Boolean, focus: Boolean) {
        player.volume = if (enabled) 1f else 0f
        player.setAudioAttributes(AudioAttributes.DEFAULT, focus)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enabled).build()
    }

    private fun load(photo: Photo, position: Long): ExoPlayer {
        val player = player()
        if (current?.uri != photo.uri || current?.added != photo.added || failed) {
            current = photo
            firstFrame = false
            failed = false
            ended = false
            surface?.let { it.animate().cancel(); it.alpha = 0f }
            mediaId = "${photo.uri}:${photo.added}:${++mediaGeneration}"
            player.setMediaItem(MediaItem.Builder().setUri(Uri.parse(photo.uri)).setMediaId(mediaId).build(), position)
            player.prepare()
        }
        return player
    }

    fun track(key: PhotoMotionKey, owner: PhotoCardSource, photo: Photo, coordinates: LayoutCoordinates) {
        cards[key] = Card(owner, photo, coordinates)
    }

    fun untrack(key: PhotoMotionKey, owner: PhotoCardSource) {
        if (cards[key]?.owner === owner) cards.remove(key)
    }

    /**
     * Picks and starts the single muted preview. Called from a short polling loop while the grid is
     * foreground; [viewport] arrives from BrowserChrome and excludes the bar areas.
     */
    fun updatePreview(profilePage: Boolean, album: String?) {
        val visible = viewport ?: return
        if (!foreground || detail != null) return
        val entries = cards.entries.toList()
        val candidates = entries.mapNotNull { (key, card) ->
            if (!card.coordinates.isAttached) return@mapNotNull null
            val matches = when (val origin = key.origin) {
                is PhotoListOrigin.Album -> !profilePage && origin.id == album
                is PhotoListOrigin.Favorites -> profilePage
                PhotoListOrigin.Search -> false
            }
            if (!matches) return@mapNotNull null
            val bounds = card.coordinates.boundsInRoot()
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            Triple(key, card.photo, PreviewRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
        }
        val chosenIndex = VideoPreviewRules.choose(candidates.map { it.third }, PreviewRect(visible.left, visible.top, visible.right, visible.bottom))
        if (chosenIndex == null) {
            stopPreview()
            return
        }
        val (key, photo) = candidates[chosenIndex]
        if (preview == key && current?.added == photo.added) return
        stopPreview()
        preview = key
        val position = resume?.takeIf { it.first == key }?.second ?: 0L
        if (resume?.first == key) resume = null
        val player = player()
        audio(player, enabled = false, focus = false)
        player.repeatMode = Player.REPEAT_MODE_ONE
        load(photo, position).play()
        android.util.Log.i("VideoPlayback", "preview started key=${photo.name} position=$position")
    }

    fun stopPreview() {
        if (preview == null) return
        preview = null
        resetMedia()
        android.util.Log.i("VideoPlayback", "preview stopped")
    }

    fun open(photo: Photo, key: PhotoMotionKey, available: Boolean, startPaused: Boolean = false) {
        val fromPreview = preview == key && samePhoto(current, photo) && !failed
        val start = when {
            fromPreview -> position
            resume?.first == key -> resume?.second ?: 0L
            else -> 0L
        }
        if (available && fromPreview) preview = null else stopPreview()
        resume = null
        detailReady = false
        detail = photo
        source = key
        muted = false
        wantsPlay = true
        // Entering keeps the player on the exact frame used by the shared-element flight; the
        // detail is revealed (and playback starts) once the expand animation has settled.
        detailRevealed = !startPaused
        setDetailAvailable(available, start)
    }

    fun setDetailRevealed(revealed: Boolean) {
        if (detailRevealed == revealed) return
        detailRevealed = revealed
        applyDetailPlayback()
    }

    /**
     * Bitmap of the frame currently shown by the bound TextureView. Used to start the shared-element
     * transition from the exact frame the user was looking at, instead of a stale cover thumbnail.
     */
    fun captureFrame(photo: Photo, key: PhotoMotionKey?): Bitmap? {
        if (!hasFrame(photo, key, previewMode = detail == null)) return null
        val view = surface?.videoSurfaceView as? android.view.TextureView ?: return null
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        return runCatching { view.bitmap }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    }

    fun setDetailAvailable(available: Boolean, start: Long = 0L) {
        val photo = detail ?: return
        if (available == detailReady) return
        detailReady = available
        if (available) {
            player().repeatMode = Player.REPEAT_MODE_OFF
            load(photo, start)
            applyDetailPlayback()
        } else resetMedia()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            preview?.let { resume = it to position }
            stopPreview()
        }
        applyDetailPlayback()
    }

    private fun applyDetailPlayback() {
        if (detail == null || !detailReady) return
        val player = engine ?: return
        val active = foreground && wantsPlay && detailRevealed
        if (!active) player.pause()
        audio(player, enabled = foreground && !muted, focus = active && !muted)
        if (active) player.play()
    }

    fun close() {
        if (detail == null) return
        if (detailReady) resume = source?.let { it to if (ended) 0L else position }
        detail = null
        detailReady = false
        source = null
        resetMedia()
    }

    fun togglePlay() {
        val photo = detail ?: return
        when {
            failed -> { wantsPlay = true; load(photo, position) }
            ended -> { wantsPlay = true; seek(0) }
            else -> wantsPlay = !wantsPlay
        }
        applyDetailPlayback()
    }

    fun toggleMute() {
        muted = !muted
        applyDetailPlayback()
    }

    fun seek(position: Long) { engine?.seekTo(position.coerceIn(0L, duration)) }

    fun bind(view: PlayerView, photo: Photo, key: PhotoMotionKey?, previewMode: Boolean): Boolean {
        if (!owns(photo, key, previewMode)) return false
        val owner = SurfaceOwner(photo.uri, photo.added, key, previewMode)
        if (surface === view && surfaceOwner == owner) return true
        surface?.let(::unbind)
        val generation = ++bindingGeneration
        firstFrame = false
        surface = view
        surfaceOwner = owner
        view.animate().cancel()
        view.alpha = 0f
        val player = player()
        val listener = object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (generation == bindingGeneration && surface === view && surfaceOwner == owner &&
                    owns(photo, key, previewMode) && eventMatches(eventTime)) firstFrame = true
            }
        }
        frameListener = listener
        player.addAnalyticsListener(listener)
        view.player = player
        return true
    }

    fun unbind(view: PlayerView) {
        if (surface !== view) return
        ++bindingGeneration
        frameListener?.let { engine?.removeAnalyticsListener(it) }
        frameListener = null
        view.animate().cancel()
        view.alpha = 0f
        view.player = null
        surface = null
        surfaceOwner = null
        firstFrame = false
    }

    private fun resetMedia() {
        surface?.let(::unbind)
        mediaId = ""
        engine?.let { player ->
            player.pause()
            audio(player, enabled = false, focus = false)
            player.stop()
            player.clearMediaItems()
        }
        current = null
        ended = false
        failed = false
        firstFrame = false
    }

    fun release() {
        surface?.let(::unbind)
        engine?.release()
        engine = null
    }
}

@SuppressLint("InflateParams")
@Composable
private fun VideoSurface(playback: VideoPlayback, photo: Photo, source: PhotoMotionKey?, preview: Boolean) {
    val frameReady = playback.hasFrame(photo, source, preview)
    val background = MaterialTheme.colorScheme.background.toArgb()
    val playbackTag = when {
        preview && frameReady && playback.playing -> "video-preview-playing"
        !preview && playback.muted -> "video-detail-audio-off"
        !preview -> "video-detail-audio-on"
        else -> "video-preview-buffering"
    }
    Box(Modifier.fillMaxSize().testTag(playbackTag)) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.local_video_surface, null, false) as PlayerView).apply {
                    resizeMode = if (preview) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(false)
                    setShutterBackgroundColor(background)
                    setBackgroundColor(background)
                    alpha = 0f
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize().testTag(if (preview) "video-preview-active" else "video-detail-surface"),
            onRelease = playback::unbind,
            update = { view ->
                val bound = playback.bind(view, photo, source, preview)
                view.setShutterBackgroundColor(background)
                view.setBackgroundColor(background)
                val targetAlpha = if (bound && frameReady) 1f else 0f
                if (view.alpha != targetAlpha) {
                    view.animate().cancel()
                    if (targetAlpha == 1f) view.animate().alpha(1f).setDuration(160).start()
                    else view.alpha = 0f
                }
            }
        )
    }
}

/** Cover frame plus the muted preview surface and the always-visible duration badge. */
@Composable
internal fun VideoPreview(photo: Photo, source: PhotoMotionKey, onBitmap: ((android.graphics.Bitmap?) -> Unit)? = null) {
    val playback = LocalVideoPlayback.current
    Box(Modifier.fillMaxSize()) {
        PhotoImage(photo.uri, Modifier.fillMaxSize(), revision = photo.added, video = true, onBitmap = onBitmap)
        if (playback != null && playback.preview == source) {
            key(source) { VideoSurface(playback, photo, source, preview = true) }
        }
        Text("▶  ${if (photo.durationMs > 0) VideoPreviewRules.videoTime(photo.durationMs) else "视频"}",
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).testTag("video-duration")
                .background(Color.Black.copy(alpha = .74f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
internal fun VideoDetailScreen(
    scene: PhotoDetail,
    playback: VideoPlayback,
    motion: PhotoMotion,
    available: Boolean,
    access: Access,
    favorite: Boolean,
    back: () -> Unit,
    share: () -> Unit,
    collect: () -> Unit,
    openOriginal: () -> Unit,
    authorize: () -> Unit,
) {
    val photo = scene.photo
    var position by remember(photo.uri) { mutableLongStateOf(playback.position) }
    var duration by remember(photo.uri) { mutableLongStateOf(playback.duration) }
    var scrub by remember(photo.uri) { mutableStateOf<Float?>(null) }
    LaunchedEffect(available, photo.uri) {
        while (available) {
            position = playback.position
            duration = playback.duration
            delay(200)
        }
    }
    Column(Modifier.fillMaxSize().testTag("video-detail")
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 12.dp)
            .graphicsLayer { alpha = motion.chromeAlpha }, verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.BACK, "返回视频流", action = back)
            Text(photo.name, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            if (available) GlyphButton(Glyph.SHARE, "分享视频", action = share)
        }
        Box(Modifier.fillMaxWidth().weight(1f)
            .onGloballyPositioned { scene.viewport = it.boundsInRoot() }, contentAlignment = Alignment.Center) {
            // Hide the full-screen frame during the expand/shrink flight; the flight layer draws the
            // captured card frame. Once settled, the live surface blends in over the cover.
            val showContent = motion.flight == null && !motion.busy
            // Keep the opaque playback/letterbox background INSIDE the visibility layer.
            // A background outside graphicsLayer remains visible while the video shrinks away,
            // masking the restored grid with a full-viewport rectangle during predictive back.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (showContent) 1f else 0f }
                .background(MaterialTheme.colorScheme.background)) {
                if (available) {
                    if (!playback.hasFrame(photo, scene.source, previewMode = false)) {
                        PhotoImage(photo.uri, Modifier.fillMaxSize().testTag("video-detail-cover"), revision = photo.added, scale = ContentScale.Fit,
                            video = true, thumbnail = scene.thumbnail, preferThumbnail = scene.thumbnail != null)
                    }
                    VideoSurface(playback, photo, scene.source, preview = false)
                    if (playback.failed) Text("无法播放此视频", Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(12.dp))
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            if (access == Access.FULL) "原视频已删除或不可读取" else "视频访问受限",
                            "请检查系统相册或视频访问权限。",
                            if (access == Access.FULL) null else "授权视频访问",
                            authorize
                        )
                    }
                }
            }
        }
        Column(Modifier.graphicsLayer { alpha = motion.chromeAlpha }) {
            if (available) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Slider(
                        value = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { scrub = it },
                        onValueChangeFinished = { scrub?.let { playback.seek((it * duration).toLong()) }; scrub = null },
                        enabled = duration > 0 && !playback.failed,
                        modifier = Modifier.testTag("video-seek")
                    )
                    Text("${VideoPreviewRules.videoTime(scrub?.let { (it * duration).toLong() } ?: position)} / ${VideoPreviewRules.videoTime(duration)}",
                        fontSize = 13.sp, color = Muted)
                }
                Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    TapTextButton(onClick = playback::togglePlay) {
                        Text(when { playback.failed -> "重试"; playback.ended -> "重播"; playback.playing -> "暂停"; else -> "播放" })
                    }
                    TapTextButton(onClick = playback::toggleMute) { Text(if (playback.muted) "取消静音" else "静音") }
                    GlyphButton(Glyph.IMAGE_UP, "在相册中打开视频", action = openOriginal)
                    Spacer(Modifier.weight(1f))
                    TapButton(onClick = collect) { Text(if (favorite) "管理收藏" else "收藏") }
                }
            }
        }
    }
}
