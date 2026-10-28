package com.odomap.app.ui.replay

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import com.odomap.app.share.ShareCardArt
import com.odomap.app.share.levelWidthAt
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// 다시 보기 판 그리기(발자취 growthDraw drawGrowthFrame): 360×640pt 판 — 종이 + 44pt 모눈 → (한 지역이면 그 모양 한 겹) → 날짜 줄 · 세는 숫자 ·
// '처음 달린 길' 줄 → 지도 상자 {18, 196, 324, 386, 안쪽 16}의 선(한 잉크, 굵기 단계) · 앞머리 점 → 오른쪽 아래 로고. 판 단위(pt)로 그려 k배(px/pt).

object ReplayBoard {
    const val W = 360f
    const val H = 640f
    const val BOX_X = 18f
    const val BOX_Y = 196f
    const val BOX_W = 324f
    const val BOX_H = 386f
    const val BOX_PAD = 16f
    const val DATE_Y = 92f
    const val DATE_SIZE = 15f
    const val NUMBER_Y = 144f
    const val NUMBER_SIZE = 40f
    const val UNIT_SIZE = 17f
    const val LABEL_Y = 172f
    const val LABEL_SIZE = 14f
    const val BRAND_H = 16f
    const val BRAND_RIGHT = 22f
    const val BRAND_CY = 606f
    const val GRID = 44f
    const val LINE = 1.8f
    const val HEAD_R = 3f
    const val HALO_R = 7.5f
    const val HALO_ALPHA = 0.2f
    const val AREA_FILL_LIGHT = 0.045f
    const val AREA_FILL_DARK = 0.07f
    const val AREA_EDGE_ALPHA = 0.3f
    const val AREA_EDGE = 1f

    /** inner box (fitting area) */
    val innerW: Double get() = (BOX_W - 2 * BOX_PAD).toDouble()
    val innerH: Double get() = (BOX_H - 2 * BOX_PAD).toDouble()
}

/** colors of one look (the app's chosen paper) */
class ReplayColors(val paper: Int, val ink: Int, val text: Int, val textSoft: Int, val dark: Boolean) {
    val grid: Int get() = if (dark) 0x0DFFFFFF else 0x10000000
}

class ReplayFonts(val medium: Typeface, val semibold: Typeface, val bold: Typeface)

/** paths of finished steps per level, in world coordinates (built once) */
class ReplayPaths(scene: ReplayScene) {
    /** [step][level 1..5] */
    val done: List<Array<Path?>> = scene.steps.map { step -> pathsOf(step, step.count) }
    val area: Path? =
        scene.area?.let { a ->
            Path().apply {
                fillType = Path.FillType.EVEN_ODD
                for (r in a.rings) {
                    if (r.size < 6) continue
                    moveTo(r[0], r[1])
                    var k = 2
                    while (k + 1 < r.size) {
                        lineTo(r[k], r[k + 1])
                        k += 2
                    }
                    close()
                }
            }
        }

    companion object {
        /** segments [0, upto) of a step by level (+ a partial last one up to length a when given) */
        fun pathsOf(step: ReplayStep, upto: Int, partialEnd: Pair<Float, Float>? = null): Array<Path?> {
            val out = arrayOfNulls<Path>(6)
            val xy = step.xy
            for (i in 0 until upto) {
                val lv = step.draw[i].toInt()
                if (lv <= 0) continue
                val p = out[lv] ?: Path().also { out[lv] = it }
                val last = i == upto - 1 && partialEnd != null
                val x1 = if (last) partialEnd!!.first else xy[4 * i + 2]
                val y1 = if (last) partialEnd!!.second else xy[4 * i + 3]
                if (!step.joined[i] || i == 0) p.moveTo(xy[4 * i], xy[4 * i + 1])
                p.lineTo(x1, y1)
            }
            return out
        }
    }
}

object ReplayArt {
    private const val MAPLIBRE_EARTH_M = 2 * Math.PI * 6378137
    private const val MEASURE_K = 32f

    private fun argb(rgb: Int, alpha: Float = 1f): Int = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private fun textPaint(face: Typeface, sizePx: Float, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = sizePx
            this.color = color
            isSubpixelText = true
            // like a browser canvas: no hinting, advances scale linearly (hinted Android text came out ~3% narrower than the viewer)
            isLinearText = true
            hinting = Paint.HINTING_OFF
        }

    private fun advance(face: Typeface, size: Float, text: String): Float = textPaint(face, size * MEASURE_K, 0).measureText(text) / MEASURE_K

