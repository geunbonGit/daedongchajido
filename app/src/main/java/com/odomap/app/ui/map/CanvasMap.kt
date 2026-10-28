package com.odomap.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.odomap.app.model.Bounds
import com.odomap.app.ui.design.LineStyle
import com.odomap.app.ui.design.LocalLook
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.Marker
import com.odomap.app.ui.design.Palette
import com.odomap.app.ui.design.rgbColor
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow

private class Lines(val level: Int, val kind: String?, val w: Double, val lines: List<List<Pair<Double, Double>>>)

private fun parse(geo: String?): List<Lines> {
    if (geo == null || geo.length < 60) return emptyList()
    val fs = JSONObject(geo).optJSONArray("features") ?: return emptyList()
    return List(fs.length()) { i ->
        val f = fs.getJSONObject(i)
        val g = f.getJSONObject("geometry")
        val props = f.optJSONObject("properties") ?: JSONObject()
        val type = g.getString("type")
        val coords = g.getJSONArray("coordinates")
        val lines =
            when (type) {
                "MultiLineString" -> List(coords.length()) { j -> coords.getJSONArray(j).let { l -> List(l.length()) { k -> l.getJSONArray(k).getDouble(1) to l.getJSONArray(k).getDouble(0) } } }
                "LineString" -> listOf(List(coords.length()) { k -> coords.getJSONArray(k).getDouble(1) to coords.getJSONArray(k).getDouble(0) })
                "Point" -> listOf(listOf(coords.getDouble(1) to coords.getDouble(0)))
                else -> emptyList()
            }
        Lines(props.optInt("level", 1), props.optString("kind").ifEmpty { null }, props.optDouble("w", 0.0), lines)
    }
}

/**
 * JVM 시험용 지도: MapLibre와 같은 웹 메르카토르(512pt 타일)·같은 줌·같은 굵기 식으로 Canvas에 그린다(타일 없음).
 * 카메라는 마지막 명령(따라가기·맞추기·옮기기)을 움직임 없이 따른다.
 */
