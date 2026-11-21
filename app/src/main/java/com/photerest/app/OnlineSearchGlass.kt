package com.photerest.app

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Contains only the photo/letterbox, never the FAB or its icon. */
internal class OnlineBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Unspecified)
    var size by mutableStateOf(IntSize.Zero)
    var revision by mutableIntStateOf(0)
    fun invalidate() = Snapshot.withoutReadObservation { revision++ }
}

@Composable
internal fun rememberOnlineBackdrop(enabled: Boolean): OnlineBackdrop? {
    if (!enabled || Build.VERSION.SDK_INT < 31 || !LocalView.current.isHardwareAccelerated) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { OnlineBackdrop(layer) }
}

@Composable
internal fun OnlinePhotoBackdrop(backdrop: OnlineBackdrop?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val capture = if (backdrop == null) Modifier else Modifier.graphicsLayer()
        .onGloballyPositioned { backdrop.origin = it.positionInRoot(); backdrop.size = it.size }
        .drawWithContent {
            backdrop.layer.record {
                drawRect(background)
                this@drawWithContent.drawContent()
            }
            backdrop.invalidate()
            drawLayer(backdrop.layer)
        }
    Box(modifier.then(capture), content = content)
}

@Composable
private fun Modifier.onlineGlass(backdrop: OnlineBackdrop?, radiusDp: Int, tint: Color, shape: Shape): Modifier {
    val clipped = clip(shape)
    if (backdrop == null) return clipped.drawWithCache { onDrawBehind { drawRect(tint) } }
    val sample = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Unspecified) }
    val background = MaterialTheme.colorScheme.background
    return clipped.onGloballyPositioned { position = it.positionInRoot() }.graphicsLayer().drawWithCache {
        val radius = radiusDp.dp.toPx()
        val inset = ceil(radius * 3f + 2f).toInt()
        val sampleSize = IntSize(ceil(size.width).toInt() + inset * 2, ceil(size.height).toInt() + inset * 2)
        val origin = backdrop.origin
        val relative = if (position != Offset.Unspecified && origin != Offset.Unspecified) position - origin else Offset.Zero
        sample.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
        var lastRevision = -1
        onDrawBehind {
            val revision = backdrop.revision
            if (position != Offset.Unspecified && origin != Offset.Unspecified && backdrop.size != IntSize.Zero && size.width > 0 && size.height > 0) {
                // Android 12 can retain a cached blur when a child image transform changes.
                if (Build.VERSION.SDK_INT == 31 && radius > 0 && revision != lastRevision) {
                    sample.renderEffect = null
                    sample.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                }
                lastRevision = revision
                sample.record(size = sampleSize) {
                    drawRect(background)
                    translate(inset - relative.x, inset - relative.y) { drawLayer(backdrop.layer) }
                }
                translate(-inset.toFloat(), -inset.toFloat()) { drawLayer(sample) }
            }
            drawRect(tint)
        }
    }
}

/** The same Material surface and backdrop pipeline is used in settings and on the photo. */
@Composable
internal fun OnlineSearchFab(settings: AppSettings, backdrop: OnlineBackdrop?, modifier: Modifier = Modifier, action: () -> Unit) {
    OnlineTheme {
        val color = MaterialTheme.colorScheme.secondaryContainer
        val supported = Build.VERSION.SDK_INT >= 31 && LocalView.current.isHardwareAccelerated
        val glass = settings.onlineSearchGlass && supported && backdrop != null
        val shape = FloatingActionButtonDefaults.shape
        val surface = if (glass) Modifier.onlineGlass(backdrop, settings.onlineSearchBlurDp, color.copy(alpha = .58f), shape) else Modifier
        val feedback = LocalTapFeedback.current
        FloatingActionButton(
            onClick = { feedback.perform(); action() },
            modifier = modifier.size(56.dp).then(surface).semantics { contentDescription = "使用 Google 联网搜图" },
            shape = shape,
            containerColor = if (glass) Color.Transparent else color,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
        ) { Symbol(Glyph.SEARCH, Modifier.size(24.dp)) }
    }
}