    /**
     * line widths per level (index 1..5, pt) at camera scale s (pt per m). 발자취: level 1 = clamp(18·s, 0.6, 1.8) — thinner when 1 pt covers
     * more than ~10 m of a walk. Car maps are ~10× wider (a commute is 25 km, a trip 150 km) and roads lie farther apart, so the car rule is
     * clamp(200·s, 1.2, 1.8): full 1.8 up to ~110 m per pt, never below 1.2 (thinner reads as a hairline at the car's distance). More per level by the map's ratio.
     */
    fun levelWidths(s: Double, lat0: Double): FloatArray {
        val base = (200 * s).coerceIn(1.2, ReplayBoard.LINE.toDouble())
        val zoom = ln(max(1e-9, s) * MAPLIBRE_EARTH_M * max(0.01, cos(Math.toRadians(lat0))) / 512.0) / ln(2.0)
        val (b, st) = levelWidthAt(zoom)
        val ratio = if (b > 0) st / b else 0.0
        return FloatArray(6) { if (it == 0) base.toFloat() else (base * (1 + ratio * (it - 1))).toFloat() }
    }

    /**
     * One frame at time t (ms) on [c] at [k] px per board pt, origin = the board's top-left. [paths] = ReplayPaths(scene).
     */
    fun draw(c: Canvas, scene: ReplayScene, paths: ReplayPaths, t: Double, k: Float, colors: ReplayColors, fonts: ReplayFonts) {
        val B = ReplayBoard
        c.save()
        c.clipRect(0f, 0f, B.W * k, B.H * k)
        // paper + grid (1-pixel lines, from the board's centre)
        c.drawRect(0f, 0f, B.W * k, B.H * k, Paint().apply { color = argb(colors.paper) })
        val grid = Paint().apply {
            color = colors.grid
            strokeWidth = 0f
            style = Paint.Style.STROKE
        }
        var gx = B.W / 2 - kotlin.math.floor(B.W / 2 / B.GRID) * B.GRID
        while (gx <= B.W) {
            val x = (gx * k).roundToInt() + 0.5f
            c.drawLine(x, 0f, x, B.H * k, grid)
            gx += B.GRID
        }
        var gy = B.H / 2 - kotlin.math.floor(B.H / 2 / B.GRID) * B.GRID
        while (gy <= B.H) {
            val y = (gy * k).roundToInt() + 0.5f
            c.drawLine(0f, y, B.W * k, y, grid)
            gy += B.GRID
        }

        // camera: world → board pt → px
        val view = scene.camera.at(t)
        val s = B.innerW / view.w // pt per m
        val m = Matrix()
        m.setTranslate((-view.cx).toFloat(), (-view.cy).toFloat())
        m.postScale((s * k).toFloat(), (s * k).toFloat())
        m.postTranslate((B.BOX_X + B.BOX_W / 2) * k, (B.BOX_Y + B.BOX_H / 2) * k)
        val toPx = (s * k).toFloat()

        // map layer (area + lines + head), faded at the box's top and bottom so moving lines never run into the text
        c.saveLayer(0f, 0f, B.W * k, B.H * k, null)
        paths.area?.let { area ->
            c.save()
            c.concat(m)
            c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(colors.ink, if (colors.dark) B.AREA_FILL_DARK else B.AREA_FILL_LIGHT) })
            c.drawPath(
                area,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = B.AREA_EDGE * k / toPx
                    color = argb(colors.ink, B.AREA_EDGE_ALPHA)
                },
            )
            c.restore()
        }
        val tl = scene.timeline
        val n = scene.steps.size
        val kStep = tl.stepAt(t)
        if (kStep >= 0 && n > 0) {
            val widths = levelWidths(s, scene.projection.lat0)
            val done = if (kStep >= n) n else if (t >= tl.drawEnds[kStep]) kStep + 1 else kStep
            val h = min(kStep, n - 1)
            val drawing = kStep < n && t < tl.drawEnds[h]
            val f = if (drawing) tl.progress(h, t) else 0.0
            val step = scene.steps[h]
            val headA = (f * step.length).toFloat()
            var growing: Array<Path?>? = null
            var head: Pair<Float, Float>? = null
            if (drawing && f > 0) {
                val i = step.indexAt(headA)
                head = step.pointAt(headA)
                growing = ReplayPaths.pathsOf(step, i + 1, head)
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = argb(colors.ink)
            }
            c.save()
            c.concat(m)
            for (level in 1..5) {
                stroke.strokeWidth = widths[level] * k / toPx
                for (j in 0 until done) paths.done[j][level]?.let { c.drawPath(it, stroke) }
                growing?.get(level)?.let { c.drawPath(it, stroke) }
            }
            c.restore()
            // head dot: the drawing tip; after a step its last point fades (360 ms)
            var alpha = 0f
            var at: Pair<Float, Float>? = null
            if (drawing && head != null) {
                alpha = 1f
                at = head
            } else if (!drawing) {
                val fade = 1 - (t - tl.drawEnds[h]) / ReplayRules.SETTLE_MS
                if (fade > 0 && step.count > 0) {
                    alpha = fade.toFloat()
                    at = step.xy[4 * (step.count - 1) + 2] to step.xy[4 * (step.count - 1) + 3]
                }
            }
            if (at != null && alpha > 0) {
                val pt = floatArrayOf(at.first, at.second)
                m.mapPoints(pt)
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(colors.ink, B.HALO_ALPHA * alpha) }
                c.drawCircle(pt[0], pt[1], B.HALO_R * k, dot)
                dot.color = argb(colors.ink, alpha)
                c.drawCircle(pt[0], pt[1], B.HEAD_R * k, dot)
            }
        }
        fade(c, k)
        c.restore()

        // text: date line · counting number · '처음 달린 길' line · lockup
        val cx = B.W / 2 * k
        val label = scene.labelAt(t)
        val dateSize = fitSize(fonts.medium, B.DATE_SIZE, scene.steps.map { it.label } + label, B.W - 48f)
        c.drawText(label, cx, B.DATE_Y * k, textPaint(fonts.medium, dateSize * k, argb(colors.textSoft)).apply { textAlign = Paint.Align.CENTER })
        val (driven, fresh) = scene.numbersAt(t)
        drawCounter(c, k, fonts, colors, driven, decimalsFor(scene.totalDrivenM), cx)
        val small = "처음 달린 길 ${kmCount(fresh, decimalsFor(scene.totalFreshM))}km"
        c.drawText(small, cx, B.LABEL_Y * k, textPaint(fonts.medium, B.LABEL_SIZE * k, argb(colors.textSoft)).apply { textAlign = Paint.Align.CENTER })
        val fontsCard = com.odomap.app.share.CardFonts(fonts.medium, fonts.bold)
        val lw = ShareCardArt.lockupWidth(fontsCard, B.BRAND_H)
        ShareCardArt.drawLockup(c, k, fontsCard, B.W - B.BRAND_RIGHT - lw, B.BRAND_CY, B.BRAND_H, argb(colors.text, 0.92f), argb(colors.text, 0.92f))
        c.restore()
    }

    /** soft edges of the map layer (DST_IN): top 182→204, bottom 574→594 */
    private fun fade(c: Canvas, k: Float) {
        val B = ReplayBoard
        val p = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader =
                LinearGradient(
                    0f, 0f, 0f, B.H * k,
                    intArrayOf(0, 0, -0x1000000, -0x1000000, 0, 0),
                    floatArrayOf(0f, 182f / B.H, 204f / B.H, 574f / B.H, 594f / B.H, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
        c.drawRect(0f, 0f, B.W * k, B.H * k, p)
    }

    /** one size for every label of the replay (the widest fits) — the line does not change size between steps */
    private fun fitSize(face: Typeface, size: Float, labels: List<String>, room: Float): Float {
        val widest = labels.maxOfOrNull { advance(face, size, it) } ?: 0f
        return if (widest > room) max(12f, size * room / widest) else size
    }

    fun decimalsFor(totalM: Double): Int = if (totalM >= 100_000) 1 else 2

    /** m → "12.34" with fixed decimals (floored like formatDistanceKm) */
    fun kmCount(m: Double, decimals: Int): String {
        val div = if (decimals == 2) 10.0 else 100.0
        val units = if (m > 0) kotlin.math.floor(m / div + 1e-7).toLong() else 0L
        val per = if (decimals == 2) 100 else 10
        return "${units / per}.${(units % per).toString().padStart(decimals, '0')}"
    }

    /** big number: each digit centred in a fixed cell (the line never wobbles), centred as a whole with ' km' */
    private fun drawCounter(c: Canvas, k: Float, fonts: ReplayFonts, colors: ReplayColors, meters: Double, decimals: Int, cx: Float) {
        val B = ReplayBoard
        val text = kmCount(meters, decimals)
        val digitW = FloatArray(10) { advance(fonts.semibold, B.NUMBER_SIZE, it.toString()) }
        val cell = digitW.max()
        val dotW = advance(fonts.semibold, B.NUMBER_SIZE, ".")
        val unitW = advance(fonts.medium, B.UNIT_SIZE, "km")
        val gap = cell * 0.18f
        val digits = text.count { it.isDigit() }
        val total = digits * cell + dotW + gap + unitW
        var x = cx / k - total / 2
        val np = textPaint(fonts.semibold, B.NUMBER_SIZE * k, argb(colors.text))
        for (ch in text) {
            if (ch == '.') {
                c.drawText(".", x * k, B.NUMBER_Y * k, np)
                x += dotW
            } else {
                val d = ch - '0'
                c.drawText(ch.toString(), (x + (cell - digitW[d]) / 2) * k, B.NUMBER_Y * k, np)
                x += cell
            }
        }
        c.drawText("km", (x + gap) * k, B.NUMBER_Y * k, textPaint(fonts.medium, B.UNIT_SIZE * k, argb(colors.textSoft)))
    }
}
