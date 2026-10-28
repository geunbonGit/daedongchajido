package com.odomap.app.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 1 화소 줄(발자취 hairlineWidth) */
@Composable
fun hairline(): Dp = with(LocalDensity.current) { 1f.toDp() }

@Composable
fun HLine(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(hairline()).background(color))
}

@Composable
fun VLine(color: Color, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(hairline()).height(height).background(color))
}

// ───────────────────────── 지도 위 도구

@Immutable
data class ToolItem(val icon: ImageVector, val label: String, val selected: Boolean = false, val dimmed: Boolean = false, val onClick: () -> Unit)

/** 도구 묶음(오른쪽 위): 세로 알약 폭 40, 칸 40×40, 칸 사이 가는 줄(좌우 9), 아이콘 19. 켜진 칸은 32 잉크 원 + 종이색 아이콘 */
@Composable
fun ToolPill(items: List<ToolItem>, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.width(40.dp).card(p.button, 20f, ToolShadow).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))) {
        items.forEachIndexed { index, item ->
            if (index > 0) HLine(p.dividerSubtle, Modifier.padding(horizontal = 9.dp))
            Box(Modifier.size(40.dp).press(0.9f, onClick = item.onClick), contentAlignment = Alignment.Center) {
                if (item.selected) Box(Modifier.size(32.dp).background(p.ink, CircleShape))
                Icon(item.icon, 19f, if (item.selected) p.paper else p.buttonIcon, Modifier.alpha(if (item.dimmed) 0.35f else 1f))
            }
        }
    }
}

/** 동그라미 단추(현재 위치 44 · 십자 22). 켜짐(따라가는 중)이면 잉크 바탕 + 종이색 아이콘. 누르면 살짝 커진다(1.05) */
@Composable
fun RoundButton(icon: ImageVector, iconSize: Float, modifier: Modifier = Modifier, size: Float = 44f, selected: Boolean = false, dimmed: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.size(size.dp).circle(if (selected) p.ink else p.button, ToolShadow).press(1.05f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, iconSize, if (selected) p.paper else p.buttonIcon, Modifier.alpha(if (dimmed) 0.35f else 1f))
    }
}

/** 길잡이 알약(화면 밖 내 길 쪽): 92×34, 화살 14 + 거리 14/500, 간격 6 */
@Composable
fun CompassChip(label: String, rotation: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.width(92.dp).height(34.dp).card(p.button, 17f, CompassShadow).press(0.94f, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).rotate(rotation), contentAlignment = Alignment.Center) { Icon(Icons.compassArrow, 14f, p.buttonIcon) }
        T(label, Type.compass, p.text)
    }
}

// ───────────────────────── 알약

const val PILL_H = 56f

/** 알약 글자 크기: 기호 20 + 틈 7 + 양옆 14 를 뺀 폭에 들어가게 17에서 12까지(발자취 labelSizeFor) */
@Composable
fun pillLabelSize(labels: List<String>, width: Float, withIcon: Boolean = true): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val room = width - (if (withIcon) 27f else 0f) - 28f
    var size = 17f
    while (size > MIN_TEXT) {
        val widest = labels.maxOf { with(density) { measurer.measure(it, Type.pill.copy(fontSize = size.sp)).size.width.toDp().value } }
        if (widest <= room) return size
        size -= 0.5f
    }
    return MIN_TEXT
}

/** 검은 알약(주 단추): 높이 56, 바탕 ink, 아이콘 20 + 글자 17/600/−0.2 textInverse, 간격 7. 누르면 3.5% 줄어든다 */
@Composable
fun BlackPill(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg = if (enabled) p.ink else p.busyFill
    val fg = if (enabled) p.textInverse else p.busyText
    Box(modifier.height(PILL_H.dp).card(bg, PILL_H / 2).press(0.965f, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, 20f, fg)
            T(text, Type.pill, fg)
        }
    }
}

