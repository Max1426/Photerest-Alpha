package com.photerest.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Current press scale of a bottom navigation item, exposed so instrumentation can assert the animation. */
internal val NavPressScale = SemanticsPropertyKey<Float>("NavPressScale")

/**
 * Shared per-tab spring state. It lives in PhoterestApp so the bounce survives the
 * Home/Profile composable swap that recreates the NavigationBar items.
 */
internal class NavigationBounceState {
    val scale = Animatable(1f)

    suspend fun compress() {
        scale.animateTo(0.86f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1200f))
    }

    suspend fun release(expressive: Boolean) {
        scale.animateTo(
            1f,
            spring(
                dampingRatio = if (expressive) 0.30f else 0.45f,
                stiffness = if (expressive) 520f else 750f,
            ),
        )
    }
}

/**
 * Material3 navigation item with a springy press/release bounce. The scale only affects drawing,
 * so the bar height, insets and the video/photo content layout stay untouched.
 */
@Composable
internal fun RowScope.BouncyNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    bounce: NavigationBounceState,
    description: String,
    label: String,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val expressive = LocalExpressive.current
    val animated = remember { ValueAnimator.areAnimatorsEnabled() }
    val scale by bounce.scale.asState()

    LaunchedEffect(pressed) {
        if (!animated || !ValueAnimator.areAnimatorsEnabled()) {
            bounce.scale.snapTo(1f)
            return@LaunchedEffect
        }
        if (pressed) bounce.compress() else bounce.release(expressive)
    }

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label) },
        interactionSource = interaction,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = description; this[NavPressScale] = scale },
    )
}
