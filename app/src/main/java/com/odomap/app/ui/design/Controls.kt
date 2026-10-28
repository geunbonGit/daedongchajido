package com.odomap.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// Small controls of the share / replay screens (발자취 카드 미리보기: SlidingSegment · 바탕 칩 · 토글).

/** 칸 고르기(발자취 SlidingSegment, tone inset): 바탕 surfaceStrong, 고른 칸 = surface 알약(스프링 {17, 230, 0.7}) */
@Composable
fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, height: Float = 44f, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val at by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.67f, stiffness = 328f), label = "seg")
    BoxWithConstraints(modifier.height(height.dp).card(p.surfaceStrong, height / 2).padding(3.dp)) {
        val cell = maxWidth / options.size
        Box(Modifier.offset(x = cell * at).width(cell).fillMaxHeight().card(p.surface, (height - 6f) / 2, Shadow(1f, 1.5f, 0.12f)))
        Row(Modifier.fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val ok = enabled(i)
                Box(
                    Modifier.width(cell).fillMaxHeight().alpha(if (ok) 1f else 0.4f).press(0.96f, enabled = ok && !on) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    T(label, ts(15f, if (on) W.Bold else W.Medium, 20f), if (on) p.textPrimary else p.textMuted)
                }
            }
        }
    }
}

/** 바탕 칩: 견본 동그라미(종이에 먹 점) + 이름. 고르면 textPrimary 바탕 · textInverse 글자 */
@Composable
fun SwatchChip(label: String, paper: Color, ink: Color, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (on) p.textPrimary else p.surfaceStrong, tween(180), label = "chip")
    val fg = if (on) p.textInverse else p.textPrimary
    Row(
        modifier.height(40.dp).card(bg, 20f).press(0.95f, onClick = onClick).padding(start = 10.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).drawBehind {
                val r = size.minDimension / 2
                drawCircle(paper, r)
                drawCircle(ink, r * 0.26f)
                drawCircle(Color(0x597F7F7F), r - 0.5f * density, style = Stroke(1f * density))
            },
        )
        T(label, ts(15f, W.Medium, 20f), fg)
    }
}

/** 켜고 끄기(52×32, 켜면 ink) */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 600f), label = "toggle")
    val track by animateColorAsState(if (on) p.ink else p.surfaceStrong.compositeOverOpaque(p.surface), tween(160), label = "track")
    Box(modifier.size(52.dp, 32.dp).card(track, 16f)) {
        Box(Modifier.offset(x = (3f + 20f * x).dp, y = 3.dp).size(26.dp).card(if (on) p.textInverse else p.surface, 13f, Shadow(1f, 1.5f, 0.18f)))
    }
}

private fun Color.compositeOverOpaque(base: Color): Color =
    Color(
        red = red * alpha + base.red * (1 - alpha),
        green = green * alpha + base.green * (1 - alpha),
        blue = blue * alpha + base.blue * (1 - alpha),
        alpha = 1f,
    )
