package com.odomap.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.SheetShadow
import com.odomap.app.ui.design.card
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 발자취 SHEET_SPRING {stiffness 1000, mass 3, damping 110} — 질량을 나누면 k/m 333, 감쇠비 ≥ 1(넘치지 않는다) */
private val SHEET_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 333f)

/** 손잡이 칸(위아래 10 + 막대 4) */
const val HANDLE_H = 24f

/**
 * 두 자리 판(작게 · 크게) 상태. 높이는 아래에서 잰 pt. 판을 올려도 지도는 움직이지 않는다(지도가 비워 둘 아래 높이는 늘 작은 자리).
 */
@Stable
class SheetState(private val scope: CoroutineScope, private val startExpanded: Boolean = false) {
    var small by mutableFloatStateOf(200f)
        private set
    var large by mutableFloatStateOf(600f)
        private set
    private val anim = Animatable(200f)
    private var initialized = false
    var locked by mutableStateOf(false)

    /** 지금 높이(pt) */
    val shown: Float get() = anim.value

    /** 0 = 작게 · 1 = 크게 */
    val progress: Float get() = if (large > small) ((anim.value - small) / (large - small)).coerceIn(0f, 1f) else 0f

    val expanded: Boolean get() = anim.targetValue >= large - 0.5f

    fun setPoints(s: Float, l: Float) {
        val wasExpanded = if (initialized) expanded else startExpanded
        small = s
        large = maxOf(l, s + 160f)
        val target = if (wasExpanded && !locked) large else small
        scope.launch { anim.snapTo(target) }
        initialized = true
    }

    fun dragBy(deltaPt: Float) {
        if (locked) return
        scope.launch { anim.snapTo((anim.value - deltaPt).coerceIn(small, large)) }
    }

    /** 손을 뗀 속도(pt/s, 위가 −)로 자리를 고른다 */
    fun settle(velocityPt: Float) {
        val target =
            when {
                locked -> small
                velocityPt < -400f -> large
                velocityPt > 400f -> small
                else -> if (anim.value - small > (large - small) / 2) large else small
            }
        scope.launch { anim.animateTo(target, SHEET_SPRING, initialVelocity = -velocityPt) }
    }

    fun collapse() {
        if (anim.targetValue == small && abs(anim.value - small) < 0.5f) return
        scope.launch { anim.animateTo(small, SHEET_SPRING) }
    }

    fun expand() {
        if (locked) return
        scope.launch { anim.animateTo(large, SHEET_SPRING) }
    }
}

@Composable
fun rememberSheetState(startExpanded: Boolean = false): SheetState {
    val scope = rememberCoroutineScope()
    return androidx.compose.runtime.remember { SheetState(scope, startExpanded) }
}

/** 판을 이만큼(pt) 올리는 동안 아래 기록이 흐림에서 나타난다(발자취 RECORDS_FADE_FROM/TO), 0.6 넘게 나타나야 눌린다 */
fun recordsReveal(shownAboveSmall: Float): Float = ((shownAboveSmall - 12f) / (120f - 12f)).coerceIn(0f, 1f)

/**
 * 아래 판: 바탕 surface, 위 모서리 28, 그림자 0 −2 32 @0.1, 손잡이 36×4(textMuted @0.5). 윗부분(upper)은 판 위 끝에 붙어 함께 오르내리고
 * (끌면 판이 움직인다), 그 아래 body는 판을 올릴수록 나타난다. body 안 스크롤은 판이 다 올라간 뒤에만 — 맨 위에서 아래로 끌면 판이 내려간다.
 */
@Composable
fun BottomSheet(state: SheetState, modifier: Modifier = Modifier, upper: @Composable ColumnScope.() -> Unit, body: @Composable (Modifier, NestedScrollConnection) -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val dragState = rememberDraggableState { delta -> state.dragBy(with(density) { delta.toDp().value }) }
    val connection =
        androidx.compose.runtime.remember(state) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // 위로 끌 때 판이 아직 다 안 올라갔으면 판을 먼저
                    val dy = available.y
                    if (dy < 0 && state.shown < state.large - 0.5f && !state.locked) {
                        state.dragBy(with(density) { dy.toDp().value })
                        return Offset(0f, dy)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    // 목록 맨 위에서 아래로 남은 끌기는 판을 내린다
                    val dy = available.y
                    if (dy > 0 && source == NestedScrollSource.UserInput) {
                        state.dragBy(with(density) { dy.toDp().value })
                        return Offset(0f, dy)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.shown > state.small + 0.5f && state.shown < state.large - 0.5f) {
                        state.settle(with(density) { available.y.toDp().value })
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
    Box(
        modifier
            .fillMaxWidth()
            .height(state.large.dp)
            .offset { IntOffset(0, with(density) { (state.large - state.shown).dp.toPx() }.roundToInt()) }
            .card(p.surface, 28f, SheetShadow, topOnly = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(dragState, Orientation.Vertical, enabled = !state.locked, onDragStopped = { v -> state.settle(with(density) { v.toDp().value }) }),
            ) {
                Box(Modifier.fillMaxWidth().height(HANDLE_H.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).graphicsLayer { alpha = 0.5f }.background(p.textMuted, RoundedCornerShape(4.dp)))
                }
                upper()
            }
            val reveal = recordsReveal(state.shown - state.small)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                body(Modifier.graphicsLayer { alpha = reveal }.nestedScroll(connection), connection)
                // 고정된 윗부분 밑으로 지나가는 목록을 흐리게
                Box(Modifier.fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(p.surface, p.surface.copy(alpha = 0f)))))
            }
        }
    }
}

/** 판이 지도에 비워 둘 높이(pt) */
@Suppress("unused")
fun Modifier.sheetPadding(state: SheetState): Modifier = this.padding(bottom = state.small.dp)
