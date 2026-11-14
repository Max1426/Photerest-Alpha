package com.photerest.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private const val BlurScale = 0.35f

internal data class BrowserInsets(val top: Dp, val bottom: Dp, val fade: Dp) {
    val bars get() = PaddingValues(top = top, bottom = bottom)

    fun listPadding(horizontal: Dp) = PaddingValues(
        start = horizontal, end = horizontal, top = top + fade, bottom = bottom + fade
    )
}

private enum class ChromeSlot { Header, Navigation, Content }

@Composable
internal fun BrowserChrome(
    header: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    scrollState: ScrollableState,
    scrollLayout: () -> Any?,
    content: @Composable (BrowserInsets) -> Unit
) {
    val view = LocalView.current
    val blur = Build.VERSION.SDK_INT >= 31 && view.isHardwareAccelerated
    val background = MaterialTheme.colorScheme.background
    val navigationColor = MaterialTheme.colorScheme.surfaceContainer
    val motion = LocalPhotoMotion.current

    SubcomposeLayout(
        Modifier.fillMaxSize().background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .clipToBounds()
    ) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val barConstraints = constraints.copy(minWidth = width, minHeight = 0)
        val bottom = subcompose(ChromeSlot.Navigation) { ChromeControls(navigation) }.single().measure(barConstraints)
        val top = subcompose(ChromeSlot.Header) { ChromeControls(header) }.single()
            .measure(barConstraints.copy(maxHeight = (height - bottom.height).coerceAtLeast(0)))
        val available = height - top.height - bottom.height
        val overlap = 18.dp.roundToPx().coerceAtMost((available - 40.dp.roundToPx()).coerceAtLeast(0) / 2)
        val insets = BrowserInsets(top.height.toDp(), bottom.height.toDp(), overlap.toDp())

        val body = subcompose(ChromeSlot.Content) {
            val source = if (blur) rememberGraphicsLayer() else null
            val topLayer = if (blur) rememberGraphicsLayer() else null
            val bottomLayer = if (blur) rememberGraphicsLayer() else null
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    motion?.browserViewport = bounds.copy(top = bounds.top + top.height, bottom = bounds.bottom - bottom.height)
                }.drawWithCache {
                    val innerFade = 32.dp.toPx()
                    val upper = EdgeBand(insets.top.toPx(), insets.fade.toPx(), innerFade, size.height, top = true)
                    val lower = EdgeBand(insets.bottom.toPx(), insets.fade.toPx(), innerFade, size.height, top = false)
                    val upperTint = upper.gradient(background, edgeOpacity = 0.98f, fadeOpacity = 0.8f)
                    val lowerTint = lower.gradient(navigationColor, edgeOpacity = 0.98f, fadeOpacity = 0.8f)
                    val radius = 20.dp.toPx()
                    val upperMask = topLayer?.let { upper.gradient(Color.Black) }
                    val lowerMask = bottomLayer?.let { lower.gradient(Color.Black) }
                    if (topLayer != null && bottomLayer != null) {
                        val effect = BlurEffect(radius * BlurScale, radius * BlurScale, TileMode.Clamp)
                        topLayer.renderEffect = effect
                        bottomLayer.renderEffect = effect
                    }
                    val maskPaint = Paint()

                    onDrawWithContent {
                        // Re-record glass when the source image moves into the flight overlay.
                        motion?.flight?.key
                        val scrollable = scrollState.canScrollBackward || scrollState.canScrollForward
                        if (source != null && scrollable && size.width > 0f && size.height > 0f) {
                            // Observe lazy layout changes in the draw phase, including on Android 12.
                            scrollLayout()
                            source.record {
                                drawRect(background)
                                this@onDrawWithContent.drawContent()
                            }
                            drawLayer(source)
                            if (topLayer != null && upperMask != null) {
                                drawBandBlur(upper, source, topLayer, upperMask, maskPaint, radius)
                            }
                            if (bottomLayer != null && lowerMask != null) {
                                drawBandBlur(lower, source, bottomLayer, lowerMask, maskPaint, radius)
                            }
                        } else {
                            drawContent()
                        }
                        drawRect(upperTint)
                        drawRect(lowerTint)
                    }
                }
            ) { content(insets) }
        }.single().measure(Constraints.fixed(width, height))

        layout(width, height) {
            body.place(0, 0)
            top.place(0, 0)
            bottom.place(0, height - bottom.height)
        }
    }
}

@Composable
private fun ChromeControls(content: @Composable () -> Unit) {
    // Include the empty parts of the bar in hit testing so taps cannot open covered photos.
    Box(Modifier.fillMaxWidth().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    }) { content() }
}

private class EdgeBand(barHeight: Float, overlap: Float, innerFade: Float, height: Float, private val top: Boolean) {
    private val extent = (barHeight + overlap).coerceIn(0f, height)
    private val fadeStart = (barHeight - innerFade).coerceIn(0f, extent)
    val start = if (top) 0f else height - extent
    val end = if (top) extent else height
    val hasArea = extent >= 1f && barHeight > 0f

    fun gradient(color: Color, edgeOpacity: Float = 1f, fadeOpacity: Float = edgeOpacity): Brush {
        if (!hasArea) return Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
        val fadeFraction = fadeStart / extent
        val stops = buildList {
            if (fadeStart > 0f) {
                // Keep some of the blurred photo visible behind the controls.
                for (index in 0 until 8) {
                    val position = index / 8f
                    val opacity = edgeOpacity + (fadeOpacity - edgeOpacity) * smoothStep(position)
                    add(position * fadeFraction to color.copy(alpha = opacity))
                }
            }
            for (index in 0..16) {
                val position = index / 16f
                val strength = 1f - smoothStep(position)
                add((fadeFraction + position * (1f - fadeFraction)) to color.copy(alpha = fadeOpacity * strength))
            }
        }
        return Brush.verticalGradient(
            *stops.toTypedArray(),
            startY = if (top) start else end,
            endY = if (top) end else start
        )
    }

    private fun smoothStep(value: Float) = value * value * (3f - 2f * value)
}

private fun DrawScope.drawBandBlur(
    band: EdgeBand, source: GraphicsLayer, layer: GraphicsLayer, mask: Brush, maskPaint: Paint, radius: Float
) {
    if (!band.hasArea) return
    // Keep the mask transparent past the fade before clipping, with room for the blur kernel.
    val outset = ceil(radius * 3f).toInt()
    val sampleTop = (band.start.toInt() - outset).coerceAtLeast(0)
    val sampleEnd = (ceil(band.end).toInt() + outset).coerceAtMost(size.height.toInt())
    val sampleSize = IntSize(
        ceil(size.width * BlurScale).toInt(),
        ceil((sampleEnd - sampleTop) * BlurScale).toInt()
    )
    val bounds = Rect(0f, sampleTop.toFloat(), size.width, sampleEnd.toFloat())
    val canvas = drawContext.canvas

    layer.record(size = sampleSize) {
        scale(BlurScale, BlurScale, pivot = Offset.Zero) {
            translate(top = -sampleTop.toFloat()) { drawLayer(source) }
        }
    }
    clipRect(top = bounds.top, bottom = bounds.bottom) {
        canvas.saveLayer(bounds, maskPaint)
        translate(top = bounds.top) {
            scale(1f / BlurScale, 1f / BlurScale, pivot = Offset.Zero) { drawLayer(layer) }
        }
        drawRect(mask, bounds.topLeft, bounds.size, blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}
