package com.odomap.app.ui.map

import com.odomap.app.model.Bounds
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** 구글 줌(256pt 타일) = MapLibre 줌(512pt 타일) + 1. 화면들은 구글 줌으로 말하고 지도에 넘길 때 1을 뺀다. */
const val ZOOM_OFFSET = 1.0
const val TILE_PT = 512.0
private const val MAX_LAT = 85.05112878

fun lngToX(lng: Double): Double = (lng + 180.0) / 360.0

fun latToY(lat: Double): Double {
    val phi = max(-MAX_LAT, min(MAX_LAT, lat)) * PI / 180.0
    return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
}

fun xToLng(x: Double): Double = x * 360.0 - 180.0

fun yToLat(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

/** 화면 상자(pt) */
data class Insets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/** 카메라: 가운데(카메라 여백 안쪽 상자의 가운데에 놓이는 자리) · MapLibre 줌 · 여백(pt) */
data class Camera(val lat: Double, val lng: Double, val zoom: Double, val padding: Insets = Insets())

/** 보이는 범위(손·명령으로 카메라가 멈춘 뒤) — 줌은 구글 줌 */
data class Viewport(val lat: Double, val lng: Double, val googleZoom: Double, val bounds: Bounds, val bearing: Double = 0.0)

/**
 * 범위가 여백(pt) 안쪽 상자에 다 보이는 카메라(발자취 fitBoundsCamera — 북쪽 위). 범위가 minSpanM보다 작으면 그만큼 넓히고, 줌은 maxGoogleZoom까지.
 * size는 지도 크기(pt).
 */
fun fitCamera(bounds: Bounds, width: Float, height: Float, padding: Insets, minSpanM: Double = 400.0, maxGoogleZoom: Double = 16.0): Camera {
    val cLat = (bounds.minLat + bounds.maxLat) / 2
    val cLng = (bounds.minLng + bounds.maxLng) / 2
    val mPerDegLat = 111_320.0
    val mPerDegLng = 111_320.0 * cos(cLat * PI / 180)
    val halfLat = max((bounds.maxLat - bounds.minLat) / 2, minSpanM / 2 / mPerDegLat)
    val halfLng = max((bounds.maxLng - bounds.minLng) / 2, minSpanM / 2 / mPerDegLng)
    val x0 = lngToX(cLng - halfLng)
    val x1 = lngToX(cLng + halfLng)
    val y0 = latToY(cLat + halfLat)
    val y1 = latToY(cLat - halfLat)
    // 여백이 지도보다 크면 안쪽 상자가 40은 남게 줄인다
    fun shrink(a: Float, b: Float, total: Float): Pair<Float, Float> {
        val room = total - 40f
        if (a + b <= room) return a to b
        val k = if (room > 0) room / (a + b) else 0f
        return a * k to b * k
    }
    val (top, bottom) = shrink(padding.top, padding.bottom, height)
    val (left, right) = shrink(padding.left, padding.right, width)
    val innerW = max(1f, width - left - right)
    val innerH = max(1f, height - top - bottom)
    val world = min(innerW / max(1e-12, x1 - x0), innerH / max(1e-12, y1 - y0))
    val zoom = min(log2(world / TILE_PT), maxGoogleZoom - ZOOM_OFFSET)
    val centerLat = yToLat((y0 + y1) / 2)
    return Camera(centerLat, cLng, zoom, Insets(left, top, right, bottom))
}

/** 화면 자리 focus(pt)가 카메라 가운데가 되는 여백 */
fun focusPadding(focusX: Float, focusY: Float, width: Float, height: Float): Insets =
    Insets(
        left = max(0f, 2 * focusX - width),
        top = max(0f, 2 * focusY - height),
        right = max(0f, width - 2 * focusX),
        bottom = max(0f, height - 2 * focusY),
    )

/** 카메라(가운데·MapLibre 줌·여백)와 지도 크기(pt)로 본 보이는 범위 */
fun viewBounds(camera: Camera, width: Float, height: Float): Bounds {
    val world = TILE_PT * 2.0.pow(camera.zoom)
    val cx = lngToX(camera.lng) * world
    val cy = latToY(camera.lat) * world
    // 카메라 가운데는 여백 안쪽 상자의 가운데에 있다
    val innerCx = camera.padding.left + (width - camera.padding.left - camera.padding.right) / 2
    val innerCy = camera.padding.top + (height - camera.padding.top - camera.padding.bottom) / 2
    val left = cx - innerCx
    val top = cy - innerCy
    return Bounds(
        minLat = yToLat((top + height) / world),
        minLng = xToLng(left / world),
        maxLat = yToLat(top / world),
        maxLng = xToLng((left + width) / world),
    )
}

/** 구글 줌의 1pt가 몇 m인지(위도 lat) */
fun metersPerPt(googleZoom: Double, lat: Double): Double = 156_543.03392 * cos(lat * PI / 180) / 2.0.pow(googleZoom)

// ───────────────────────── 선 굵기(발자취 EXPLORE_LEVEL_WIDTH_STOPS — MapLibre 줌, 단계 1 굵기, 한 단계마다 더하는 굵기)

val LEVEL_WIDTH_STOPS: List<Triple<Double, Double, Double>> =
    listOf(Triple(8.0, 1.9, 0.35), Triple(12.0, 1.7, 0.6), Triple(16.0, 1.4, 1.1), Triple(19.0, 2.2, 1.8))

/** 그 MapLibre 줌의 단계 1 굵기와 한 단계마다 더하는 굵기(pt) */
fun levelWidthAt(mapZoom: Double): Pair<Double, Double> {
    val s = LEVEL_WIDTH_STOPS
    if (mapZoom <= s.first().first) return s.first().second to s.first().third
    if (mapZoom >= s.last().first) return s.last().second to s.last().third
    var i = 1
    while (s[i].first < mapZoom) i += 1
    val (z0, b0, t0) = s[i - 1]
    val (z1, b1, t1) = s[i]
    val u = (mapZoom - z0) / (z1 - z0)
    return (b0 + (b1 - b0) * u) to (t0 + (t1 - t0) * u)
}

fun levelWidth(mapZoom: Double, level: Int, scale: Double = 1.0): Double {
    val (base, step) = levelWidthAt(mapZoom)
    return scale * (base + step * (level - 1))
}

/** 단계별 진하기 1→0.92 … 5→1 */
fun levelOpacity(level: Int): Float =
    when (level) {
        1 -> 0.92f
        2 -> 0.94f
        3 -> 0.96f
        4 -> 0.98f
        else -> 1f
    }

// ───────────────────────── 모눈 종이(발자취 paperGrid.ts 그대로)

const val GRID_SHOW_PT = 40.0
const val GRID_HIDE_PT = 20.0
private const val FINEST_STEPS = 5
const val GRID_MARGIN = 1.0
private const val OPACITY_ZOOM_BELOW = 4
private const val OPACITY_ZOOM_ABOVE = 4

data class GridBox(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)

/** 모눈 선 GeoJSON · 만든 상자 · 줌 단계 · 진하기(줌 칸마다 lv별 진하기) */
class PaperGrid(val json: String, val box: GridBox, val zoomFloor: Int, val opacityStops: List<Pair<Int, List<Pair<Int, Double>>>>, val lines: List<GridLine>)

/** 선 하나: 경선(vertical, 값 = 경도) 또는 위선(값 = 위도) */
class GridLine(val vertical: Boolean, val value: Double, val lv: Int)

/** 선 번호 i가 처음 나오는 가장 거친 단계 */
fun coarsestLevel(index: Long, finest: Int): Int {
    if (index == 0L) return 0
    var level = finest
    var value = kotlin.math.abs(index)
    while (level > 0 && value % 2 == 0L) {
        value /= 2
        level -= 1
    }
    return level
}

/** lv단계 선의 진하기(MapLibre 줌 z에서) — 칸 간격 512·2^(z−lv) pt가 20 → 40 사이에서 0 → 1(간격의 로그에 비례) */
fun gridLineOpacity(lv: Int, zoom: Double): Double {
    val spacing = TILE_PT * 2.0.pow(zoom - lv)
    if (spacing <= GRID_HIDE_PT) return 0.0
    if (spacing >= GRID_SHOW_PT) return 1.0
    return log2(spacing / GRID_HIDE_PT) / log2(GRID_SHOW_PT / GRID_HIDE_PT)
}

fun expandBox(box: GridBox, margin: Double = GRID_MARGIN): GridBox {
    val dLat = (box.maxLat - box.minLat) * margin
    val dLng = (box.maxLng - box.minLng) * margin
    return GridBox(max(-MAX_LAT, box.minLat - dLat), min(MAX_LAT, box.maxLat + dLat), max(-180.0, box.minLng - dLng), min(180.0, box.maxLng + dLng))
}

fun boxInside(inner: GridBox, outer: GridBox): Boolean =
    inner.minLat >= outer.minLat && inner.maxLat <= outer.maxLat && inner.minLng >= outer.minLng && inner.maxLng <= outer.maxLng

private fun f6(v: Double): String = String.format(Locale.US, "%.6f", v)

/** 보이는 범위와 MapLibre 줌으로 모눈 선과 진하기 식 재료를 만든다(선은 범위를 GRID_MARGIN만큼 넓힌 상자 안, 가장 고운 단계는 줌 + 5) */
fun buildPaperGrid(view: GridBox, mapZoom: Double): PaperGrid {
    val zoomFloor = floor(mapZoom).toInt()
    val finest = zoomFloor + FINEST_STEPS
    val box = expandBox(view)
    val cells = 2.0.pow(finest)
    val x0 = lngToX(box.minLng)
    val x1 = lngToX(box.maxLng)
    val y0 = latToY(box.maxLat)
    val y1 = latToY(box.minLat)
    val north = f6(box.maxLat)
    val south = f6(box.minLat)
    val west = f6(box.minLng)
    val east = f6(box.maxLng)
    val sb = StringBuilder(4096)
    sb.append("""{"type":"FeatureCollection","features":[""")
    var first = true
    var lvMin = finest
    val lines = ArrayList<GridLine>()
    val minLv = zoomFloor - OPACITY_ZOOM_BELOW - 2
    for (i in kotlin.math.ceil(x0 * cells).toLong()..floor(x1 * cells).toLong()) {
        val lv = max(coarsestLevel(i, finest), minLv)
        lvMin = min(lvMin, lv)
        val lngV = xToLng(i / cells)
        val lng = f6(lngV)
        lines.add(GridLine(true, lngV, lv))
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","properties":{"lv":$lv},"geometry":{"type":"LineString","coordinates":[[$lng,$south],[$lng,$north]]}}""")
    }
    for (j in kotlin.math.ceil(y0 * cells).toLong()..floor(y1 * cells).toLong()) {
        val lv = max(coarsestLevel(j, finest), minLv)
        lvMin = min(lvMin, lv)
        val latV = yToLat(j / cells)
        val lat = f6(latV)
        lines.add(GridLine(false, latV, lv))
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","properties":{"lv":$lv},"geometry":{"type":"LineString","coordinates":[[$west,$lat],[$east,$lat]]}}""")
    }
    sb.append("]}")
    val stops = ArrayList<Pair<Int, List<Pair<Int, Double>>>>()
    for (z in (zoomFloor - OPACITY_ZOOM_BELOW)..(zoomFloor + OPACITY_ZOOM_ABOVE)) {
        val match = ArrayList<Pair<Int, Double>>()
        for (lv in lvMin..finest) match.add(lv to Math.round(gridLineOpacity(lv, z.toDouble()) * 1000) / 1000.0)
        stops.add(z to match)
    }
    return PaperGrid(sb.toString(), box, zoomFloor, stops, lines)
}

/** 카메라 가운데·MapLibre 줌·지도 크기(pt)로 어림한 보이는 범위(긴 변으로 만든 네모) */
fun approxViewBox(lat: Double, lng: Double, mapZoom: Double, width: Float, height: Float): GridBox {
    val side = max(width, height).toDouble()
    val half = side / 2 / (TILE_PT * 2.0.pow(mapZoom))
    val cx = lngToX(lng)
    val cy = latToY(lat)
    return GridBox(
        minLat = max(-MAX_LAT, yToLat(cy + half)),
        maxLat = min(MAX_LAT, yToLat(cy - half)),
        minLng = max(-180.0, xToLng(cx - half)),
        maxLng = min(180.0, xToLng(cx + half)),
    )
}

fun Bounds.toGridBox(): GridBox = GridBox(minLat, maxLat, minLng, maxLng)
