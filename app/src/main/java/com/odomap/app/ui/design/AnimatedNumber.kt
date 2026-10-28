package com.odomap.app.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** 발자취 숫자 움직임: 300ms out-cubic, 자리 간 28ms(design-footprint-ref 5). 카운트업 없이 바뀐 자리만 넘어간다. */
private val OUT_CUBIC = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private const val DIGIT_MS = 300
private const val STAGGER_MS = 28

/**
 * 숫자 글을 자리별로 그린다(오른쪽 끝 기준 — 소수 자리가 같은 자리에 남는다). 고정폭 숫자(tnum)라 자리 폭이 바뀌지 않는다.
 * animate = false면 바로 바뀐다(동작 줄이기).
 */
@Composable
fun AnimatedNumber(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, animate: Boolean = true) {
    if (!animate) {
        T(text, style, color, modifier)
        return
    }
    val chars = text.toList()
    Row(modifier) {
        chars.forEachIndexed { index, c ->
            val fromRight = chars.size - 1 - index
            key(fromRight) {
                AnimatedContent(
                    targetState = c,
                    transitionSpec = {
                        val delay = index * STAGGER_MS
                        (slideInVertically(tween(DIGIT_MS, delay, OUT_CUBIC)) { it } + fadeIn(tween(DIGIT_MS, delay, OUT_CUBIC)))
                            .togetherWith(slideOutVertically(tween(DIGIT_MS, delay, OUT_CUBIC)) { -it } + fadeOut(tween(DIGIT_MS / 2, delay, OUT_CUBIC)))
                    },
                    modifier = Modifier.clipToBounds(),
                    label = "digit",
                ) { ch -> T(ch.toString(), style, color) }
            }
        }
    }
}