/** 무채색 알약(누르지 않는 상태 줄 · 둘째 단추): 바탕 mix(button, ink, 0.08/0.14) */
@Composable
fun NeutralPill(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val p = LocalPalette.current
    val base = modifier.height(PILL_H.dp).card(p.neutralPill, PILL_H / 2)
    Box(if (onClick != null) base.press(0.965f, onClick = onClick) else base, contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** 판 머리 오른쪽 작은 알약 "내 지도 415.0km ›" */
@Composable
fun SmallPill(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.height(32.dp).card(p.neutralPill, 16f).press(0.95f, onClick = onClick).padding(start = 12.dp, end = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, ts(13f, W.Medium, 18f), p.textSecondary)
        T(value, ts(15f, W.Bold, 20f, -0.2f), p.textPrimary)
        Icon(Icons.chevron, 9f, p.textMuted)
    }
}

/** 글자만 있는 작은 단추('닫기' · '기록 버리기') */
@Composable
fun TextButton(text: String, color: Color, modifier: Modifier = Modifier, style: TextStyle = ts(15f, W.Medium, 20f), onClick: () -> Unit) {
    Box(modifier.press(0.96f, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp)) { T(text, style, color) }
}

/** 닫기 원(하위 화면 왼쪽 위): 40, 바탕 surface, × 14 */
@Composable
fun CloseCircle(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(modifier.size(40.dp).circle(p.surface, Shadow(1f, 4f, 0.06f)).press(1.05f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.close, 14f, p.textPrimary)
    }
}

/** '닫기' 알약(꾸미기 시트 머리): 34 높이, 좌우 14, surfaceStrong */
@Composable
fun ClosePill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(modifier.height(34.dp).card(p.surfaceStrong, 17f).press(0.94f, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        T("닫기", ts(14f, W.Medium, 18f), p.textPrimary)
    }
}

// ───────────────────────── 숫자

/** 판 숫자 셋의 한 칸 */
@Immutable
data class SheetStat(
    val key: String,
    val label: String,
    val value: String,
    val unit: String = "",
    /** 어림이 덜 된 값(흐리게) */
    val soft: Boolean = false,
    /** 값 대신 누를 말(강조색) */
    val action: Boolean = false,
    /** 숫자 글이면 자리별로 넘어간다 */
    val number: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

private const val STAT_VALUE_H = 24f
private const val STAT_LABEL_H = 16f
private const val STAT_UNIT_SIZE = 12f
private const val STAT_UNIT_H = 16f
private const val STAT_DIVIDER_SPACE = 12f
const val STATS_H = STAT_VALUE_H + 2f + STAT_LABEL_H

/**
 * 판 숫자 셋(발자취 StatsRow): 칸마다 숫자 + 단위, 그 아래 이름. 가는 세로줄(높이 36, 위 3, 좌우 12)로 나눈다(박스 안 박스 없음).
 * 숫자 크기는 가장 넓은 칸이 칸 폭에 들어가는 크기(20 → 14, 셋이 같은 크기). 이름은 12(차에서는 더 줄이지 않는다).
 */
@Composable
fun StatRow(stats: List<SheetStat>, width: Float, modifier: Modifier = Modifier, animate: Boolean = true) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val n = stats.size
    val column = (width - STAT_DIVIDER_SPACE * 2 * (n - 1)) / n
    fun widthOf(text: String, style: TextStyle): Float = with(density) { measurer.measure(text, style).size.width.toDp().value }
    var size = 20f
    while (size > 14f) {
        val widest =
            stats.maxOf { s ->
                val v = widthOf(s.value, ts(size, W.SemiBold, STAT_VALUE_H, -0.4f))
                val u = if (s.unit.isNotEmpty()) 2f + widthOf(s.unit, ts(STAT_UNIT_SIZE, W.Medium, STAT_UNIT_H)) else 0f
                (v + u) * 1.05f
            }
        if (widest <= column) break
        size -= 1f
    }
    val valueStyle = ts(size, W.SemiBold, STAT_VALUE_H, -0.4f)
    Row(modifier.height(STATS_H.dp), verticalAlignment = Alignment.Top) {
        stats.forEachIndexed { index, stat ->
            if (index > 0) {
                Spacer(Modifier.width(STAT_DIVIDER_SPACE.dp))
                VLine(p.dividerSubtle, (STATS_H - 6f).dp, Modifier.padding(top = 3.dp))
                Spacer(Modifier.width(STAT_DIVIDER_SPACE.dp))
            }
            val cell = Modifier.width(column.dp)
            Column(if (stat.onClick != null) cell.press(0.96f, onClick = stat.onClick) else cell, horizontalAlignment = Alignment.CenterHorizontally) {
                val color = when {
                    stat.action -> p.blue
                    stat.soft -> p.textSecondary
                    else -> p.textPrimary
                }
                val vs = if (stat.action) valueStyle.copy(fontSize = minOf(size, 16f).sp) else valueStyle
                Row(Modifier.height(STAT_VALUE_H.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (stat.number && !stat.action) AnimatedNumber(stat.value, vs, color, animate = animate) else T(stat.value, vs, color)
                    if (stat.unit.isNotEmpty()) {
                        val lift = unitLift(STAT_VALUE_H, vs.fontSize.value, STAT_UNIT_H, STAT_UNIT_SIZE)
                        T(stat.unit, ts(STAT_UNIT_SIZE, W.Medium, STAT_UNIT_H), p.textSecondary, Modifier.padding(bottom = lift.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                T(oneLine(stat.label), ts(12f, W.Medium, STAT_LABEL_H), p.textSecondary, Modifier.fillMaxWidth(), align = TextAlign.Center)
            }
        }
    }
}

/** 숫자 칸 하나(2×2 칸) */
@Immutable
data class GridStat(val key: String, val label: String, val value: String, val unit: String = "", val prefix: String = "", val number: Boolean = true)

private const val GRID_VALUE_H = 28f
private const val GRID_UNIT_SIZE = 14f
private const val GRID_UNIT_H = 18f
private const val GRID_INNER = 16f

/**
 * 숫자 칸 두 개씩 줄(발자취 ExploreStatGrid): 칸마다 위에 이름 13/500, 아래 숫자 22/700(칸에 맞춰 15까지) + 단위 14/500(왼쪽 3).
 * 칸은 가는 줄로만 나눈다. width는 묶음 폭, inset은 바깥 양옆 여백(혼자 카드면 18, 카드 안이면 0).
 */
@Composable
fun StatGrid(stats: List<GridStat>, width: Float, modifier: Modifier = Modifier, inset: Float = 18f, animate: Boolean = true) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val line = with(density) { 1f.toDp().value }
    val cell = maxOf(0f, (width - inset * 2 - GRID_INNER * 2 - line) / 2)
    fun widthOf(text: String, style: TextStyle): Float = with(density) { measurer.measure(text, style).size.width.toDp().value }
    var size = 22f
    while (size > 15f) {
        val widest =
            stats.maxOf { s ->
                (widthOf(s.prefix + s.value, ts(size, W.Bold, GRID_VALUE_H, -0.3f)) + (if (s.unit.isNotEmpty()) 3f + widthOf(s.unit, ts(GRID_UNIT_SIZE, W.Medium, GRID_UNIT_H)) else 0f)) * 1.05f
            }
        if (widest <= cell) break
        size -= 1f
    }
    val valueStyle = ts(size, W.Bold, GRID_VALUE_H, -0.3f)
    val padV = if (inset > 0) 14f else 12f
    Column(modifier.padding(vertical = if (inset > 0) 4.dp else 0.dp)) {
        stats.chunked(2).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) HLine(p.dividerSubtle, Modifier.padding(horizontal = inset.dp))
            Row(Modifier.fillMaxWidth()) {
                GridCell(row[0], valueStyle, Modifier.weight(1f).padding(start = inset.dp, end = GRID_INNER.dp, top = padV.dp, bottom = padV.dp), animate)
                VLine(p.dividerSubtle, (GRID_VALUE_H + 17f + 4f).dp, Modifier.padding(vertical = padV.dp))
                if (row.size > 1) {
                    GridCell(row[1], valueStyle, Modifier.weight(1f).padding(start = GRID_INNER.dp, end = inset.dp, top = padV.dp, bottom = padV.dp), animate)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GridCell(stat: GridStat, valueStyle: TextStyle, modifier: Modifier, animate: Boolean) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(oneLine(stat.label), Type.gridLabel, p.textSecondary)
        Row(Modifier.height(GRID_VALUE_H.dp), verticalAlignment = Alignment.Bottom) {
            val known = stat.value != "-"
            if (stat.prefix.isNotEmpty() && known) T(stat.prefix, valueStyle, p.textPrimary)
            if (stat.number && known) AnimatedNumber(stat.value, valueStyle, p.textPrimary, animate = animate) else T(stat.value, valueStyle, p.textPrimary)
            if (stat.unit.isNotEmpty() && known) {
                val lift = unitLift(GRID_VALUE_H, valueStyle.fontSize.value, GRID_UNIT_H, GRID_UNIT_SIZE)
                T(stat.unit, ts(GRID_UNIT_SIZE, W.Medium, GRID_UNIT_H), p.textSecondary, Modifier.padding(start = 3.dp, bottom = lift.dp))
            }
        }
    }
}

/** 큰 숫자 + 단위(아래 끝 정렬 — 단위를 바닥선에). lineH는 숫자 줄 높이 */
@Composable
fun BigNumber(value: String, unit: String, valueStyle: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier, color: Color? = null, unitColor: Color? = null, gap: Float = 4f, animate: Boolean = true) {
    val p = LocalPalette.current
    val lineH = valueStyle.lineHeight.value
    Row(modifier.height(lineH.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
        AnimatedNumber(value, valueStyle, color ?: p.textPrimary, animate = animate)
        if (unit.isNotEmpty()) {
            val lift = unitLift(lineH, valueStyle.fontSize.value, unitStyle.lineHeight.value, unitStyle.fontSize.value)
            T(unit, unitStyle, unitColor ?: p.textSecondary, Modifier.padding(bottom = lift.dp))
        }
    }
}

// ───────────────────────── 줄 · 카드

/** 이름 붙은 줄(아이콘 · 이름 · 오른쪽 작은 글 · ›): 높이 56, 모서리 24, 좌우 18 */
@Composable
fun NavRow(icon: ImageVector?, text: String, modifier: Modifier = Modifier, trailing: String? = null, iconSize: Float = 19f, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().height(56.dp).card(p.surface, 24f).press(0.98f, onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { Icon(icon, iconSize, p.textPrimary) }
        T(text, Type.navRow, p.textPrimary, Modifier.weight(1f))
        if (trailing != null) T(trailing, Type.trailing, p.textSecondary)
        Icon(Icons.chevron, 9f, p.textMuted)
    }
}

/** 카드(surface, 모서리 24) */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val base = modifier.fillMaxWidth().card(p.surface, 24f)
    Box(if (onClick != null) base.press(0.98f, onClick = onClick) else base) { content() }
}

/** 묶음 제목(24/32/800) — 옆에 옅은 수 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, count: String? = null) {
    val p = LocalPalette.current
    Row(modifier.height(32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        T(text, Type.sectionTitle, p.textPrimary)
        if (count != null) T(count, Type.sectionTitle, p.textMuted)
    }
}

// ───────────────────────── 알림(토스트)

class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var serial by mutableStateOf(0)

    fun show(text: String) {
        message = text
        serial += 1
    }

    @Composable
    fun Host(modifier: Modifier = Modifier) {
        val p = LocalPalette.current
        val current = message
        LaunchedEffect(serial) {
            if (current != null) {
                delay(2200)
                message = null
            }
        }
        AnimatedVisibility(
            visible = current != null,
            modifier = modifier,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 3 },
        ) {
            Box(Modifier.height(48.dp).card(p.ink, 24f, ToolShadow).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                T(current ?: "", ts(16f, W.SemiBold, 21f, -0.2f), p.textInverse)
            }
        }
    }
}

val LocalToast = staticCompositionLocalOf { ToastState() }

/** 화면 가운데 위 알림을 띄우는 자리 */
@Composable
fun BoxScope.ToastSlot(top: Dp) {
    LocalToast.current.Host(Modifier.align(Alignment.TopCenter).padding(top = top))
}
