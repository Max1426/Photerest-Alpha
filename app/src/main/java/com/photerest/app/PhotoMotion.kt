package com.photerest.app

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface PhotoListOrigin {
    data class Album(val id: String?) : PhotoListOrigin
    data class Favorites(val folderId: Long?) : PhotoListOrigin
}

data class PhotoMotionKey(val origin: PhotoListOrigin, val uri: String)

internal class PhotoCardSource {
    var coordinates: LayoutCoordinates? = null
    var bitmap: Bitmap? = null
}

internal data class PhotoFrame(val image: Rect, val clip: Rect, val roundedCard: Rect? = null)
internal data class PhotoFlight(
    val key: PhotoMotionKey,
    val thumbnail: Bitmap,
    val bitmap: Bitmap,
    val card: PhotoFrame,
    val detail: PhotoFrame
)

internal class PhotoDetail(val photo: Photo, val source: PhotoMotionKey?, val thumbnail: Bitmap?) {
    var viewport: Rect? by mutableStateOf(null)
    var scale by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var bitmap: Bitmap? by mutableStateOf(null)

    fun frame(): PhotoFrame? {
        val viewport = viewport ?: return null
        if (viewport.width <= 0 || viewport.height <= 0) return null
        val aspect = photo.aspect
        val fitWidth = minOf(viewport.width, viewport.height * aspect)
        val fitHeight = minOf(viewport.height, viewport.width / aspect)
        val width = fitWidth * scale
        val height = fitHeight * scale
        val left = viewport.center.x - width / 2f + pan.x
        val top = viewport.center.y - height / 2f + pan.y
        val image = Rect(left, top, left + width, top + height)
        val visible = image.intersect(viewport)
        return if (visible.isEmpty) null else PhotoFrame(image, visible)
    }
}

internal val LocalPhotoMotion = compositionLocalOf<PhotoMotion?> { null }

internal class PhotoMotion(private val scope: CoroutineScope) {
    private val cards = mutableMapOf<PhotoMotionKey, PhotoCardSource>()
    private val enterEasing = CubicBezierEasing(.05f, .7f, .1f, 1f)
    private val returnEasing = CubicBezierEasing(.4f, 0f, .2f, 1f)
    private var animation: Job? = null
    private var opened = false
    private var closing = false
    private var seeking = false
    private var seekStart = 1f
    var browserViewport: Rect? = null
    var detail by mutableStateOf<PhotoDetail?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var flight by mutableStateOf<PhotoFlight?>(null)
        private set

    val busy get() = detail != null && (progress < 1f || flight != null)
    val chromeAlpha get() = ((progress - .65f) / .35f).coerceIn(0f, 1f)

    fun track(key: PhotoMotionKey, card: PhotoCardSource) {
        cards[key] = card
    }

    fun untrack(key: PhotoMotionKey, card: PhotoCardSource) {
        if (cards[key] === card) cards.remove(key)
    }

    fun open(photo: Photo, source: PhotoMotionKey?, fromNotification: Boolean = false) {
        if (detail != null && !fromNotification) return
        animation?.cancel()
        val thumbnail = cards[source]?.bitmap
        progress = 0f
        flight = null
        opened = false
        closing = false
        seeking = false
        detail = PhotoDetail(photo, source, thumbnail)
        if (!animationsEnabled()) {
            opened = true
            progress = 1f
        }
    }

    fun enter(scene: PhotoDetail, available: Boolean) {
        if (scene !== detail || opened || closing || seeking) return
        opened = true
        flight = scene.thumbnail?.takeIf { available }?.let { flight(scene, it) }
        settle(1f, if (flight != null) 360 else 160, enterEasing)
    }

    fun seekBack(scene: PhotoDetail, fraction: Float, sourceAvailable: Boolean) {
        if (scene !== detail || closing || !animationsEnabled()) return
        if (seeking && !sourceAvailable) {
            flight = null
            return
        }
        if (seeking && flight == null) return
        if (!seeking) {
            val current = if (sourceAvailable) flight ?: flight(scene, scene.bitmap ?: scene.thumbnail) else null
            if (current == null) return
            animation?.cancel()
            flight = current
            seekStart = progress
            seeking = true
        }
        progress = seekStart * (1f - fraction.coerceIn(0f, 1f))
    }

