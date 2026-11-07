package com.photerest.app

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

sealed interface PhotoListOrigin {
    data class Album(val id: String?) : PhotoListOrigin
    data class Favorites(val folderId: Long?) : PhotoListOrigin
}

data class PhotoMotionKey(val origin: PhotoListOrigin, val uri: String)

internal data class PhotoDetail(
    val photo: Photo,
    val source: PhotoMotionKey? = null,
    val thumbnail: Bitmap? = null
)

internal val photoEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val photoReturnEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class)
internal class PhotoMotion(
    private val shared: SharedTransitionScope,
    private val visibility: AnimatedVisibilityScope,
    private val opening: Boolean
) {
    @Composable
    fun bounds(key: PhotoMotionKey): Modifier = with(shared) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ ->
                if (opening) tween(360, easing = photoEnterEasing) else tween(300, easing = photoReturnEasing)
            },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            enter = fadeIn(tween(135, delayMillis = if (opening) 40 else 145)),
            // Keep the outgoing photo solid until the incoming photo covers it.
            exit = fadeOut(tween(20, delayMillis = if (opening) 175 else 280))
        )
    }
}

internal val LocalPhotoMotion = compositionLocalOf<PhotoMotion?> { null }

@Composable
internal fun photoBounds(key: PhotoMotionKey?): Modifier {
    val motion = LocalPhotoMotion.current
    return if (key != null && motion != null) motion.bounds(key) else Modifier
}
