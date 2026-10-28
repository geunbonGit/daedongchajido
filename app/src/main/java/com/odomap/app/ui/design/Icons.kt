package com.odomap.app.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * 아이콘 — 발자취 SVG 경로 그대로(design-footprint-ref 4, ExploreIcons.tsx · PinIcons.tsx · TabIcons.tsx · ShareIcons.tsx · RecordIcons.tsx).
 * 채운 윤곽(선이 아니라) · 24 단위. 글자로 그리지 않는다.
 */
private fun vector(name: String, viewport: Float, vararg paths: Pair<String, Boolean>): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = viewport.dp, defaultHeight = viewport.dp, viewportWidth = viewport, viewportHeight = viewport)
    for ((d, evenOdd) in paths) {
        b.addPath(pathData = addPathNodes(d), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero, fill = SolidColor(Color.Black))
    }
    return b.build()
}

private fun f(v: Double): String = String.format(Locale.US, "%.3f", v)

/** 발자국 하나(TabIcons footPath) */
private fun footPath(cx: Double, cy: Double): String {
    fun x(dx: Double) = f(cx + dx)
    fun y(dy: Double) = f(cy + dy)
    return "M${x(0.0)} ${y(-4.5)}C${x(2.2)} ${y(-4.5)} ${x(2.8)} ${y(-2.5)} ${x(2.6)} ${y(-0.6)}" +
        "C${x(2.45)} ${y(0.8)} ${x(1.6)} ${y(1.6)} ${x(0.0)} ${y(1.6)}" +
        "C${x(-1.6)} ${y(1.6)} ${x(-2.45)} ${y(0.8)} ${x(-2.6)} ${y(-0.6)}" +
        "C${x(-2.8)} ${y(-2.5)} ${x(-2.2)} ${y(-4.5)} ${x(0.0)} ${y(-4.5)}Z" +
        "M${x(-1.8)} ${y(3.6)}A1.8 1.5 0 1 0 ${x(1.8)} ${y(3.6)}A1.8 1.5 0 1 0 ${x(-1.8)} ${y(3.6)}Z"
}

private fun circlePath(cx: Double, cy: Double, r: Double): String =
    "M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 0 ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 0 ${f(cx - r)} ${f(cy)}Z"

private fun barPath(x1: Double, y1: Double, x2: Double, y2: Double, t: Double): String {
    val length = hypot(x2 - x1, y2 - y1)
    val nx = (-(y2 - y1) / length) * (t / 2)
    val ny = ((x2 - x1) / length) * (t / 2)
    return "M${f(x1 + nx)} ${f(y1 + ny)}L${f(x2 + nx)} ${f(y2 + ny)}L${f(x2 - nx)} ${f(y2 - ny)}L${f(x1 - nx)} ${f(y1 - ny)}Z"
}

private val SHARE_PATH: String =
    run {
        val dots = listOf(6.2 to 12.0, 17.6 to 5.4, 17.6 to 18.6)
        barPath(dots[0].first, dots[0].second, dots[1].first, dots[1].second, 1.9) +
            barPath(dots[0].first, dots[0].second, dots[2].first, dots[2].second, 1.9) +
            dots.joinToString("") { (x, y) -> circlePath(x, y, 3.1) }
    }

/** 톱니(설정): 바깥 톱니 8개 + 가운데 구멍 */
private val GEAR_PATH: String =
    run {
        val sb = StringBuilder()
        val teeth = 8
        val rOut = 10.2
        val rIn = 7.9
        val step = 2 * PI / teeth
        for (i in 0 until teeth) {
            val a = i * step - PI / 2
            val pts = listOf(a - step * 0.22 to rOut, a + step * 0.22 to rOut, a + step * 0.34 to rIn, a + step * 0.66 to rIn)
            pts.forEachIndexed { j, (ang, r) ->
                val x = 12 + r * cos(ang)
                val y = 12 + r * sin(ang)
                sb.append(if (i == 0 && j == 0) "M" else "L").append(f(x)).append(' ').append(f(y))
            }
        }
        sb.append("Z")
        sb.append(circlePath(12.0, 12.0, 3.4))
        sb.toString()
    }

