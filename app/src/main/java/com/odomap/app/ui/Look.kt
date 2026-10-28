package com.odomap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odomap.app.ui.design.AUTO_PAPER
import com.odomap.app.ui.design.ClosePill
import com.odomap.app.ui.design.LineStyle
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.LookStore
import com.odomap.app.ui.design.Marker
import com.odomap.app.ui.design.PAPERS
import com.odomap.app.ui.design.Palette
import com.odomap.app.ui.design.Paper
import com.odomap.app.ui.design.Shadow
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.WIDE_MIN_PT
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.paperOf
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.rgbColor
import com.odomap.app.ui.design.swallowTouches
import com.odomap.app.ui.map.drawPuck

/** 견본 칸 66 · 고른 링 62(테 2) · 원 54 */
private const val CELL = 66f
private const val RING = 62f
private const val DISC = 54f

/** 견본 선(54 단위) */
private val SAMPLE = PathParser().parsePathString("M13 33c4-9 9-13 14-9s9 5 14-6").toPath()

/**
 * 꾸미기 시트(발자취 ExploreLookSheet, design.md 4.10): 떠 있는 카드(최대 480, 모서리 30) — 배색 17 + 자동, 선 6, 현재 위치(점 · 달).
 * 시트는 늘 어두운 판(발자취 시안과 같게), 고르면 바로 지도에 든다. 바깥을 누르면 닫힌다.
 */
@Composable
fun LookSheet(looks: LookStore, nightMode: Boolean, onClose: () -> Unit) {
    val look by looks.look.collectAsStateWithLifecycle()
    val current = LocalPalette.current
    val sheet = remember { Palette(paperOf("night", true)) }
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp().value }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)).press(1f, onClick = onClose)) {
        val wide = maxWidth.value >= WIDE_MIN_PT
        val w = minOf(480f, maxWidth.value - 32f)
        CompositionLocalProvider(LocalPalette provides sheet) {
            Column(
                Modifier
                    .align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
                    .padding(start = if (wide) 16.dp else 0.dp, bottom = (16f + bottomInset).dp)
                    .width(w.dp)
                    .card(sheet.surface, 30f, Shadow(4f, 16f, 0.18f))
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(30.dp))
                    .border(1.dp, Color.White.copy(alpha = if (current.dark) 0.10f else 0f), androidx.compose.foundation.shape.RoundedCornerShape(30.dp))
                    .swallowTouches()
                    .padding(top = 20.dp, bottom = 20.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("지도 꾸미기", Type.lookTitle, sheet.textPrimary, Modifier.weight(1f))
                    ClosePill(onClick = onClose)
                }
                Section("배색")
                val papers: List<String> = listOf(AUTO_PAPER) + PAPERS.map { it.id }
                SwatchRow(papers, selected = look.paper) { id ->
                    val label = if (id == AUTO_PAPER) "자동" else PAPERS.first { it.id == id }.label
                    Swatch(label, id == look.paper, onClick = { looks.update { it.copy(paper = id) } }) {
                        if (id == AUTO_PAPER) {
                            AutoDisc(paperOf("paper", false), paperOf("night", true))
                        } else {
                            PaperDisc(PAPERS.first { it.id == id })
                        }
                    }
                }
                Section("선")
                SwatchRow(LineStyle.entries.map { it.id }, selected = look.line.id) { id ->
                    val style = LineStyle.entries.first { it.id == id }
                    Swatch(style.label, style == look.line, onClick = { looks.update { it.copy(line = style) } }) {
                        Canvas(Modifier.size(DISC.dp)) {
                            drawCircle(current.paper)
                            drawLineSample(style, current.ink, current.paper)
                        }
                    }
                }
                Section("현재 위치")
                SwatchRow(Marker.entries.map { it.id }, selected = look.marker.id) { id ->
                    val m = Marker.entries.first { it.id == id }
                    Swatch(m.label, m == look.marker, onClick = { looks.update { it.copy(marker = m) } }) {
                        Canvas(Modifier.size(DISC.dp)) {
                            drawCircle(current.paper)
                            drawPuck(Offset(size.width / 2, size.height / 2), null, current, m)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    val p = LocalPalette.current
    T(text, Type.lookSection, p.textSecondary, Modifier.padding(start = 22.dp, top = 18.dp, bottom = 10.dp))
}

@Composable
private fun SwatchRow(ids: List<String>, selected: String, item: @Composable (String) -> Unit) {
    val start = ids.indexOf(selected).coerceAtLeast(0)
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, start - 2))
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ids, key = { it }) { item(it) }
    }
}

