package com.odomap.app.share

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.odomap.app.R
import com.odomap.core.share.ShareCard
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The share card, drawn natively with the SAME layout as the phone viewer — a line-by-line port of web/assets/card.js (drawExploreCard),
// web/assets/format.js (card text) and the values of web/assets/tokens.js. The car shows exactly the picture the phone will make from the QR:
// the preview is drawn from the card as encoded (ShareQr.card — simplified and quantized, what the viewer decodes).
// Design units: card width 360 (square 360×360, story 360×640); [ShareCardArt.draw] scales them to any pixel size.

enum class CardRatio(val id: String, val label: String, val height: Float) {
    SQUARE("square", "정사각", 360f),
    STORY("story", "스토리", 640f),
}

/** card backgrounds (tokens.js LOOKS: 종이 = paper + 44 grid, 흰색 = white without grid, both with the paper look's ink) */
enum class CardLook(val id: String, val label: String, val paper: Int, val grid: Boolean) {
    PAPER("paper", "종이", 0xF7F7F5, true),
    WHITE("white", "흰색", 0xFFFFFF, false),
    ;

    val ink: Int get() = 0x262626
    val text: Int get() = 0x1C1C1E
    val textSoft: Int get() = 0x8A8A8E
}

/** tokens.js `card` */
object CardTokens {
    const val DESIGN_W = 360f
    const val EXPORT_W = 1080

    class Frame(val padX: Float, val top: Float, val bottom: Float, val gap: Float)

    fun frame(r: CardRatio) = if (r == CardRatio.STORY) Frame(28f, 68f, 76f, 28f) else Frame(24f, 24f, 24f, 18f)

    const val DATE = 13f
    const val TITLE = 17f
    const val TITLE_MIN = 12f
    const val HERO = 40f
    const val UNIT = 18f
    const val UNIT_GAP = 6f
    const val LABEL = 13f
    const val STATS = 13f
    const val STATS_MIN = 11f
    const val BRAND = 13f
    const val INK_DIGIT = 0.72f
    const val INK_HANGUL = 0.8f
    const val TITLE_GAP = 9f
    const val LABEL_GAP = 22f
    const val HERO_GAP = 9f
    const val TITLE_MAX_CHARS = 20
    const val LINE_WALKED = 2f
    const val LINE_ALPHA = 0.92f
    const val MIN_SPAN_M = 400.0
    const val SMOOTH_STEP = 1.5f
    const val SMOOTH_PASSES = 3
    val WIDTH_STOPS = arrayOf(doubleArrayOf(8.0, 1.9, 0.35), doubleArrayOf(12.0, 1.7, 0.6), doubleArrayOf(16.0, 1.4, 1.1), doubleArrayOf(19.0, 2.2, 1.8))
    const val BRAND_WORD = "대동차지도"
    const val BRAND_GAP_K = 0.36f
    const val BRAND_WORD_K = 0.98f
    const val BRAND_ROOM = 14f
    const val REGION_OUTLINE = 0.5f
    const val REGION_OUTLINE_ALPHA = 0.18f
    const val REGION_FILL_ALPHA = 0.85f
    const val REGION_EDGE = 0.5f
    const val REGION_EDGE_ALPHA = 0.9f
    const val CREDIT_SIZE = 9f
    const val CREDIT_TEXT = "지역 경계 © 통계청 SGIS · vuski/admdongkor CC BY 4.0"
    const val GRID_CELL = 44f
    const val GRID_LINE = 0.5f
    const val GRID_COLOR = 0x10000000 // rgba(0, 0, 0, 0.063)
    const val REGION_COUNT = 230
}

// ───────────────────────── text (format.js)

private val WEEKDAYS = arrayOf("일", "월", "화", "수", "목", "금", "토")

/** dateDays (since 2020-01-01, Korean date) → date */
fun shareDate(dateDays: Int): LocalDate = LocalDate.of(2020, 1, 1).plusDays(dateDays.toLong())

/** "2026.10.31 토" */
fun cardDateLabel(dateDays: Int): String {
    val d = shareDate(dateDays)
    return "${d.year}.${d.monthValue}.${d.dayOfMonth} ${WEEKDAYS[d.dayOfWeek.value % 7]}"
}

