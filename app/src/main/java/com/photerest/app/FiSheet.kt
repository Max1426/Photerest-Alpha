package com.photerest.app

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// Also used by the frame-by-frame regression checks; no extra window or system-bar writer.
internal val FiSheetOffset = SemanticsPropertyKey<Float>("FiSheetOffset")
internal val FiSheetViewport = SemanticsPropertyKey<Float>("FiSheetViewport")
internal val FiSheetPhase = SemanticsPropertyKey<String>("FiSheetPhase")

private enum class DrawerPhase { Opening, Idle, Dragging, Settling, Closing }

/** One animation owner. Opening/closing move a premeasured layout, never resize its children. */
@Stable
private class FiDrawerMotion(val height: Float, val half: Float, val expanded: Float) {
    var offset by mutableFloatStateOf(height)
        private set
    var phase by mutableStateOf(DrawerPhase.Opening)
        private set
    private var frozenHeight = height - half
    private var job: Job? = null
    private val animation = Animatable(height)
    val closing get() = phase == DrawerPhase.Closing
    val dragging get() = phase == DrawerPhase.Dragging
    val viewport: Float get() = when (phase) {
        DrawerPhase.Opening -> height - half
        DrawerPhase.Closing -> frozenHeight
        else -> (height - offset).coerceIn(height - half, height - expanded)
    }
    private suspend fun animate(target: Float, duration: Int) {
        if (!ValueAnimator.areAnimatorsEnabled()) { offset = target; return }
        animation.snapTo(offset)
        animation.animateTo(target, tween(duration, easing = FastOutSlowInEasing)) { offset = value }
    }
    fun open(scope: CoroutineScope) {
        job = scope.launch { animate(half,360); phase = DrawerPhase.Idle }
    }
    fun close(scope: CoroutineScope, after: () -> Unit) {
        if (closing) return
        frozenHeight = viewport
        job?.cancel()
        phase = DrawerPhase.Closing
        job = scope.launch { animate(height,280); after() }
    }
    fun beginDrag() {
        if (closing) return
        job?.cancel()
        phase = DrawerPhase.Dragging
    }
    fun drag(delta: Float): Float {
        if (closing) return 0f
        if (!dragging) beginDrag()
        val before = offset
        offset = (offset + delta).coerceIn(expanded,height)
        return offset - before
    }
    fun settle(scope: CoroutineScope, velocity: Float, dismiss: () -> Unit) {
        if (closing) return
        val anchors = listOf(expanded,half,height)
        val target = when {
            velocity > 900f -> anchors.firstOrNull { it > offset + 1f } ?: height
            velocity < -900f -> anchors.lastOrNull { it < offset - 1f } ?: expanded
            else -> anchors.minBy { abs(it-offset) }
        }
        move(scope,target,dismiss)
    }
    fun move(scope: CoroutineScope, target: Float, dismiss: () -> Unit) {
        if (closing) return
        if (target == height) { close(scope,dismiss); return }
        job?.cancel()
        phase = DrawerPhase.Settling
        job = scope.launch { animate(target,300); phase = DrawerPhase.Idle }
    }
}

/** Material drawer hosted in the caller's existing window. Never adds a Dialog/Popup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FiSheet(tag: String, dismiss: () -> Unit,
    content: @Composable ColumnScope.(close: (after: () -> Unit) -> Unit) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("$tag-host")) {
        val density = LocalDensity.current
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val top = maxOf(WindowInsets.safeDrawing.getTop(density).toFloat(),height*.06f)
            .coerceAtMost(height*.45f)
        val state = remember(height,top) { FiDrawerMotion(height,height*.5f,top) }
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        val currentDismiss by rememberUpdatedState(dismiss)
        val close: (after: () -> Unit) -> Unit = { after -> state.close(scope) { currentDismiss(); after() } }
        LaunchedEffect(state) { state.open(scope); focus.requestFocus() }
        BackHandler {
            if (state.offset < state.half-1f) state.move(scope,state.half) { currentDismiss() }
            else close {}
        }
        val scrim = MaterialTheme.colorScheme.scrim
        Box(Modifier.matchParentSize().drawBehind {
            val alpha = ((height-state.offset)/(height-state.half)).coerceIn(0f,1f)*.32f
            drawRect(scrim.copy(alpha=alpha))
        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = "关闭 φ 智能面板") { close {} }.testTag("$tag-scrim"))

        val nested = remember(state,scope) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || state.closing) return Offset.Zero
                return if (available.y < 0 && state.offset > state.expanded) Offset(0f,state.drag(available.y)) else Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || state.closing || available.y == 0f) return Offset.Zero
                return Offset(0f,state.drag(available.y))
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.dragging) return Velocity.Zero
                state.settle(scope,available.y) { currentDismiss() }
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (state.dragging) { state.settle(scope,available.y) { currentDismiss() }; return available }
                return Velocity.Zero
            }
        } }
        Surface(
            Modifier.align(Alignment.TopCenter).widthIn(max=640.dp).fillMaxWidth()
                .height(with(density) { (height-top).toDp() })
                .graphicsLayer { translationY = state.offset }
                .nestedScroll(nested)
                .draggable(rememberDraggableState { state.drag(it) }, Orientation.Vertical,
                    enabled = !state.closing, startDragImmediately = state.phase == DrawerPhase.Opening || state.phase == DrawerPhase.Settling,
                    onDragStarted = { state.beginDrag() },
                    onDragStopped = { state.settle(scope,it) { currentDismiss() } })
                .testTag(tag).semantics {
                    paneTitle = "φ 智能面板"
                    isTraversalGroup = true
                    this[FiSheetOffset] = state.offset
                    this[FiSheetViewport] = state.viewport
                    this[FiSheetPhase] = state.phase.name
                    dismiss { close {}; true }
                    expand { state.move(scope,state.expanded) { currentDismiss() }; true }
                    collapse { state.move(scope,state.half) { currentDismiss() }; true }
                }
                .focusRequester(focus)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup().focusable(),
            shape = BottomSheetDefaults.ExpandedShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().height(with(density) { state.viewport.toDp() })
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))) {
                    Box(Modifier.fillMaxWidth().height(32.dp).clickable(
                        interactionSource=remember { MutableInteractionSource() },indication=null,
                        onClickLabel="展开或收起 φ 智能面板") {
                        state.move(scope,if (state.offset > state.half-1f) state.expanded else state.half) { currentDismiss() }
                    },contentAlignment=Alignment.Center) {
                        Box(Modifier.size(36.dp,4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f),RoundedCornerShape(2.dp)))
                    }
                    content(close)
                }
            }
        }
    }
}