    fun cancelBack(scene: PhotoDetail) {
        if (scene !== detail || !seeking) return
        seeking = false
        opened = true
        settle(1f, 220, enterEasing)
    }

    fun close(scene: PhotoDetail, sourceAvailable: Boolean) {
        if (scene !== detail || closing) return
        closing = true
        seeking = false
        if (!animationsEnabled()) {
            showList()
            return
        }
        val destination = if (sourceAvailable) flight(scene, scene.bitmap ?: scene.thumbnail) else null
        flight = destination?.let { flight ?: it.takeIf { progress > 0f } }
        val duration = if (flight != null) 300 else 150
        settle(0f, (duration * progress).roundToInt(), returnEasing)
    }

    fun invalidateSource(scene: PhotoDetail) {
        if (scene === detail) flight = null
    }

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled()

    private fun showList() {
        animation?.cancel()
        progress = 0f
        flight = null
        detail = null
    }

    private fun settle(target: Float, duration: Int, easing: CubicBezierEasing) {
        animation?.cancel()
        animation = scope.launch {
            animate(progress, target, animationSpec = tween(duration, easing = easing)) { value, _ ->
                progress = value
            }
            flight = null
            if (target == 0f) detail = null
        }
    }

    private fun flight(scene: PhotoDetail, bitmap: Bitmap?): PhotoFlight? {
        val key = scene.source ?: return null
        val cardSource = cards[key] ?: return null
        val coordinates = cardSource.coordinates?.takeIf { it.isAttached && it.size.width > 0 && it.size.height > 0 } ?: return null
        val detailBitmap = bitmap ?: cardSource.bitmap ?: return null
        val thumbnail = cardSource.bitmap ?: return null
        val detail = scene.frame() ?: return null
        val viewport = browserViewport ?: return null
        val clip = coordinates.boundsInRoot().intersect(viewport)
        val origin = coordinates.positionInRoot()
        val card = Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        if (clip.isEmpty || clip.bottom <= clip.top) return null
        val cover = maxOf(card.width / thumbnail.width, card.height / thumbnail.height)
        val width = thumbnail.width * cover
        val height = thumbnail.height * cover
        val image = Rect(card.center.x - width / 2f, card.center.y - height / 2f, card.center.x + width / 2f, card.center.y + height / 2f)
        return PhotoFlight(key, thumbnail, detailBitmap, PhotoFrame(image, clip, card), detail)
    }
}

@Composable
internal fun PhotoFlightOverlay(flight: PhotoFlight, progress: () -> Float, rootOrigin: () -> Offset) {
    val image = remember(flight.bitmap) { flight.bitmap.asImageBitmap() }
    val thumbnail = remember(flight.thumbnail) { flight.thumbnail.asImageBitmap() }
    Canvas(Modifier.fillMaxSize().testTag("photo-flight")) {
        val fraction = progress()
        val origin = rootOrigin()
        fun blend(start: Float, end: Float): Float = start + (end - start) * fraction
        fun interpolate(start: Rect, end: Rect) = Rect(
            blend(start.left, end.left), blend(start.top, end.top),
            blend(start.right, end.right), blend(start.bottom, end.bottom)
        ).translate(-origin)
        val clip = interpolate(flight.card.clip, flight.detail.clip)
        val bounds = interpolate(flight.card.image, flight.detail.image)
        val cornerBounds = interpolate(flight.card.roundedCard ?: flight.card.clip, flight.detail.clip)
        val radius = CornerRadius(12.dp.toPx() * (1f - fraction))
        val path = Path().apply { addRoundRect(RoundRect(cornerBounds, topLeft = radius, topRight = radius)) }
        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            clipPath(path) {
                val offset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
                val size = IntSize(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1))
                drawImage(if (fraction > 0.18f) image else thumbnail, dstOffset = offset, dstSize = size)
            }
        }
    }
}
