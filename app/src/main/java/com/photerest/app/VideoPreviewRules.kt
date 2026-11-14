package com.photerest.app

import java.util.Locale
import kotlin.math.abs

/**
 * Plain geometry and policy for picking the single muted video preview.
 * Kept free of Compose/Android types so the rule can be unit tested on the JVM.
 */
internal data class PreviewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    fun intersect(other: PreviewRect): PreviewRect {
        val left = maxOf(this.left, other.left)
        val top = maxOf(this.top, other.top)
        val right = minOf(this.right, other.right)
        val bottom = minOf(this.bottom, other.bottom)
        return if (right <= left || bottom <= top) PreviewRect(left, top, left, top) else PreviewRect(left, top, right, bottom)
    }
}

internal object VideoPreviewRules {
    const val MinimumVisibleFraction = .6f

    /**
     * Index of the card that should preview: it must show at least 60% of its own area inside the
     * viewport, and the closest card centre to the viewport centre (vertical distance, like the
     * classic implementation) wins.
     */
    fun choose(frames: List<PreviewRect>, viewport: PreviewRect, minVisibleFraction: Float = MinimumVisibleFraction): Int? {
        if (frames.isEmpty() || viewport.width <= 0f || viewport.height <= 0f) return null
        var chosen: Int? = null
        var chosenDistance = Float.MAX_VALUE
        frames.forEachIndexed { index, frame ->
            if (frame.width <= 0f || frame.height <= 0f) return@forEachIndexed
            val visible = frame.intersect(viewport)
            if (visible.area < frame.area * minVisibleFraction) return@forEachIndexed
            val distance = abs(frame.centerY - viewport.centerY)
            if (distance < chosenDistance) {
                chosen = index
                chosenDistance = distance
            }
        }
        return chosen
    }

    fun videoTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