/** › — 두 팔이 평행한 띠, 끝은 수평으로 잘림(ChevronIcon, 57°). size = 높이 */
private fun chevronRight(size: Double, stroke: Double): Pair<String, Double> {
    val angle = 57 * PI / 180
    val arm = stroke / sin(angle)
    val run = size / 2 / tan(angle)
    val width = arm + run
    return "M0 0H${f(arm)}L${f(width)} ${f(size / 2)}L${f(arm)} ${f(size)}H0L${f(run)} ${f(size / 2)}Z" to width
}

object Icons {
    val crosshair =
        vector(
            "crosshair", 24f,
            "M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14Zm0 1.7a5.3 5.3 0 1 1 0 10.6a5.3 5.3 0 1 1 0-10.6Z" to true,
            "M11.15 2h1.7v4.2h-1.7zM11.15 17.8h1.7V22h-1.7zM2 11.15h4.2v1.7H2zM17.8 11.15H22v1.7h-4.2zM12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4Z" to false,
        )
    val flag = vector("flag", 24f, "M5 3h1.8v18H5zM6.8 4.2h11.4l-2.5 3.7 2.5 3.7H6.8z" to false)
    val fitAll = vector("fitAll", 24f, "M3.5 3.5h6v1.8H5.3v4.2H3.5zM20.5 3.5h-6v1.8h4.2v4.2h1.8zM3.5 20.5h6v-1.8H5.3v-4.2H3.5zM20.5 20.5h-6v-1.8h4.2v-4.2h1.8z" to false)
    val play = vector("play", 24f, "M8.2 4.6c-.9-.55-2.05.1-2.05 1.15v12.5c0 1.05 1.15 1.7 2.05 1.15l10.15-6.25a1.35 1.35 0 0 0 0-2.3Z" to false)
    val pause = vector("pause", 24f, "M6.9 4.4h1.6a1.5 1.5 0 0 1 1.5 1.5v12.2a1.5 1.5 0 0 1-1.5 1.5H6.9a1.5 1.5 0 0 1-1.5-1.5V5.9a1.5 1.5 0 0 1 1.5-1.5ZM15.5 4.4h1.6a1.5 1.5 0 0 1 1.5 1.5v12.2a1.5 1.5 0 0 1-1.5 1.5h-1.6a1.5 1.5 0 0 1-1.5-1.5V5.9a1.5 1.5 0 0 1 1.5-1.5Z" to false)
    val stop = vector("stop", 24f, "M8.1 5.5h7.8a2.6 2.6 0 0 1 2.6 2.6v7.8a2.6 2.6 0 0 1-2.6 2.6H8.1a2.6 2.6 0 0 1-2.6-2.6V8.1a2.6 2.6 0 0 1 2.6-2.6Z" to false)
    val foldedMap =
        vector(
            "foldedMap", 24f,
            "M8.6 4.1 3.9 5.9A1.5 1.5 0 0 0 3 7.3v11.4a1 1 0 0 0 1.36.93L8.6 18l6.8 2 4.7-1.8a1.5 1.5 0 0 0 .9-1.4V5.4a1 1 0 0 0-1.36-.93L15.4 6 8.6 4.1ZM9.6 6.2l4.8 1.35v10.3L9.6 16.5V6.2ZM5 7.6l2.6-1V16.4L5 17.4V7.6Zm11.4 0L19 6.6v9.8l-2.6 1V7.6Z" to true,
        )
    val palette =
        vector(
            "palette", 24f,
            "M12 3a9 9 0 0 0 0 18c1.25 0 1.9-.85 1.9-1.75 0-.5-.2-.95-.55-1.3-.32-.32-.5-.75-.5-1.2 0-.95.8-1.75 1.8-1.75H17a4 4 0 0 0 4-4C21 6.7 17 3 12 3Zm-4.5 6.6a1.45 1.45 0 1 1 0 2.9a1.45 1.45 0 1 1 0-2.9Zm2.6-3.5a1.45 1.45 0 1 1 0 2.9a1.45 1.45 0 1 1 0-2.9Zm4.6 0a1.45 1.45 0 1 1 0 2.9a1.45 1.45 0 1 1 0-2.9Zm2.6 3.5a1.45 1.45 0 1 1 0 2.9a1.45 1.45 0 1 1 0-2.9Z" to true,
        )
    val camera =
        vector(
            "camera", 24f,
            "M8.6 4.4h6.8a1.3 1.3 0 0 1 1.08.58L17.6 6.6h1.9A2.5 2.5 0 0 1 22 9.1v8.9a2.5 2.5 0 0 1-2.5 2.5h-15A2.5 2.5 0 0 1 2 18V9.1a2.5 2.5 0 0 1 2.5-2.5h1.9l1.12-1.62A1.3 1.3 0 0 1 8.6 4.4ZM12 8.9a4.35 4.35 0 1 0 0 8.7a4.35 4.35 0 1 0 0-8.7Zm0 1.75a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 1 1 0-5.2Z" to true,
        )
    val car =
        vector(
            "car", 24f,
            "M7.3 5.5h9.4c.8 0 1.5.5 1.8 1.2l1.6 3.9h.4c.8 0 1.5.7 1.5 1.5v4.4c0 .6-.5 1.1-1.1 1.1h-.9a2.6 2.6 0 0 1-5.1 0H9.1a2.6 2.6 0 0 1-5.1 0h-.9c-.6 0-1.1-.5-1.1-1.1v-4.4c0-.8.7-1.5 1.5-1.5h.4l1.6-3.9c.3-.7 1-1.2 1.8-1.2Zm.2 1.8-1.3 3.3h11.6l-1.3-3.3H7.5Zm-1 8.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2Zm11 0a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2Z" to true,
        )
    val calendar =
        vector(
            "calendar", 24f,
            "M7.6 2.6h1.8v1.9h5.2V2.6h1.8v1.9h1.6A2.5 2.5 0 0 1 20.5 7v11.5a2.5 2.5 0 0 1-2.5 2.5H6a2.5 2.5 0 0 1-2.5-2.5V7A2.5 2.5 0 0 1 6 4.5h1.6V2.6ZM5.3 9.6v8.9a.7.7 0 0 0 .7.7h12a.7.7 0 0 0 .7-.7V9.6H5.3Zm2.4 2.2h2.2V14H7.7v-2.2Zm4.2 0h2.2V14h-2.2v-2.2Zm4.2 0h2.2V14h-2.2v-2.2ZM7.7 15.6h2.2v2.2H7.7v-2.2Zm4.2 0h2.2v2.2h-2.2v-2.2Z" to true,
        )
    val replay = vector("replay", 24f, "M12 4.2a7.8 7.8 0 1 1-7.4 10.3l1.7-.6A6 6 0 1 0 7.9 7.6l2 2H4.4V4.1l2.2 2.2A7.8 7.8 0 0 1 12 4.2Z" to false)
    val footprints = vector("footprints", 24f, footPath(8.3, 13.8) + footPath(15.7, 9.6) to false)
    val share = vector("share", 24f, SHARE_PATH to false)
    val gear = vector("gear", 24f, GEAR_PATH to true)
    /** 길잡이 화살(14 단위) */
    val compassArrow = vector("compassArrow", 14f, "M7 0.8 12.6 12.4 7 9.6 1.4 12.4Z" to false)
    /** 달(꾸미기 견본 — 54 단위, ExploreLookSheet MarkerSample) */
    val moon54 = vector("moon54", 54f, "M27 18.5a8.5 8.5 0 1 0 7.5 12.6a6.8 6.8 0 0 1-7.5-12.6Z" to false)
    val chevron: ImageVector =
        run {
            val (d, w) = chevronRight(9.0, 1.7)
            ImageVector.Builder("chevron", w.toFloat().dp, 9.dp, w.toFloat(), 9f)
                .addPath(addPathNodes(d), fill = SolidColor(Color.Black))
                .build()
        }
    /** × (닫기 원 안 — 발자취 BackButton CloseGlyph: 14 길이 · 2.1 굵기 막대 둘) */
    val close: ImageVector =
        run {
            val half = (14 - 2.1f) / 2f * 0.70710677f
            val c = 7f
            ImageVector.Builder("close", 14.dp, 14.dp, 14f, 14f)
                .addPath(
                    addPathNodes("M${c - half} ${c - half}L${c + half} ${c + half}M${c + half} ${c - half}L${c - half} ${c + half}"),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2.1f,
                    strokeLineCap = StrokeCap.Round,
                )
                .build()
        }
}

/** 아이콘 하나(size pt, tint) */
@Composable
fun Icon(vector: ImageVector, size: Float, tint: Color, modifier: Modifier = Modifier) {
    val ratio = vector.defaultWidth.value / vector.defaultHeight.value
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(width = (size * ratio).dp, height = size.dp),
    )
}