/** 발자취 formatDistanceKm: under 100 km two decimals (floored), from 100 km one decimal (floored) */
fun formatDistanceKm(meters: Double): String {
    if (!(meters > 0)) return "0.00"
    val hundredths = floor(meters / 10 + 1e-7).toLong()
    if (hundredths < 10000) return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    val tenths = floor(meters / 100 + 1e-7).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** "1분 미만" · "38분" · "1시간 39분" · "2시간" */
fun minutesText(seconds: Int): String {
    val minutes = Math.round(max(0, seconds) / 60.0).toInt()
    if (minutes < 1) return "1분 미만"
    if (minutes < 60) return "${minutes}분"
    val h = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "${h}시간" else "${h}시간 ${rest}분"
}

private fun withCommas(v: Int): String = String.format(java.util.Locale.US, "%,d", v)

/** 한마디: whitespace runs → one space, trimmed, at most [max] code points */
fun cardTitleText(raw: String?, maxChars: Int = CardTokens.TITLE_MAX_CHARS): String {
    val flat = (raw ?: "").replace(Regex("\\s+"), " ").trim()
    val cps = flat.codePoints().toArray()
    return if (cps.size > maxChars) String(cps, 0, maxChars).trim() else flat
}

class CardNumbers(val hero: String, val unit: String, val label: String, val stats: String)

/** format.js cardNumbers */
fun cardNumbers(card: ShareCard): CardNumbers =
    when (card) {
        is ShareCard.Regions -> {
            val parts = ArrayList<String>()
            if (card.distanceM > 0) parts.add("${formatDistanceKm(card.distanceM)}km")
            if (card.driveCount > 0) parts.add("드라이브 ${withCommas(card.driveCount)}번")
            CardNumbers("${if (card.visitedTotal > 0) card.visitedTotal else card.visited.size}", "/${CardTokens.REGION_COUNT}곳", "가 본 시·군·구", parts.joinToString(" · "))
        }
        is ShareCard.Atlas -> {
            val parts = ArrayList<String>()
            if (card.distanceM > 0) parts.add("${formatDistanceKm(card.distanceM)}km")
            if (card.driveCount > 0) parts.add("드라이브 ${withCommas(card.driveCount)}번")
            CardNumbers(formatDistanceKm(card.pioneeredM), "km", "내 지도", parts.joinToString(" · "))
        }
        is ShareCard.Drive -> {
            val pioneered = card.pioneeredM >= 10
            val parts = ArrayList<String>()
            if (pioneered) parts.add("${formatDistanceKm(card.distanceM)}km")
            parts.add(minutesText(card.durationS))
            if (card.newRegions.isNotEmpty()) parts.add("첫 방문 ${card.newRegions.size}곳")
            val stats = parts.joinToString(" · ")
            if (!pioneered) CardNumbers(formatDistanceKm(card.distanceM), "km", "달린 길", stats)
            else CardNumbers("+${formatDistanceKm(card.pioneeredM)}", "km", "처음 달린 길", stats)
        }
    }

// ───────────────────────── fonts & outline

/** Pretendard Medium (500) · Bold (700) — the viewer's 'Odomap Sans' weights */
class CardFonts(val medium: Typeface, val bold: Typeface) {
    companion object {
        @Volatile private var cached: CardFonts? = null

        fun of(context: Context): CardFonts =
            cached ?: CardFonts(
                ResourcesCompat.getFont(context, R.font.pretendard_medium) ?: Typeface.DEFAULT,
                ResourcesCompat.getFont(context, R.font.pretendard_bold) ?: Typeface.DEFAULT_BOLD,
            ).also { cached = it }
    }
}

/** web/data/outline.json (the viewer's region outlines, copied to assets/share): per region number, rings of [lat0, lng0, lat1, lng1, …] degrees */
class CardOutline(val regions: List<List<DoubleArray>>) {
    companion object {
        @Volatile private var cached: CardOutline? = null

        fun load(context: Context): CardOutline? =
            cached ?: runCatching { parse(context.assets.open("share/outline.json").bufferedReader().use { it.readText() }) }.getOrNull()?.also { cached = it }

        fun parse(json: String): CardOutline {
            val o = JSONObject(json)
            val scale = o.getDouble("scale")
            val ra = o.getJSONArray("regions")
            val out = ArrayList<List<DoubleArray>>(ra.length())
            for (i in 0 until ra.length()) {
                val polys = ra.getJSONArray(i)
                val rings = ArrayList<DoubleArray>()
                for (p in 0 until polys.length()) {
                    val pr = polys.getJSONArray(p)
                    for (r in 0 until pr.length()) {
                        val flat = pr.getJSONArray(r)
                        val pts = DoubleArray(flat.length())
                        var lat = 0.0
                        var lng = 0.0
                        var k = 0
                        while (k + 1 < flat.length()) {
                            lat += flat.getDouble(k)
                            lng += flat.getDouble(k + 1)
                            pts[k] = lat / scale
                            pts[k + 1] = lng / scale
                            k += 2
                        }
                        rings.add(pts)
                    }
                }
                out.add(rings)
            }
            return CardOutline(out)
        }
    }
}

// ───────────────────────── layout & fit (card.js cardLayout / cardFit)

class CardLayout(
    val ratio: CardRatio,
    val width: Float,
    val height: Float,
    val padX: Float,
    val dateTop: Float,
    val titleBaseline: Float?,
    val box: RectF,
    val heroBaseline: Float,
    val labelBaseline: Float,
    val statsBaseline: Float,
)

fun cardLayout(ratio: CardRatio, hasTitle: Boolean): CardLayout {
    val t = CardTokens
    val width = t.DESIGN_W
    val height = ratio.height
    val frame = t.frame(ratio)
    val dateTop = frame.top
    val dateBottom = dateTop + t.DATE * t.INK_HANGUL
    val titleBaseline = if (hasTitle) dateBottom + t.TITLE_GAP + t.TITLE * t.INK_HANGUL else null
    val headBottom = titleBaseline ?: dateBottom
    val statsBaseline = height - frame.bottom - 1
    val labelBaseline = statsBaseline - t.LABEL_GAP
    val heroBaseline = labelBaseline - t.LABEL * t.INK_HANGUL - t.HERO_GAP
    val heroTop = heroBaseline - t.HERO * t.INK_DIGIT
    val top = headBottom + frame.gap
    val bottom = heroTop - frame.gap
    return CardLayout(ratio, width, height, frame.padX, dateTop, titleBaseline, RectF(frame.padX, top, width - frame.padX, top + max(40f, bottom - top)), heroBaseline, labelBaseline, statsBaseline)
}

private const val EARTH_RADIUS_M = 6371008.8
private const val M_PER_DEG = EARTH_RADIUS_M * Math.PI / 180
private const val MAPLIBRE_EARTH_M = 2 * Math.PI * 6378137
private const val MAPLIBRE_TILE_PT = 512.0

class CardFit(val midLat: Double, val midLng: Double, val kx: Double, val ky: Double, val scale: Double, val cx: Double, val cy: Double) {
    fun x(lng: Double): Float = (cx + (lng - midLng) * kx * scale).toFloat()

    fun y(lat: Double): Float = (cy - (lat - midLat) * ky * scale).toFloat()

    /** equivalent MapLibre zoom (1 design unit = 1 pt) */
    val zoom: Double get() = log2(max(1e-9, scale) * MAPLIBRE_EARTH_M * max(0.01, cos(Math.toRadians(midLat))) / MAPLIBRE_TILE_PT)
}

private fun log2(v: Double): Double = ln(v) / ln(2.0)

/** bounds [minLat, maxLat, minLng, maxLng] → fit (north up, equirectangular around the middle latitude) */
fun cardFit(b: DoubleArray?, box: RectF, minSpanM: Double): CardFit? {
    if (b == null || box.width() <= 0 || box.height() <= 0) return null
    val midLat = (b[0] + b[1]) / 2
    val midLng = (b[2] + b[3]) / 2
    val kx = M_PER_DEG * cos(Math.toRadians(midLat))
    val ky = M_PER_DEG
    val spanX = max((b[3] - b[2]) * kx, minSpanM)
    val spanY = max((b[1] - b[0]) * ky, minSpanM)
    return CardFit(midLat, midLng, kx, ky, min(box.width() / spanX, box.height() / spanY), (box.left + box.width() / 2).toDouble(), (box.top + box.height() / 2).toDouble())
}

/** (base, step) of the map line widths at a MapLibre zoom (EXPLORE_LEVEL_WIDTH_STOPS, linear between stops) */
fun levelWidthAt(zoom: Double): Pair<Double, Double> {
    val stops = CardTokens.WIDTH_STOPS
    val first = stops.first()
    val last = stops.last()
    if (!(zoom > first[0])) return first[1] to first[2]
    if (zoom >= last[0]) return last[1] to last[2]
    var i = 1
    while (stops[i][0] < zoom) i += 1
    val a = stops[i - 1]
    val c = stops[i]
    val u = (zoom - a[0]) / (c[0] - a[0])
    return (a[1] + (c[1] - a[1]) * u) to (a[2] + (c[2] - a[2]) * u)
}

/** line width per level 1–5 (index 0 unused): level 1 = walked width, each level thicker by the map's ratio */
fun cardLevelWidths(fit: CardFit?): FloatArray {
    val walked = CardTokens.LINE_WALKED
    val (base, step) = levelWidthAt(fit?.zoom ?: 12.0)
    val out = FloatArray(6)
    out[0] = walked
    for (level in 1..5) out[level] = (Math.round(walked * ((base + step * (level - 1)) / base) * 100) / 100.0).toFloat()
    return out
}

/** card.js smoothLine: resample every [step] units, [1,2,1]/4 × passes, ends fixed */
fun smoothLine(xy: FloatArray, step: Float = CardTokens.SMOOTH_STEP, passes: Int = CardTokens.SMOOTH_PASSES): FloatArray {
    val n = xy.size / 2
    if (n < 3 || step <= 0 || passes <= 0) return xy
    val dense = ArrayList<Float>(xy.size * 4)
    dense.add(xy[0])
    dense.add(xy[1])
    for (i in 1 until n) {
        val ax = xy[2 * i - 2]
        val ay = xy[2 * i - 1]
        val bx = xy[2 * i]
        val by = xy[2 * i + 1]
        val k = max(1, ceil(hypot((bx - ax).toDouble(), (by - ay).toDouble()) / step).toInt())
        for (j in 1..k) {
            dense.add(ax + (bx - ax) * j / k)
            dense.add(ay + (by - ay) * j / k)
        }
    }
    var cur = dense.toFloatArray()
    val m = cur.size / 2
    repeat(passes) {
        val next = cur.copyOf()
        for (i in 1 until m - 1) {
            next[2 * i] = (cur[2 * i - 2] + 2 * cur[2 * i] + cur[2 * i + 2]) / 4
            next[2 * i + 1] = (cur[2 * i - 1] + 2 * cur[2 * i + 1] + cur[2 * i + 3]) / 4
        }
        cur = next
    }
    return cur
}

// ───────────────────────── scene

class CardLine(val xy: FloatArray, val level: Int)

class CardRegions(val all: Path, val visited: Path)

/** Everything needed to draw one card (card.js cardScene) */
class CardScene(
    val card: ShareCard,
    val look: CardLook,
    val layout: CardLayout,
    val date: String,
    val title: String?,
    val numbers: CardNumbers,
    /** in draw order (thin under thick) */
    val lines: List<CardLine>,
    val widths: FloatArray,
    val regions: CardRegions?,
)

fun cardScene(card: ShareCard, ratio: CardRatio, look: CardLook, outline: CardOutline?): CardScene {
    val numbers = cardNumbers(card)
    var title = cardTitleText(card.title)
    if (title == numbers.label) title = ""
    val layout = cardLayout(ratio, title.isNotEmpty())
    val date = cardDateLabel(card.dateDays)
    if (card is ShareCard.Regions) {
        var regions: CardRegions? = null
        if (outline != null) {
            var b: DoubleArray? = null
            for (rings in outline.regions) for (r in rings) b = extend(b, r)
            val fit = cardFit(b, layout.box, 0.0)
            if (fit != null) {
                val visited = card.visited.toSet()
                val all = Path()
                val hit = Path().apply { fillType = Path.FillType.EVEN_ODD }
                outline.regions.forEachIndexed { i, rings ->
                    for (r in rings) {
                        ring(all, r, fit)
                        if (i in visited) ring(hit, r, fit)
                    }
                }
                regions = CardRegions(all, hit)
            }
        }
        return CardScene(card, look, layout, date, title.ifEmpty { null }, numbers, emptyList(), cardLevelWidths(null), regions)
    }
    val src =
        when (card) {
            is ShareCard.Drive -> card.lines
            is ShareCard.Atlas -> card.lines
            else -> emptyList()
        }.filter { it.points.size >= 2 }
    var b: DoubleArray? = null
    for (l in src) for (p in l.points) b = extendPoint(b, p.lat, p.lng)
    val fit = cardFit(b, layout.box, CardTokens.MIN_SPAN_M)
    val widths = cardLevelWidths(fit)
    val lines =
        if (fit == null) emptyList()
        else src.mapIndexed { i, l ->
            val xy = FloatArray(l.points.size * 2)
            l.points.forEachIndexed { k, p ->
                xy[2 * k] = fit.x(p.lng)
                xy[2 * k + 1] = fit.y(p.lat)
            }
            Triple(i, l.level, CardLine(smoothLine(xy), l.level))
        }.sortedWith(compareBy({ it.second }, { it.first })).map { it.third }
    return CardScene(card, look, layout, date, title.ifEmpty { null }, numbers, lines, widths, null)
}

private fun extendPoint(b: DoubleArray?, lat: Double, lng: Double): DoubleArray {
    if (!lat.isFinite() || !lng.isFinite()) return b ?: doubleArrayOf(Double.NaN)
    if (b == null || b.size < 4) return doubleArrayOf(lat, lat, lng, lng)
    if (lat < b[0]) b[0] = lat
    if (lat > b[1]) b[1] = lat
    if (lng < b[2]) b[2] = lng
    if (lng > b[3]) b[3] = lng
    return b
}

private fun extend(b: DoubleArray?, ring: DoubleArray): DoubleArray? {
    var out = b
    var k = 0
    while (k + 1 < ring.size) {
        out = extendPoint(out, ring[k], ring[k + 1])
        k += 2
    }
    return out
}

private fun ring(path: Path, r: DoubleArray, fit: CardFit) {
    if (r.size < 6) return
    path.moveTo(fit.x(r[1]), fit.y(r[0]))
    var k = 2
    while (k + 1 < r.size) {
        path.lineTo(fit.x(r[k + 1]), fit.y(r[k]))
        k += 2
    }
    path.close()
}

// ───────────────────────── drawing (card.js drawCard)

object ShareCardArt {
    private const val MEASURE_K = 32f

    private class Ink(val left: Float, val width: Float, val ascent: Float, val descent: Float)

    private fun paint(face: Typeface, size: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            isSubpixelText = true
            // like a browser canvas: no hinting, advances scale linearly (hinted Android text came out ~3% narrower than the viewer)
            isLinearText = true
            hinting = Paint.HINTING_OFF
        }

    /** ink box of text at [size] design units (measured large so the integer bounds are precise) */
    private fun ink(face: Typeface, size: Float, text: String): Ink {
        if (text.isEmpty()) return Ink(0f, 0f, 0f, 0f)
        val p = paint(face, size * MEASURE_K)
        val r = Rect()
        p.getTextBounds(text, 0, text.length, r)
        return Ink(r.left / MEASURE_K, (r.right - r.left) / MEASURE_K, -r.top / MEASURE_K, r.bottom / MEASURE_K)
    }

    /** text whose ink starts exactly at x (design units) on a canvas in pixels (k px per unit); returns the ink box */
    private fun drawLeft(c: Canvas, k: Float, text: String, x: Float, baseline: Float, face: Typeface, size: Float, color: Int): Ink {
        val m = ink(face, size, text)
        if (text.isNotEmpty()) {
            val p = paint(face, size * k).apply { this.color = color }
            c.drawText(text, (x - m.left) * k, baseline * k, p)
        }
        return m
    }

    fun lockupWidth(fonts: CardFonts, h: Float): Float = h + h * CardTokens.BRAND_GAP_K + ink(fonts.bold, h * CardTokens.BRAND_WORD_K, CardTokens.BRAND_WORD).width

    /** 대동차지도 mark: ink ring with a center dot */
    fun markPath(cx: Float, cy: Float, r: Float): Path =
        Path().apply {
            fillType = Path.FillType.WINDING
            addCircle(cx, cy, r, Path.Direction.CW)
            addCircle(cx, cy, r * 0.7f, Path.Direction.CCW)
            addCircle(cx, cy, r * 0.36f, Path.Direction.CW)
        }

    /** lockup (mark + word) with its left edge at x, vertically centered on cy (design units) */
    fun drawLockup(c: Canvas, k: Float, fonts: CardFonts, x: Float, cy: Float, h: Float, markColor: Int, wordColor: Int) {
        c.save()
        c.scale(k, k)
        c.drawPath(markPath(x + h / 2, cy, h / 2), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markColor })
        c.restore()
        val size = h * CardTokens.BRAND_WORD_K
        // Hangul wordmark: center the word's own ink box on the mark (card.js drawLockup)
        val word = ink(fonts.bold, size, CardTokens.BRAND_WORD)
        drawLeft(c, k, CardTokens.BRAND_WORD, x + h + h * CardTokens.BRAND_GAP_K, cy + (word.ascent - word.descent) / 2, fonts.bold, size, wordColor)
    }

    private fun opaque(rgb: Int, alpha: Float = 1f): Int = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    /** grid line positions along a side, centered so both margins match */
    fun gridPositions(length: Float, cell: Float): List<Float> {
        val out = ArrayList<Float>()
        if (!(length > 0) || !(cell > 0)) return out
        var at = (length % cell) / 2
        while (at <= length + 1e-4f) {
            out.add(Math.round(at * 1000) / 1000f)
            at += cell
        }
        return out
    }

    /**
     * Draws the finished card on [c], whose drawing area is the card at [k] pixels per design unit, origin at the card's top-left.
     */
    fun draw(c: Canvas, scene: CardScene, k: Float, fonts: CardFonts) {
        val L = scene.layout
        val look = scene.look
        val W = L.width
        val H = L.height
        // paper (+ grid: one fill, crossings painted once)
        c.save()
        c.scale(k, k)
        c.drawRect(0f, 0f, W, H, Paint().apply { color = opaque(look.paper) })
        if (look.grid) {
            val g = Path()
            val half = CardTokens.GRID_LINE / 2
            for (x in gridPositions(W, CardTokens.GRID_CELL)) g.addRect(x - half, 0f, x + half, H, Path.Direction.CW)
            for (y in gridPositions(H, CardTokens.GRID_CELL)) g.addRect(0f, y - half, W, y + half, Path.Direction.CW)
            g.fillType = Path.FillType.WINDING
            c.drawPath(g, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CardTokens.GRID_COLOR })
        }
        c.restore()

        val regions = scene.regions
        if (regions != null) {
            c.save()
            c.scale(k, k)
            c.drawPath(regions.visited, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque(look.ink, CardTokens.REGION_FILL_ALPHA) })
            c.drawPath(
                regions.all,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = CardTokens.REGION_OUTLINE
                    color = opaque(look.ink, CardTokens.REGION_OUTLINE_ALPHA)
                },
            )
            c.drawPath(
                regions.visited,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = CardTokens.REGION_EDGE
                    color = opaque(look.paper, CardTokens.REGION_EDGE_ALPHA)
                },
            )
            c.restore()
        } else if (scene.lines.isNotEmpty()) {
            // one opaque layer, composited once at the line alpha (overlaps never darken)
            c.saveLayerAlpha(0f, 0f, W * k, H * k, (CardTokens.LINE_ALPHA * 255).roundToInt())
            c.scale(k, k)
            val p =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = opaque(look.ink)
                }
            for (line in scene.lines) {
                p.strokeWidth = scene.widths[line.level.coerceIn(1, 5)]
                val path = Path()
                path.moveTo(line.xy[0], line.xy[1])
                var i = 2
                while (i + 1 < line.xy.size) {
                    path.lineTo(line.xy[i], line.xy[i + 1])
                    i += 2
                }
                c.drawPath(path, p)
            }
            c.restore()
        }
        drawText(c, scene, k, fonts)
    }

    private fun drawText(c: Canvas, scene: CardScene, k: Float, fonts: CardFonts) {
        val t = CardTokens
        val L = scene.layout
        val look = scene.look
        val W = L.width
        val x0 = L.padX
        val text = opaque(look.text)
        val soft = opaque(look.textSoft)

        // top: date (ink top at dateTop) · 한마디
        val dm = ink(fonts.medium, t.DATE, scene.date.ifEmpty { "0" })
        drawLeft(c, k, scene.date, x0, L.dateTop + dm.ascent, fonts.medium, t.DATE, soft)
        val title = scene.title
        if (title != null && L.titleBaseline != null) {
            val (fitted, size) = fitTitle(fonts, title, W - x0 * 2)
            drawLeft(c, k, fitted, x0, L.titleBaseline, fonts.bold, size, text)
        }

        // bottom left: big number + unit · label
        val n = scene.numbers
        val hero = drawLeft(c, k, n.hero, x0, L.heroBaseline, fonts.bold, t.HERO, text)
        drawLeft(c, k, n.unit, x0 + hero.width + t.UNIT_GAP, L.heroBaseline, fonts.bold, t.UNIT, soft)
        drawLeft(c, k, n.label, x0, L.labelBaseline, fonts.medium, t.LABEL, soft)

        // bottom right lockup; the stats line shrinks (to statsMin) instead of touching it
        val brandH = t.BRAND
        val brandW = lockupWidth(fonts, brandH)
        val room = W - x0 * 2 - brandW - t.BRAND_ROOM
        var statsSize = t.STATS
        val statsWidth = ink(fonts.medium, statsSize, n.stats).width
        if (statsWidth > room && statsWidth > 0) statsSize = max(t.STATS_MIN, t.STATS * room / statsWidth)
        val stats = drawLeft(c, k, n.stats, x0, L.statsBaseline, fonts.medium, statsSize, text)
        val brandCy = if (stats.ascent + stats.descent > 0) L.statsBaseline + (stats.descent - stats.ascent) / 2 else L.statsBaseline - t.STATS * 0.36f
        drawLockup(c, k, fonts, W - x0 - brandW, brandCy, brandH, opaque(look.ink), soft)

        // kind 3: source credit in the box's bottom-right corner
        if (scene.regions != null) {
            val p = paint(fonts.medium, t.CREDIT_SIZE * k).apply {
                color = soft
                textAlign = Paint.Align.RIGHT
            }
            val parts = t.CREDIT_TEXT.split(" · ")
            val right = L.box.right
            val bottom = L.box.bottom
            c.drawText(parts.getOrElse(1) { "" }, right * k, bottom * k, p)
            c.drawText(parts[0], right * k, (bottom - t.CREDIT_SIZE * 1.25f) * k, p)
        }
    }

    /** 한마디 on one line: shrink to fit (down to titleMin), then ellipsis */
    private fun fitTitle(fonts: CardFonts, title: String, maxWidth: Float): Pair<String, Float> {
        val t = CardTokens
        var size = t.TITLE
        val natural = ink(fonts.bold, size, title).width
        if (natural <= maxWidth) return title to size
        size = max(t.TITLE_MIN, floor(size * maxWidth / natural * 10) / 10)
        if (ink(fonts.bold, size, title).width <= maxWidth) return title to size
        val cps = title.codePoints().toArray().toMutableList()
        fun str() = String(cps.toIntArray(), 0, cps.size)
        while (cps.size > 1 && ink(fonts.bold, size, str() + "…").width > maxWidth) cps.removeAt(cps.lastIndex)
        return (str().trimEnd() + "…") to size
    }

    /** the card at export size (1080 × 1080 · 1080 × 1920) as a bitmap */
    fun bitmap(scene: CardScene, fonts: CardFonts, widthPx: Int = CardTokens.EXPORT_W): android.graphics.Bitmap {
        val k = widthPx / CardTokens.DESIGN_W
        val h = (scene.layout.height * k).roundToInt()
        val bmp = android.graphics.Bitmap.createBitmap(widthPx, h, android.graphics.Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), scene, k, fonts)
        return bmp
    }
}