@Composable
internal fun CanvasMap(modifier: Modifier, controller: MapController, content: MapContent, puck: Puck?, puckScreenFixed: Boolean) {
    val palette = LocalPalette.current
    val look = LocalLook.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val net = remember(content.net) { parse(content.net) }
    val drive = remember(content.drive) { parse(content.drive) }
    val live = remember(content.live) { parse(content.live) }
    val tail = remember(content.tail) { parse(content.tail) }
    val dots = remember(content.dots) { parse(content.dots) }
    val boundaries = remember { parse(BoundaryAsset.load(context)) }
    var size by remember { mutableStateOf(0f to 0f) }
    var camera by remember { mutableStateOf(Camera(HOME_LAT, HOME_LNG, 11.0)) }

    LaunchedEffect(controller.serial, size) {
        val (w, h) = size
        if (w <= 0f || h <= 0f) return@LaunchedEffect
        val cam =
            when (val cmd = controller.command) {
                is MapController.Command.Follow -> Camera(cmd.lat, cmd.lng, cmd.googleZoom - ZOOM_OFFSET, focusPadding(cmd.focusX, cmd.focusY, w, h))
                is MapController.Command.Fit -> fitCamera(cmd.bounds, w, h, cmd.padding, cmd.minSpanM, cmd.maxGoogleZoom)
                is MapController.Command.MoveTo -> Camera(cmd.lat, cmd.lng, cmd.googleZoom - ZOOM_OFFSET)
                null -> camera
            }
        camera = cam
        val b = viewBounds(cam, w, h)
        controller.viewport = Viewport(cam.lat, cam.lng, cam.zoom + ZOOM_OFFSET, b)
        controller.size = w to h
    }

    Box(modifier.onSizeChanged { size = (it.width / density) to (it.height / density) }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(palette.paper)
            val (w, h) = size
            if (w <= 0f) return@Canvas
            val cam = camera
            val world = TILE_PT * 2.0.pow(cam.zoom)
            val innerCx = cam.padding.left + (w - cam.padding.left - cam.padding.right) / 2
            val innerCy = cam.padding.top + (h - cam.padding.top - cam.padding.bottom) / 2
            val ox = lngToX(cam.lng) * world - innerCx
            val oy = latToY(cam.lat) * world - innerCy
            fun pt(lat: Double, lng: Double) = Offset(((lngToX(lng) * world - ox) * density).toFloat(), ((latToY(lat) * world - oy) * density).toFloat())

            // 모눈
            if (content.grid) {
                val b = viewBounds(cam, w, h)
                val grid = buildPaperGrid(b.toGridBox(), cam.zoom)
                val gridColor = if (palette.dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.063f)
                for (line in grid.lines) {
                    val o = gridLineOpacity(line.lv, cam.zoom).toFloat()
                    if (o <= 0f) continue
                    val c = gridColor.copy(alpha = gridColor.alpha * o)
                    if (line.vertical) {
                        val x = pt(cam.lat, line.value).x
                        drawLine(c, Offset(x, 0f), Offset(x, this.size.height), 0.5f * density)
                    } else {
                        val y = pt(line.value, cam.lng).y
                        drawLine(c, Offset(0f, y), Offset(this.size.width, y), 0.5f * density)
                    }
                }
            }
            // 시·군·구 경계(구글 줌 7~12)
            if (content.boundaries) {
                val z = cam.zoom
                val fade = when {
                    z < 5.4 || z > 11.6 -> 0.0
                    z < 6.2 -> (z - 5.4) / 0.8
                    z > 10.8 -> (11.6 - z) / 0.8
                    else -> 1.0
                }.toFloat()
                if (fade > 0f) {
                    for (group in boundaries) {
                        val sgg = group.kind == "sgg"
                        val color = rgbColor(palette.def.ink, (if (sgg) 0.10f else 0.18f) * fade)
                        drawPolylines(group.lines, ::pt, color, (if (sgg) 0.5f else 1.0f) * density, null)
                    }
                }
            }
            // 멀리 볼 때 점
            if (cam.zoom < DOTS_GONE) {
                val o = if (cam.zoom <= DOTS_SOLID) 0.7f else (0.7f * (DOTS_GONE - cam.zoom) / (DOTS_GONE - DOTS_SOLID)).toFloat()
                for (d in dots) for (line in d.lines) for ((lat, lng) in line) {
                    val wgt = d.w
                    val r4 = 2 + 0.5 * ln(1 + wgt)
                    val r10 = 3 + 0.8 * ln(1 + wgt)
                    val r = r4 + (r10 - r4) * ((cam.zoom - 4) / 6).coerceIn(0.0, 1.0)
                    drawCircle(palette.ink.copy(alpha = o), (r * density).toFloat(), pt(lat, lng))
                }
            }
            val netColor = if (content.netTone == NetTone.PAST) palette.pastLine else palette.ink
            drawLevels(net, cam.zoom, 1.0, netColor, null, look.line, palette, ::pt)
            drawLevels(drive, cam.zoom, 1.0, palette.ink, palette.paper, look.line, palette, ::pt)
            drawLevels(tail + live, cam.zoom, 1.15, palette.ink, palette.paper, look.line, palette, ::pt)
            for (flag in content.flags) drawFlag(pt(flag.lat, flag.lng), flag.end, palette)
            if (puck != null && !puckScreenFixed) drawPuck(pt(puck.lat, puck.lng), puck.bearing, palette, look.marker)
        }
    }
}

