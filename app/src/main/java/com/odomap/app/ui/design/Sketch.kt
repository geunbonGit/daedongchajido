package com.odomap.app.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.odomap.app.ui.map.latToY
import com.odomap.app.ui.map.lngToX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 지도 없이 그리는 내 길(작은 지도·배지·전국 지도·썸네일). 좌표는 웹 메르카토르 0..1(x = 경도, y = 위도 — 아래로 커짐).
 * 한 줄 = [x0, y0, x1, y1, …].
 */
@Immutable
class SketchLine(val level: Int, val xy: DoubleArray) {
    val minX: Double
    val minY: Double
    val maxX: Double
    val maxY: Double

    init {
        var a = Double.MAX_VALUE
        var b = Double.MAX_VALUE
        var c = -Double.MAX_VALUE
        var d = -Double.MAX_VALUE
        var i = 0
        while (i < xy.size) {
            a = min(a, xy[i])
            c = max(c, xy[i])
            b = min(b, xy[i + 1])
            d = max(d, xy[i + 1])
            i += 2
        }
        minX = a
        minY = b
        maxX = c
        maxY = d
    }
}

/** 메르카토르 상자(0..1) */
@Immutable
data class XYBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val w: Double get() = maxX - minX
    val h: Double get() = maxY - minY

    fun meets(l: SketchLine): Boolean = l.maxX >= minX && l.minX <= maxX && l.maxY >= minY && l.minY <= maxY

    fun union(o: XYBox): XYBox = XYBox(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))

    /** 가로세로 중 짧은 쪽을 넓혀 최소 크기(메르카토르) */
    fun atLeast(span: Double): XYBox {
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val hw = max(w, span) / 2
        val hh = max(h, span) / 2
        return XYBox(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    companion object {
        fun of(lines: List<SketchLine>): XYBox? {
            if (lines.isEmpty()) return null
            var b = XYBox(lines[0].minX, lines[0].minY, lines[0].maxX, lines[0].maxY)
            for (l in lines) b = b.union(XYBox(l.minX, l.minY, l.maxX, l.maxY))
            return b
        }

        fun ofLatLng(minLat: Double, minLng: Double, maxLat: Double, maxLng: Double): XYBox =
            XYBox(lngToX(minLng), latToY(maxLat), lngToX(maxLng), latToY(minLat))
    }
}

/** GeoJSON(엔진이 만든 MultiLineString / LineString, 속성 level) → 줄 */
fun parseSketch(geo: String?): List<SketchLine> {
    if (geo == null || geo.length < 60) return emptyList()
    val out = ArrayList<SketchLine>()
    val fs = JSONObject(geo).optJSONArray("features") ?: return out
    for (i in 0 until fs.length()) {
        val f = fs.getJSONObject(i)
        val level = f.optJSONObject("properties")?.optInt("level", 1) ?: 1
        val g = f.getJSONObject("geometry")
        val coords = g.getJSONArray("coordinates")
        fun line(a: org.json.JSONArray) {
            if (a.length() < 2) return
            val xy = DoubleArray(a.length() * 2)
            for (k in 0 until a.length()) {
                val p = a.getJSONArray(k)
                xy[k * 2] = lngToX(p.getDouble(0))
                xy[k * 2 + 1] = latToY(p.getDouble(1))
            }
            out.add(SketchLine(level, xy))
        }
        when (g.getString("type")) {
            "MultiLineString" -> for (j in 0 until coords.length()) line(coords.getJSONArray(j))
            "LineString" -> line(coords)
        }
    }
    return out
}

/** 경로 [lat, lng] → 줄 하나 */
fun sketchOfRoute(route: List<Pair<Double, Double>>): SketchLine? {
    if (route.size < 2) return null
    val xy = DoubleArray(route.size * 2)
    route.forEachIndexed { i, (lat, lng) ->
        xy[i * 2] = lngToX(lng)
        xy[i * 2 + 1] = latToY(lat)
    }
    return SketchLine(1, xy)
}

/** 글(GeoJSON)이 바뀔 때만 뒤에서 다시 읽는다 */
@Composable
fun rememberSketch(geo: String, key: Any = geo): State<List<SketchLine>> =
    produceState(initialValue = emptyList(), key) { value = withContext(Dispatchers.Default) { parseSketch(geo) } }

/** 메르카토르 상자를 화면 칸(rect, px)에 가운데 맞춰 넣는 투영 */
class Fit(box: XYBox, rect: Rect) {
    val scale: Double
    private val ox: Double
    private val oy: Double

    init {
        val s = min(rect.width / max(box.w, 1e-12), rect.height / max(box.h, 1e-12))
        scale = s
        ox = rect.left + (rect.width - box.w * s) / 2 - box.minX * s
        oy = rect.top + (rect.height - box.h * s) / 2 - box.minY * s
    }

    fun x(mx: Double): Float = (mx * scale + ox).toFloat()

    fun y(my: Double): Float = (my * scale + oy).toFloat()

    fun at(mx: Double, my: Double): Offset = Offset(x(mx), y(my))

    fun path(xy: DoubleArray, into: Path = Path()): Path {
        into.moveTo(x(xy[0]), y(xy[1]))
        var i = 2
        while (i < xy.size) {
            into.lineTo(x(xy[i]), y(xy[i + 1]))
            i += 2
        }
        return into
    }
}

/**
 * 줄을 단계별로 그린다(단계가 높을수록 굵고 진하게 — 지도 굵기 비율 1 : 1.4 : …). base는 1단계 굵기(px).
 * box 밖 줄은 건너뛴다.
 */
fun DrawScope.drawSketch(lines: List<SketchLine>, fit: Fit, color: Color, base: Float, view: XYBox? = null, levelStep: Float = 0.22f, alphaOf: (Int) -> Float = { 1f }) {
    val byLevel = HashMap<Int, Path>()
    for (l in lines) {
        if (view != null && !view.meets(l)) continue
        fit.path(l.xy, byLevel.getOrPut(l.level) { Path() })
    }
    for (level in byLevel.keys.sorted()) {
        val w = base * (1f + levelStep * (level - 1))
        drawPath(byLevel[level]!!, color.copy(alpha = color.alpha * alphaOf(level)), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 경로 썸네일(목록 줄 44 — 경로 굵기 1.6) */
@Composable
fun RouteThumb(route: SketchLine?, modifier: Modifier, color: Color, width: Float = 1.6f, pad: Float = 7f) {
    Canvas(modifier) {
        val r = route ?: return@Canvas
        val box = XYBox(r.minX, r.minY, r.maxX, r.maxY).atLeast(1e-7)
        val p = pad * density
        val fit = Fit(box, Rect(p, p, size.width - p, size.height - p))
        drawPath(fit.path(r.xy), color, style = Stroke(width * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