/** QR modules drawn crisp: whole pixels per module, light quiet zone of [quiet] modules, on white */
object QrArt {
    fun draw(c: Canvas, size: Int, isDark: (Int, Int) -> Boolean, left: Float, top: Float, sidePx: Float, quiet: Int = 4) {
        val total = size + quiet * 2
        val module = floor(sidePx / total).coerceAtLeast(1f)
        val drawn = module * total
        val ox = floor(left + (sidePx - drawn) / 2)
        val oy = floor(top + (sidePx - drawn) / 2)
        c.drawRect(left, top, left + sidePx, top + sidePx, Paint().apply { color = 0xFFFFFFFF.toInt() })
        val dark = Paint().apply {
            color = 0xFF000000.toInt()
            isAntiAlias = false
        }
        for (y in 0 until size) {
            var x = 0
            while (x < size) {
                if (!isDark(x, y)) {
                    x += 1
                    continue
                }
                var e = x
                while (e + 1 < size && isDark(e + 1, y)) e += 1
                c.drawRect(ox + (quiet + x) * module, oy + (quiet + y) * module, ox + (quiet + e + 1) * module, oy + (quiet + y + 1) * module, dark)
                x = e + 1
            }
        }
    }

    fun bitmap(size: Int, isDark: (Int, Int) -> Boolean, modulePx: Int = 8, quiet: Int = 4): android.graphics.Bitmap {
        val side = (size + quiet * 2) * modulePx
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), size, isDark, 0f, 0f, side.toFloat(), quiet)
        return bmp
    }
}