private fun DrawScope.drawPolylines(lines: List<List<Pair<Double, Double>>>, pt: (Double, Double) -> Offset, color: Color, width: Float, effect: PathEffect?) {
    val path = Path()
    for (line in lines) {
        line.forEachIndexed { i, (lat, lng) ->
            val p = pt(lat, lng)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.drawLevels(
    groups: List<Lines>,
    mapZoom: Double,
    scale: Double,
    color: Color,
    halo: Color?,
    style: LineStyle,
    palette: Palette,
    pt: (Double, Double) -> Offset,
) {
    if (groups.isEmpty()) return
    val sorted = groups.sortedBy { it.level }
    if (halo != null) {
        for (g in sorted) drawPolylines(g.lines, pt, halo.copy(alpha = 0.95f), ((levelWidth(mapZoom, g.level, scale) + 3) * density).toFloat(), null)
    }
    val patterned = mapZoom >= 12.9
    for (g in sorted) {
        val w = (levelWidth(mapZoom, g.level, scale) * density).toFloat()
        val a = levelOpacity(g.level)
        when (style) {
            LineStyle.MARKER -> drawPolylines(g.lines, pt, color.copy(alpha = a * 0.34f), w * 2.3f, null)
            LineStyle.BLEED -> {
                drawPolylines(g.lines, pt, color.copy(alpha = a * 0.24f), w * 3.4f, null)
                drawPolylines(g.lines, pt, color.copy(alpha = a), w, null)
            }
            LineStyle.DOTTED -> if (patterned) drawPolylines(g.lines, pt, color.copy(alpha = a), w * 1.45f, PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 1.45f * 2.2f))) else drawPolylines(g.lines, pt, color.copy(alpha = a), w, null)
            LineStyle.DASHED -> if (patterned) drawPolylines(g.lines, pt, color.copy(alpha = a), w, PathEffect.dashPathEffect(floatArrayOf(w * 1.6f, w * 2.4f))) else drawPolylines(g.lines, pt, color.copy(alpha = a), w, null)
            LineStyle.DOUBLE ->
                if (patterned) {
                    drawPolylines(g.lines, pt, color.copy(alpha = a), w * 2.3f, null)
                    drawPolylines(g.lines, pt, palette.paper, w * 1.1f, null)
                } else {
                    drawPolylines(g.lines, pt, color.copy(alpha = a), w, null)
                }
            LineStyle.SOLID -> drawPolylines(g.lines, pt, color.copy(alpha = a), w, null)
        }
    }
}

private fun DrawScope.drawFlag(at: Offset, end: Boolean, palette: Palette) {
    val s = density
    val x = at.x
    val y = at.y - 37.6f * s // 장대 아래 끝이 그 자리
    drawOval(palette.ink.copy(alpha = 0.18f), topLeft = Offset(x - 5f * s, y + 36f * s), size = androidx.compose.ui.geometry.Size(10f * s, 3.2f * s))
    drawRect(palette.ink, topLeft = Offset(x - 1.1f * s, y + 4.2f * s), size = androidx.compose.ui.geometry.Size(2.2f * s, 32.8f * s))
    val p = Path().apply {
        moveTo(x + 1.1f * s, y + 4.6f * s)
        cubicTo(x + 8f * s, y + 2.4f * s, x + 13f * s, y + 7.2f * s, x + 21f * s, y + 4.8f * s)
        lineTo(x + 21f * s, y + 17.4f * s)
        cubicTo(x + 13f * s, y + 19.8f * s, x + 8f * s, y + 15f * s, x + 1.1f * s, y + 17.2f * s)
        close()
    }
    drawPath(p, if (end) palette.fresh else palette.ink)
}

/** 내 점(지도 위 · 화면 고정 모두): 점 13 + 테 2.5(그림자 0 1 3 @0.18), 방향 세모(점 위 7.2) */
fun DrawScope.drawPuck(at: Offset, bearing: Float?, palette: Palette, marker: Marker) {
    val s = density
    val ring = rgbColor(palette.puckRingRgb)
    if (bearing != null) {
        rotate(bearing, at) {
            val p = Path().apply {
                moveTo(at.x, at.y - 16.2f * s)
                lineTo(at.x + 4.2f * s, at.y - 9.8f * s)
                lineTo(at.x - 4.2f * s, at.y - 9.8f * s)
                close()
            }
            drawPath(p, palette.ink)
        }
    }
    if (marker == Marker.MOON) {
        drawCircle(ring, (6.5f + 2.5f + 0.5f) * s, at)
        val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(at, 7.5f * s)) }
        val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(at.x + 3.3f * s, at.y - 1.95f * s), 6.457f * s)) }
        val moon = Path().apply { op(outer, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
        drawPath(moon, palette.ink)
    } else {
        drawCircle(Color.Black.copy(alpha = 0.12f), 9.6f * s, at + Offset(0f, 1f * s))
        drawCircle(ring, 9f * s, at)
        drawCircle(palette.ink, 6.5f * s, at)
    }
}

@Suppress("unused")
private fun Bounds.center() = ((minLat + maxLat) / 2) to ((minLng + maxLng) / 2)