/** 견본 하나: 원 54(고르면 62 링) + 이름 12/500 */
@Composable
private fun Swatch(label: String, selected: Boolean, onClick: () -> Unit, disc: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(CELL.dp).press(0.94f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(CELL.dp), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(RING.dp).border(2.dp, p.textPrimary, CircleShape))
            // 어두운 판 위 어두운 견본도 보이게 — 가는 테
            Box(Modifier.size(DISC.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)) { disc() }
        }
        Spacer(Modifier.height(4.dp))
        T(label, Type.swatchLabel, if (selected) p.textPrimary else p.textSecondary)
    }
}

@Composable
private fun PaperDisc(paper: Paper) {
    Canvas(Modifier.size(DISC.dp)) {
        drawCircle(rgbColor(paper.paper))
        drawLineSample(LineStyle.SOLID, rgbColor(paper.ink), rgbColor(paper.paper))
    }
}

/** 자동: 반은 종이 · 반은 밤 */
@Composable
private fun AutoDisc(day: Paper, night: Paper) {
    Canvas(Modifier.size(DISC.dp)) {
        drawCircle(rgbColor(day.paper))
        clipRect(left = size.width / 2) {
            drawCircle(rgbColor(night.paper))
        }
        clipRect(right = size.width / 2) { drawLineSample(LineStyle.SOLID, rgbColor(day.ink), rgbColor(day.paper)) }
        clipRect(left = size.width / 2) { drawLineSample(LineStyle.SOLID, rgbColor(night.ink), rgbColor(night.paper)) }
    }
}

/** 견본 선(굵기 2.2)을 선 모양대로. bg = 원 바탕(두 줄의 가운데) */
private fun DrawScope.drawLineSample(style: LineStyle, ink: Color, bg: Color) {
    val k = size.width / DISC
    val path = androidx.compose.ui.graphics.Path().apply { addPath(SAMPLE) }
    path.transform(androidx.compose.ui.graphics.Matrix().apply { scale(k, k) })
    val w = 2.2f * density
    when (style) {
        LineStyle.SOLID -> drawPath(path, ink, style = Stroke(w, cap = StrokeCap.Round))
        LineStyle.DOTTED -> drawPath(path, ink, style = Stroke(w * 1.45f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 2.2f))))
        LineStyle.DASHED -> drawPath(path, ink, style = Stroke(w, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 1.6f, w * 2.4f))))
        LineStyle.DOUBLE -> {
            drawPath(path, ink, style = Stroke(w * 2.0f, cap = StrokeCap.Butt))
            drawPath(path, bg, style = Stroke(w * 0.85f, cap = StrokeCap.Butt))
        }
        LineStyle.MARKER -> drawPath(path, ink.copy(alpha = 0.34f), style = Stroke(w * 2.3f, cap = StrokeCap.Round))
        LineStyle.BLEED -> {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    color = ink.copy(alpha = 0.3f)
                    this.style = androidx.compose.ui.graphics.PaintingStyle.Stroke
                    strokeWidth = w * 3.4f
                    strokeCap = StrokeCap.Round
                    asFrameworkPaint().maskFilter = android.graphics.BlurMaskFilter(w * 1.2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawPath(path, paint)
            }
            drawPath(path, ink, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}
