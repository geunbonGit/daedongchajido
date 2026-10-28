package com.odomap.app.ui.design

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import kotlin.math.max

/**
 * 떠 있는 그림자 — 발자취 floatShadow(y, radius, opacity) = CSS `0 y 2·radius rgba(0,0,0,opacity)`. CSS 흐림 B의 표준편차는 B/2(= radius)라
 * 같은 퍼짐의 BlurMaskFilter 반지름으로 바꿔 그린다(Android 반지름 → 시그마: 0.57735·r + 0.5).
 */
data class Shadow(val y: Float, val radius: Float, val opacity: Float)

/** 도구 묶음·동그라미 단추 */
val ToolShadow = Shadow(2f, 8f, 0.12f)
/** 길잡이 알약 */
val CompassShadow = Shadow(2f, 6f, 0.14f)
/** 아래 판·왼쪽 판 */
val SheetShadow = Shadow(-2f, 16f, 0.1f)
/** 떠 있는 왼쪽 판(아래로 드리움) */
val PanelShadow = Shadow(2f, 16f, 0.1f)
/** 내 점 */
val PuckShadow = Shadow(1f, 1.5f, 0.18f)

private fun sigmaToRadius(sigma: Float): Float = max(0.5f, (sigma - 0.5f) / 0.57735f)

/**
 * 모서리 둥근 판 하나(바탕색 + 그림자). corner는 pt(모두 같은 둥글기), topOnly면 아래 모서리는 각지게(아래 판).
 */
fun Modifier.card(color: Color, corner: Float, shadow: Shadow? = null, topOnly: Boolean = false): Modifier =
    drawBehind { drawCard(color, corner, shadow, topOnly) }

fun DrawScope.drawCard(color: Color, corner: Float, shadow: Shadow?, topOnly: Boolean = false, size: Size = this.size, offset: Offset = Offset.Zero) {
    val r = corner * density
    val rect =
        if (topOnly) {
            RoundRect(offset.x, offset.y, offset.x + size.width, offset.y + size.height, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero)
        } else {
            RoundRect(offset.x, offset.y, offset.x + size.width, offset.y + size.height, CornerRadius(r))
        }
    val path = Path().apply { addRoundRect(rect) }
    if (shadow != null && shadow.opacity > 0f) {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val fp = paint.asFrameworkPaint()
            fp.isAntiAlias = true
            fp.color = Color.Black.copy(alpha = shadow.opacity).toArgb()
            fp.maskFilter = BlurMaskFilter(sigmaToRadius(shadow.radius * density), BlurMaskFilter.Blur.NORMAL)
            canvas.save()
            canvas.translate(0f, shadow.y * density)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
    drawPath(path, color)
}

/** 동그라미(지름 = 짧은 변) 바탕 + 그림자 */
fun Modifier.circle(color: Color, shadow: Shadow? = null): Modifier =
    drawBehind {
        val d = minOf(size.width, size.height)
        drawCard(color, d / 2f / density, shadow, size = Size(d, d), offset = Offset((size.width - d) / 2f, (size.height - d) / 2f))
    }

/**
 * 누름 — 발자취 BouncyPressable: 누르면 scaleTo로 스프링 {320, 18, 0.56}, 떼면 {220, 14, 0.56}(질량을 나눠 Compose 스프링으로).
 * 물결 없음. onLongClick이 있으면 길게 누르기도.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.press(
    scaleTo: Float = 0.96f,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val source = remember { MutableInteractionSource() }
        val scale = remember { Animatable(1f) }
        LaunchedEffect(source) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> scale.animateTo(scaleTo, PRESS_IN)
                    is PressInteraction.Release, is PressInteraction.Cancel -> scale.animateTo(1f, PRESS_OUT)
                }
            }
        }
        val base = this.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        if (onLongClick != null) {
            base.combinedClickable(interactionSource = source, indication = null, enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick)
        } else {
            base.clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
        }
    }

// 발자취 PRESS_IN_SPRING {stiffness 320, damping 18, mass 0.56} → k/m 571, ζ 0.67 · OUT {220, 14, 0.56} → 393, ζ 0.63
private val PRESS_IN = spring<Float>(dampingRatio = 0.672f, stiffness = 571f)
private val PRESS_OUT = spring<Float>(dampingRatio = 0.63f, stiffness = 393f)

/** 손가락을 받기만 하고 아무것도 하지 않는다(판 뒤 지도로 새지 않게) */
fun Modifier.swallowTouches(): Modifier =
    composed { clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}) }
