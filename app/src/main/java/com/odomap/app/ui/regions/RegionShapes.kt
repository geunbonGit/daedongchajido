package com.odomap.app.ui.regions

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import com.odomap.app.ui.design.Fit
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.Palette
import com.odomap.app.ui.design.SketchLine
import com.odomap.app.ui.design.XYBox
import com.odomap.app.ui.design.drawSketch
import com.odomap.app.ui.map.latToY
import com.odomap.app.ui.map.lngToX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

/** 지역 모양 하나(그리기 전용 — 판정은 core RegionIndex). 고리 = 메르카토르 [x0, y0, …], 바깥과 구멍 모두(짝홀 채움). */
@Immutable
class Shape(val rings: List<DoubleArray>) {
    val box: XYBox =
        run {
            var a = Double.MAX_VALUE
            var b = Double.MAX_VALUE
            var c = -Double.MAX_VALUE
            var d = -Double.MAX_VALUE
            for (r in rings) {
                var i = 0
                while (i < r.size) {
                    a = minOf(a, r[i]); c = maxOf(c, r[i]); b = minOf(b, r[i + 1]); d = maxOf(d, r[i + 1]); i += 2
                }
            }
            XYBox(a, b, c, d)
        }

    fun path(fit: Fit): Path {
        val p = Path().apply { fillType = PathFillType.EvenOdd }
        for (r in rings) {
            fit.path(r, p)
            p.close()
        }
        return p
    }

    /** 점(메르카토르)이 모양 안인가(짝홀) */
    fun contains(x: Double, y: Double): Boolean {
        if (x < box.minX || x > box.maxX || y < box.minY || y > box.maxY) return false
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (k in 0 until n) {
                val yi = r[2 * k + 1]
                val yj = r[2 * j + 1]
                if ((yi > y) != (yj > y)) {
                    val xi = r[2 * k]
                    val xj = r[2 * j]
                    if (x < xi + (y - yi) * (xj - xi) / (yj - yi)) inside = !inside
                }
                j = k
            }
        }
        return inside
    }
}

/** 시·군·구 230 + 시·도 17 모양(assets/regions/regions-outline.json, 700m 단순화) */
class RegionShapes(val regions: Map<String, Shape>, val sido: Map<String, Shape>, val sidoNames: Map<String, String>, private val fine: Map<String, Shape> = emptyMap()) {
    /** 배지·지역 하나처럼 크게 그릴 모양: 단순화 없는 경계(있으면) */
    fun detail(code: String): Shape? = fine[code] ?: regions[code]

    val all: XYBox = regions.values.map { it.box }.reduce { a, b -> a.union(b) }

    companion object {
        @Volatile private var cached: RegionShapes? = null
        @Volatile private var precise: Map<String, Shape>? = null

        /** 판정용 경계(regions.json, 단순화 없음) — 지역 하나 화면처럼 크게 그릴 때 */
        fun precise(context: Context, code: String): Shape? = precise(context)[code]

        private fun precise(context: Context): Map<String, Shape> {
            precise?.let { return it }
            val all =
                runCatching {
                    val o = JSONObject(context.assets.open("regions/regions.json").bufferedReader().use { it.readText() })
                    val ra = o.getJSONArray("regions")
                    val m = HashMap<String, Shape>()
                    for (i in 0 until ra.length()) {
                        val r = ra.getJSONObject(i)
                        m[r.getString("code")] = shapeOf(r.getJSONArray("polygons"))
                    }
                    m
                }.getOrNull() ?: return emptyMap()
            precise = all
            return all
        }

        private fun shapeOf(polygons: JSONArray): Shape {
            val rings = ArrayList<DoubleArray>()
            for (i in 0 until polygons.length()) {
                val poly = polygons.getJSONArray(i)
                for (j in 0 until poly.length()) {
                    val ring = poly.getJSONArray(j)
                    val xy = DoubleArray(ring.length())
                    var k = 0
                    while (k < ring.length()) {
                        xy[k] = lngToX(ring.getInt(k + 1) / 1e6)
                        xy[k + 1] = latToY(ring.getInt(k) / 1e6)
                        k += 2
                    }
                    rings.add(xy)
                }
            }
            return Shape(rings)
        }

        fun load(context: Context): RegionShapes? {
            cached?.let { return it }
            return runCatching {
                val o = JSONObject(context.assets.open("regions/regions-outline.json").bufferedReader().use { it.readText() })
                val regions = HashMap<String, Shape>()
                val ra = o.getJSONArray("regions")
                for (i in 0 until ra.length()) {
                    val r = ra.getJSONObject(i)
                    regions[r.getString("code")] = shapeOf(r.getJSONArray("polygons"))
                }
                val sido = LinkedHashMap<String, Shape>()
                val names = LinkedHashMap<String, String>()
                val sa = o.optJSONArray("sido") ?: JSONArray()
                for (i in 0 until sa.length()) {
                    val s = sa.getJSONObject(i)
                    sido[s.getString("sidoCode")] = shapeOf(s.getJSONArray("polygons"))
                    names[s.getString("sidoCode")] = s.getString("sido")
                }
                RegionShapes(regions, sido, names, precise(context))
            }.getOrNull()?.also { cached = it }
        }
    }
}

@Composable
fun rememberRegionShapes(): State<RegionShapes?> {
    val context = LocalContext.current
    return produceState(initialValue = null as RegionShapes?, context) { value = withContext(Dispatchers.IO) { RegionShapes.load(context) } }
}

/**
 * 지역 모양 안 모눈 + 테두리 + 그 안의 내 길(발자취 동네 배지): 채움 fill, 모양 안 모눈(칸 max(9, 크기/8)), 테두리 ink@0.38 굵기 1.2,
 * 선 굵기 clamp(크기/190, 1.1, 2)·(1 + 0.22(L−1)), 진하기 0.72 + 0.07(L−1).
 */
fun DrawScope.drawRegionBadge(
    shape: Shape,
    lines: List<SketchLine>,
    p: Palette,
    fill: Color,
    rect: Rect,
    lineBase: Float? = null,
    border: Color = p.ink.copy(alpha = 0.38f),
    borderWidth: Float = 1.2f,
    grid: Boolean = true,
) {
    val fit = Fit(shape.box, rect)
    val path = shape.path(fit)
    drawPath(path, fill)
    val sizePt = minOf(rect.width, rect.height) / density
    clipPath(path) {
        if (grid) {
            val cell = max(9f, sizePt / 8f) * density
            val gc = if (p.dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.063f)
            var x = floor(rect.left / cell) * cell
            while (x <= rect.right) {
                drawLine(gc, androidx.compose.ui.geometry.Offset(x, rect.top), androidx.compose.ui.geometry.Offset(x, rect.bottom), 0.5f * density)
                x += cell
            }
            var y = floor(rect.top / cell) * cell
            while (y <= rect.bottom) {
                drawLine(gc, androidx.compose.ui.geometry.Offset(rect.left, y), androidx.compose.ui.geometry.Offset(rect.right, y), 0.5f * density)
                y += cell
            }
        }
        val base = (lineBase ?: (sizePt / 190f).coerceIn(1.1f, 2f)) * density
        drawSketch(lines, fit, p.ink, base, view = shape.box) { level -> (0.72f + 0.07f * (level - 1)).coerceAtMost(1f) }
    }
    drawPath(path, border, style = Stroke(borderWidth * density, join = StrokeJoin.Round))
}

/** 배지 하나(정사각 size pt) */
@Composable
fun RegionBadge(shape: Shape?, lines: List<SketchLine>, modifier: Modifier, fill: Color, pad: Float = 4f) {
    val p = LocalPalette.current
    val inside = remember(shape, lines) { if (shape == null) emptyList() else lines.filter { shape.box.meets(it) } }
    Canvas(modifier) {
        val s = shape ?: return@Canvas
        val d = pad * density
        drawRegionBadge(s, inside, p, fill, Rect(d, d, size.width - d, size.height - d))
    }
}
