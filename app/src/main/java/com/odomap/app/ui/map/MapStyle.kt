package com.odomap.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.DisplayMetrics
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.ui.design.LineStyle
import com.odomap.app.ui.design.Marker
import com.odomap.app.ui.design.Palette
import com.odomap.app.ui.design.rgba
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.sum
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LayoutPropertyValue
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineGapWidth
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale
import kotlin.math.max

/**
 * 종이 지도의 층(발자취 NativeRunMap 층 차례 그대로 — design-footprint-ref 2.1):
 * background(#F7F7F5 고정) → paper-fill-<hex>(배색 종이) → paper-anchor(빈 선) → paper-grid-lines → 시·군·구 경계 → real-*(실제 지도, 평소 숨김)
 * → explore-dots(멀리) → explore-mine(내 길) → explore-drive(한 드라이브) → explore-live(-tail)(-halo)(지금 그리는 선) → 오차 원 → 깃발 · 내 점.
 * Android 함정: background 층은 붙인 뒤 색을 바꾸면 안 그려진다 → 배색이 바뀌면 새 id의 층을 붙이고 옛 층을 뗀다.
 */
object MapStyleJson {
    const val REAL_SOURCE = "real-openmaptiles"
    const val REAL_PREFIX = "real-"

    private var light: JSONObject? = null
    private var dark: JSONObject? = null

    private fun load(context: Context, name: String): JSONObject =
        JSONObject(context.assets.open("map/$name").bufferedReader().use { it.readText() })

    fun lightOf(context: Context): JSONObject = light ?: load(context, "runMapLight.json").also { light = it }

    fun darkOf(context: Context): JSONObject = dark ?: load(context, "runMapDark.json").also { dark = it }

    /** 흰 지도 스타일: 바탕 한 층 + 그 바로 위에 보이지 않게 둔 실제 지도(원본·층, 바탕 층은 뺌) + 글꼴 */
    fun blank(context: Context, darkScheme: Boolean): String {
        val src = if (darkScheme) darkOf(context) else lightOf(context)
        val url = src.getJSONObject("sources").getJSONObject("openmaptiles").getString("url")
        val layers = JSONArray()
        layers.put(JSONObject().put("id", "background").put("type", "background").put("paint", JSONObject().put("background-color", "#F7F7F5")))
        val srcLayers = src.getJSONArray("layers")
        for (i in 0 until srcLayers.length()) {
            val layer = JSONObject(srcLayers.getJSONObject(i).toString())
            if (layer.optString("type") == "background") continue
            layer.put("id", REAL_PREFIX + layer.getString("id"))
            if (layer.has("source")) layer.put("source", REAL_SOURCE)
            val layout = layer.optJSONObject("layout") ?: JSONObject()
            layout.put("visibility", "none")
            layer.put("layout", layout)
            layers.put(layer)
        }
        return JSONObject()
            .put("version", 8)
            .put("name", "odomap-paper")
            .put("glyphs", src.optString("glyphs"))
            .put("sources", JSONObject().put(REAL_SOURCE, JSONObject().put("type", "vector").put("url", url)))
            .put("layers", layers)
            .toString()
    }

    /** 실제 지도 층마다 원래 칠(배색 쪽 스타일) — id(앞말 붙음) → 층 JSON */
    fun realLayers(context: Context, darkScheme: Boolean): List<JSONObject> {
        val src = if (darkScheme) darkOf(context) else lightOf(context)
        val out = ArrayList<JSONObject>()
        val arr = src.getJSONArray("layers")
        for (i in 0 until arr.length()) {
            val layer = arr.getJSONObject(i)
            if (layer.optString("type") == "background") continue
            out.add(layer)
        }
        return out
    }
}

private fun f(v: Double) = String.format(Locale.US, "%.3f", v)

/** level 단계별 굵기(pt) — 줌 사이 선형(EXPLORE_LEVEL_WIDTH_STOPS), scale 배, extra를 더함 */
fun levelWidthExpr(scale: Double, extra: Double = 0.0): Expression {
    val stops =
        LEVEL_WIDTH_STOPS.map { (z, base, step) ->
            val w = product(literal(scale), sum(literal(base), product(literal(step), Expression.subtract(toNumber(get("level")), literal(1)))))
            stop(z, if (extra != 0.0) sum(literal(extra), w) else w)
        }
    return interpolate(linear(), zoom(), *stops.toTypedArray())
}

/** 단계별 진하기(× alpha) */
fun levelOpacityExpr(alpha: Double = 1.0): Expression {
    val m = match(toNumber(get("level")), literal(1.0 * alpha), stop(1, 0.92 * alpha), stop(2, 0.94 * alpha), stop(3, 0.96 * alpha), stop(4, 0.98 * alpha))
    return m
}

/**
 * 멀리 볼 때 점(MapLibre 줌): 발자취는 8.4까지 진하고 9.2에서 사라진다. 차의 내 지도는 걸음보다 수십 배 넓어(서울↔가평 한 화면이 줌 8.5)
 * 선이 아직 잘 보이는 줌에서 점이 선을 덮는다 — 두 단계 멀리(6.4 → 7.2)로 옮긴다.
 */
const val DOTS_SOLID = 6.4
const val DOTS_GONE = 7.2
private const val DOTS_FROM = 12.4
private const val DOTS_FULL = 12.9
private const val SOLID_END = 13.2
private const val HALO_EXTRA = 3.0

/** 한 스타일 위의 Odomap 층들. 모두 지도 스레드(메인)에서 부른다. */
class MapLayers(private val context: Context, private val style: Style, private val pxPerPt: Float) {
    private var palette: Palette? = null
    private var paperLayerId: String? = null
    private var lineStyle: LineStyle = LineStyle.SOLID
    private var netTone: NetTone = NetTone.INK
    private var marker: Marker? = null
    private var realOn = false
    private var realVisible = false
    private var realDark: Boolean? = null

    // 소스
    private val grid = GeoJsonSource("paper-grid", EMPTY_FEATURES)
    private val net = GeoJsonSource("explore-mine", EMPTY_FEATURES, GeoJsonOptions().withTolerance(0.25f))
    private val drive = GeoJsonSource("explore-drive", EMPTY_FEATURES, GeoJsonOptions().withTolerance(0.25f))
    private val live = GeoJsonSource("explore-live", EMPTY_FEATURES, GeoJsonOptions().withTolerance(0.25f))
    private val tail = GeoJsonSource("explore-live-tail", EMPTY_FEATURES)
    private val dots = GeoJsonSource("explore-dots", EMPTY_FEATURES)
    private val accuracy = GeoJsonSource("accuracy", EMPTY_FEATURES)
    private val puck = GeoJsonSource("puck", EMPTY_FEATURES)
    private val flags = GeoJsonSource("flags", EMPTY_FEATURES)
    private var boundaries: GeoJsonSource? = null

    private val netLayers = ArrayList<String>()
    private val driveLayers = ArrayList<String>()
    private val liveLayers = ArrayList<String>()

    fun install(p: Palette, look: LineStyle, mark: Marker, boundaryJson: String?) {
        style.addSource(GeoJsonSource("paper", EMPTY_FEATURES))
        // 종이 기준 층(층 차례 1 — 바탕 바로 위, 실제 지도 아래). 종이 층은 그 아래, 모눈은 그 위
        style.addLayerAt(LineLayer("paper-anchor", "paper"), 1)
        style.addSource(grid)
        style.addLayerAbove(LineLayer("paper-grid-lines", "paper-grid").withProperties(lineWidth(0.5f)), "paper-anchor")
        if (boundaryJson != null) {
            val src = GeoJsonSource("region-lines", boundaryJson)
            boundaries = src
            style.addSource(src)
            // 구글 줌 7~12(MapLibre 6~11)에서만, 사이는 진하기 보간
            val fade = interpolate(linear(), zoom(), stop(5.4, 0f), stop(6.2, 1f), stop(10.8, 1f), stop(11.6, 0f))
            style.addLayerAbove(
                LineLayer("region-sgg", "region-lines").withFilter(Expression.eq(get("kind"), literal("sgg")))
                    .withProperties(lineWidth(0.5f), lineOpacity(fade), lineJoin(Property.LINE_JOIN_ROUND))
                    .apply { minZoom = 5.4f; maxZoom = 11.6f },
                "paper-grid-lines",
            )
            style.addLayerAbove(
                LineLayer("region-sido", "region-lines").withFilter(Expression.eq(get("kind"), literal("sido")))
                    .withProperties(lineWidth(1.0f), lineOpacity(fade), lineJoin(Property.LINE_JOIN_ROUND))
                    .apply { minZoom = 5.4f; maxZoom = 11.6f },
                "region-sgg",
            )
        }
        style.addSource(dots)
        style.addSource(net)
        style.addSource(drive)
        style.addSource(live)
        style.addSource(tail)
        style.addSource(accuracy)
        style.addSource(flags)
        style.addSource(puck)
        style.addLayer(
            CircleLayer("explore-dots-dots", "explore-dots").withProperties(
                circleOpacity(interpolate(linear(), zoom(), stop(4, 0.7f), stop(DOTS_SOLID, 0.7f), stop(DOTS_GONE, 0f))),
                circleRadius(
                    interpolate(
                        linear(), zoom(),
                        stop(4, sum(literal(2.0), product(literal(0.5), Expression.ln(sum(literal(1.0), toNumber(get("w"))))))),
                        stop(10, sum(literal(3.0), product(literal(0.8), Expression.ln(sum(literal(1.0), toNumber(get("w"))))))),
                    ),
                ),
            ).apply { maxZoom = (DOTS_GONE + 0.1).toFloat() },
        )
        lineStyle = look
        addLineLayers("explore-mine", netLayers, 1.0, halo = false, haloBelow = null)
        addLineLayers("explore-drive", driveLayers, 1.0, halo = true, haloBelow = null)
        // 지금 그리는 선: 두 원본(다듬은 몫·꼬리)의 테두리를 모두 선 밑에 — 이어지는 자리에서 뒤 테두리가 앞 선을 덮지 않게
        addLineLayers("explore-live-tail", liveLayers, 1.15, halo = true, haloBelow = null)
        addLineLayers("explore-live", liveLayers, 1.15, halo = true, haloBelow = "explore-live-tail-halo")
        style.addLayer(CircleLayer("accuracy-circle", "accuracy").withProperties(circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        style.addLayer(
            SymbolLayer("flags-pins", "flags").withProperties(
                iconImage(get("icon")),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconOffset(arrayOf(0f, 2.4f)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            SymbolLayer("puck-heading", "puck").withFilter(Expression.has("bearing")).withProperties(
                iconImage("puck-heading"),
                iconRotate(toNumber(get("bearing"))),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            SymbolLayer("puck-body", "puck").withProperties(iconImage("puck-body"), iconAllowOverlap(true), iconIgnorePlacement(true)),
        )
        setPalette(p, mark)
    }

    // ───────── 선 층(발자취 exploreStyleLayers)

    private fun addLineLayers(source: String, into: MutableList<String>, scale: Double, halo: Boolean, haloBelow: String?) {
        val created = ArrayList<Layer>()
        fun line(id: String) = LineLayer(id, source).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
        if (halo) {
            val h = line("$source-halo").withProperties(lineOpacity(0.95f), lineWidth(levelWidthExpr(scale, HALO_EXTRA)))
            if (haloBelow != null && style.getLayer(haloBelow) != null) style.addLayerBelow(h, haloBelow) else style.addLayer(h)
            into.add(h.id)
        }
        when (lineStyle) {
            LineStyle.SOLID -> created.add(line("$source-lines").withProperties(lineOpacity(levelOpacityExpr()), lineWidth(levelWidthExpr(scale))))
            LineStyle.MARKER -> created.add(line("$source-marker").withProperties(lineOpacity(levelOpacityExpr(0.34)), lineWidth(levelWidthExpr(scale * 2.3))))
            LineStyle.BLEED -> {
                created.add(
                    line("$source-bleed").withProperties(
                        lineOpacity(levelOpacityExpr(0.24)),
                        lineWidth(levelWidthExpr(scale * 3.4)),
                        lineBlur(levelWidthExpr(scale * 1.1 * 1.6)),
                    ),
                )
                created.add(line("$source-core").withProperties(lineOpacity(levelOpacityExpr()), lineWidth(levelWidthExpr(scale))))
            }
            else -> {
                created.add(
                    line("$source-far").withProperties(
                        lineOpacity(interpolate(linear(), zoom(), stop(DOTS_FULL, levelOpacityExpr()), stop(SOLID_END, literal(0.0)))),
                        lineWidth(levelWidthExpr(scale)),
                    ).apply { maxZoom = (SOLID_END + 0.1).toFloat() },
                )
                val near =
                    line("$source-${lineStyle.id}").withProperties(
                        lineOpacity(interpolate(linear(), zoom(), stop(DOTS_FROM, literal(0.0)), stop(DOTS_FULL, levelOpacityExpr()))),
                    ).apply { minZoom = DOTS_FROM.toFloat() }
                when (lineStyle) {
                    LineStyle.DOUBLE -> near.setProperties(lineWidth(levelWidthExpr(scale * (2.3 - 1.1) / 2)), lineGapWidth(levelWidthExpr(scale * 1.1)))
                    LineStyle.DASHED -> near.setProperties(lineWidth(levelWidthExpr(scale)), lineDasharray(arrayOf(1.6f, 2.4f)))
                    else -> near.setProperties(lineWidth(levelWidthExpr(scale * 1.45)), lineDasharray(arrayOf(0f, 2.2f)))
                }
                created.add(near)
            }
        }
        for (layer in created) {
            // 지금 선의 잉크 층은 두 원본의 테두리 위(같은 묶음의 맨 위)
            style.addLayer(layer)
            into.add(layer.id)
        }
    }

    fun setLineStyle(next: LineStyle) {
        if (next == lineStyle) return
        lineStyle = next
        for (id in netLayers + driveLayers + liveLayers) style.removeLayer(id)
        netLayers.clear()
        driveLayers.clear()
        liveLayers.clear()
        // 층 차례를 지킨다: 내 길 → 한 드라이브 → 지금 선 → (오차 원·깃발·내 점은 그 위로 다시 올린다)
        val tops = listOf("accuracy-circle", "flags-pins", "puck-heading", "puck-body").mapNotNull { id -> style.getLayer(id)?.also { style.removeLayer(it) } }
        addLineLayers("explore-mine", netLayers, 1.0, halo = false, haloBelow = null)
        addLineLayers("explore-drive", driveLayers, 1.0, halo = true, haloBelow = null)
        addLineLayers("explore-live-tail", liveLayers, 1.15, halo = true, haloBelow = null)
        addLineLayers("explore-live", liveLayers, 1.15, halo = true, haloBelow = "explore-live-tail-halo")
        for (layer in tops) style.addLayer(layer)
        palette?.let { applyLineColors(it) }
    }

    // ───────── 색

    fun setPalette(p: Palette, mark: Marker) {
        val prev = palette
        palette = p
        if (prev == null || prev.paperHex != p.paperHex) swapPaper(p)
        style.getLayer("paper-grid-lines")?.setProperties(lineColor(p.gridColor))
        style.getLayer("region-sgg")?.setProperties(lineColor(rgba(p.def.ink, 0.10f)))
        style.getLayer("region-sido")?.setProperties(lineColor(rgba(p.def.ink, 0.18f)))
        style.getLayer("explore-dots-dots")?.setProperties(circleColor(p.inkHex))
        style.getLayer("accuracy-circle")?.setProperties(circleColor(p.haloHex), circleOpacity(if (p.dark) 0.2f else 0.14f))
        applyLineColors(p)
        if (prev == null || prev.def != p.def || marker != mark) {
            marker = mark
            style.addImage("puck-body", puckBody(p, mark))
            style.addImage("puck-heading", puckHeading(p))
            style.addImage("flag-start", flagBitmap(p, end = false))
            style.addImage("flag-end", flagBitmap(p, end = true))
        }
        if (realDark != null && realDark != p.dark) applyRealPaint(p.dark)
    }

    private fun applyLineColors(p: Palette) {
        val netColor = if (netTone == NetTone.PAST) p.pastLineHex else p.inkHex
        for (id in netLayers) style.getLayer(id)?.setProperties(lineColor(netColor))
        for (id in driveLayers + liveLayers) {
            style.getLayer(id)?.setProperties(lineColor(if (id.endsWith("-halo")) p.paperHex else p.inkHex))
        }
    }

    /** 배색 종이: 새 id의 바탕 층을 기준 층 아래에 붙이고 옛 층을 뗀다(색을 바꾸면 안 그려지는 Android 함정) */
    private fun swapPaper(p: Palette) {
        val id = "paper-fill-" + p.paperHex.removePrefix("#").lowercase()
        if (id == paperLayerId) return
        if (style.getLayer(id) == null) style.addLayerBelow(BackgroundLayer(id).withProperties(backgroundColor(p.paperHex)), "paper-anchor")
        paperLayerId?.let { style.removeLayer(it) }
        paperLayerId = id
    }

    fun setNetTone(tone: NetTone) {
        if (tone == netTone) return
        netTone = tone
        palette?.let { applyLineColors(it) }
    }

    // ───────── 데이터

    fun setGrid(g: PaperGrid?, on: Boolean) {
        val layer = style.getLayer("paper-grid-lines") ?: return
        if (g == null || !on) {
            layer.setProperties(visibility(Property.NONE))
            return
        }
        grid.setGeoJson(g.json)
        val stops = g.opacityStops.map { (z, levels) ->
            val m = match(toNumber(get("lv")), literal(1.0), *levels.map { (lv, o) -> stop(lv, o) }.toTypedArray())
            stop(z, m)
        }
        layer.setProperties(visibility(Property.VISIBLE), lineOpacity(interpolate(linear(), zoom(), *stops.toTypedArray())))
    }

    fun setBoundaries(on: Boolean) {
        for (id in listOf("region-sgg", "region-sido")) style.getLayer(id)?.setProperties(visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    fun setNet(json: String) = net.setGeoJson(json)

    fun setDrive(json: String?) = drive.setGeoJson(json ?: EMPTY_FEATURES)

    fun setLive(json: String?, tailJson: String?) {
        live.setGeoJson(json ?: EMPTY_FEATURES)
        tail.setGeoJson(tailJson ?: EMPTY_FEATURES)
    }

    fun setDots(json: String) = dots.setGeoJson(json)

    fun setFlags(list: List<MapFlag>) {
        if (list.isEmpty()) {
            flags.setGeoJson(EMPTY_FEATURES)
            return
        }
        val features = list.joinToString(",") { flag ->
            """{"type":"Feature","properties":{"icon":"${if (flag.end) "flag-end" else "flag-start"}"},"geometry":{"type":"Point","coordinates":[${flag.lng},${flag.lat}]}}"""
        }
        flags.setGeoJson("""{"type":"FeatureCollection","features":[$features]}""")
    }

    /** 내 점(지도 위). screenFixed면 지도 위 점은 숨긴다(따라가는 동안은 화면에 고정한 점을 그린다) */
    fun setPuck(p: Puck?, screenFixed: Boolean) {
        if (p == null) {
            puck.setGeoJson(EMPTY_FEATURES)
            accuracy.setGeoJson(EMPTY_FEATURES)
            return
        }
        val props = if (p.bearing != null) """{"bearing":${p.bearing}}""" else "{}"
        puck.setGeoJson(if (screenFixed) EMPTY_FEATURES else """{"type":"FeatureCollection","features":[{"type":"Feature","properties":$props,"geometry":{"type":"Point","coordinates":[${p.lng},${p.lat}]}}]}""")
        val acc = p.accuracyM
        if (acc == null || acc <= 0f) {
            accuracy.setGeoJson(EMPTY_FEATURES)
        } else {
            val m = (Math.round(acc / 5f) * 5f).coerceIn(6f, 120f)
            val latForScale = Math.round(p.lat * 100) / 100.0
            val r0 = m / (78271.517 * kotlin.math.cos(latForScale * Math.PI / 180))
            accuracy.setGeoJson("""{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${p.lng},${p.lat}]}}""")
            style.getLayer("accuracy-circle")?.setProperties(
                circleRadius(interpolate(Expression.exponential(2), zoom(), stop(0, r0), stop(24, r0 * Math.pow(2.0, 24.0)))),
            )
        }
    }

    // ───────── 실제 지도(켤 때 700ms · 끌 때 650ms 진하기 전환, 끈 뒤 20초면 감춤)

    private val opacityKeys = mapOf("fill" to listOf("fill-opacity"), "line" to listOf("line-opacity"), "symbol" to listOf("text-opacity", "icon-opacity"))

    /** shown: 보임 · hidden: 붙어 있되 진하기 0 · parked: 감춤(타일을 받지 않음) */
    fun setRealMap(phase: String, dark: Boolean) {
        val layers = MapStyleJson.realLayers(context, dark)
        if (realDark == null || realDark != dark) applyRealPaint(dark)
        val shown = phase == "shown"
        val duration = if (shown) 700L else 650L
        for (spec in layers) {
            val id = MapStyleJson.REAL_PREFIX + spec.getString("id")
            val layer = style.getLayer(id) ?: continue
            val type = spec.optString("type")
            val paint = spec.optJSONObject("paint") ?: JSONObject()
            val t = TransitionOptions(duration, 0)
            when (layer) {
                is FillLayer -> layer.setFillOpacityTransition(t)
                is LineLayer -> layer.setLineOpacityTransition(t)
                is SymbolLayer -> {
                    layer.setTextOpacityTransition(t)
                    layer.setIconOpacityTransition(t)
                }
            }
            val props = ArrayList<org.maplibre.android.style.layers.PropertyValue<*>>()
            for (key in opacityKeys[type] ?: emptyList()) {
                val value: Any = if (shown) (paint.opt(key)?.let { jsonValue(it) } ?: 1f) else 0f
                props.add(PaintPropertyValue(key, value))
            }
            props.add(LayoutPropertyValue("visibility", if (phase == "parked") Property.NONE else Property.VISIBLE))
            layer.setProperties(*props.toTypedArray())
        }
        realVisible = phase != "parked"
        realOn = shown
    }

    /** 실제 지도 색을 배색 쪽 스타일의 것으로(밝음 ↔ 어둠) */
    private fun applyRealPaint(dark: Boolean) {
        realDark = dark
        for (spec in MapStyleJson.realLayers(context, dark)) {
            val layer = style.getLayer(MapStyleJson.REAL_PREFIX + spec.getString("id")) ?: continue
            val paint = spec.optJSONObject("paint") ?: continue
            val props = ArrayList<org.maplibre.android.style.layers.PropertyValue<*>>()
            val keys = paint.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.endsWith("-opacity")) continue
                props.add(PaintPropertyValue(key, jsonValue(paint.get(key))))
            }
            if (props.isNotEmpty()) layer.setProperties(*props.toTypedArray())
        }
    }

    private fun jsonValue(v: Any): Any =
        when (v) {
            is JSONArray -> if (v.length() > 0 && v.get(0) is String) Expression.Converter.convert(v.toString()) else Array(v.length()) { (v.get(it) as Number).toFloat() }
            is Number -> v.toFloat()
            else -> v
        }

    // ───────── 그림(내 점 · 깃발) — 발자취 ExplorePuck: 상자 34, 점 13 + 테 2.5, 그림자 0 1 3 @0.18, 방향 세모 M17 0.8 l4.2 6.4 h-8.4Z

    private fun bitmap(wPt: Float, hPt: Float, draw: Canvas.(Float) -> Unit): Bitmap {
        val s = pxPerPt
        val bmp = Bitmap.createBitmap(max(1, Math.round(wPt * s)), max(1, Math.round(hPt * s)), Bitmap.Config.ARGB_8888)
        bmp.density = Math.round(DisplayMetrics.DENSITY_DEFAULT * s)
        Canvas(bmp).draw(s)
        return bmp
    }

    private fun puckBody(p: Palette, mark: Marker): Bitmap =
        bitmap(34f, 34f) { s ->
            val ink = 0xFF000000.toInt() or p.def.ink
            val ring = 0xFF000000.toInt() or p.puckRingRgb
            val c = 17f * s
            if (mark == Marker.MOON) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring }
                drawCircle(c, c, (13f / 2 + 2.5f + 0.5f) * s, paint)
                drawPath(moonPath(s), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
            } else {
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x2E000000
                    maskFilter = BlurMaskFilter(max(0.5f, (1.5f * s - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
                }
                val r = (13f / 2 + 2.5f) * s
                drawCircle(c, c + 1f * s, r, shadow)
                drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring })
                drawCircle(c, c, 13f / 2 * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
            }
        }

    private fun moonPath(s: Float): Path {
        // 발자취 초승달 M17 9.5 a7.5 7.5 0 1 0 6.6 11.1 a6 6 0 0 1-6.6-11.1Z: 안쪽 호는 반지름이 현의 반(6.457)으로 늘어난 반원 —
        // 중심 (20.3, 15.05). 잉크 원에서 그 원을 덜어 낸 것과 같다
        val outer = Path().apply { addCircle(17f * s, 17f * s, 7.5f * s, Path.Direction.CW) }
        val cut = Path().apply { addCircle(20.3f * s, 15.05f * s, 6.457f * s, Path.Direction.CW) }
        outer.op(cut, Path.Op.DIFFERENCE)
        return outer
    }

    private fun puckHeading(p: Palette): Bitmap =
        bitmap(34f, 34f) { s ->
            val path = Path().apply {
                moveTo(17f * s, 0.8f * s)
                lineTo((17f + 4.2f) * s, (0.8f + 6.4f) * s)
                lineTo((17f - 4.2f) * s, (0.8f + 6.4f) * s)
                close()
            }
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() or p.def.ink })
        }

    /** 깃발(장대 + 천): 출발은 잉크, 도착은 강조색에 체크. 상자 50×40, 장대 아래 끝이 (25, 37.6) */
    private fun flagBitmap(p: Palette, end: Boolean): Bitmap =
        bitmap(50f, 40f) { s ->
            val ink = 0xFF000000.toInt() or p.def.ink
            val x = 25f
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = (0.18f * 255).toInt() }
            drawOval(RectF((x - 5f) * s, (37.6f - 1.6f) * s, (x + 5f) * s, (37.6f + 1.6f) * s), shadowPaint)
            drawRect((x - 1.1f) * s, 4.2f * s, (x + 1.1f) * s, 37f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
            val pennant = Path().apply {
                moveTo((x + 1.1f) * s, 4.6f * s)
                cubicTo((x + 8f) * s, 2.4f * s, (x + 13f) * s, 7.2f * s, (x + 21f) * s, 4.8f * s)
                lineTo((x + 21f) * s, 17.4f * s)
                cubicTo((x + 13f) * s, 19.8f * s, (x + 8f) * s, 15f * s, (x + 1.1f) * s, 17.2f * s)
                close()
            }
            drawPath(pennant, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (end) 0xFF000000.toInt() or p.def.fresh else ink })
            if (end) {
                val check = Path().apply {
                    // M(x+6.4) 10.6 l1.5-1.5 2.4 2.4 5-5 1.5 1.5 -6.5 6.5z
                    var cx = x + 6.4f
                    var cy = 10.6f
                    moveTo(cx * s, cy * s)
                    fun rel(dx: Float, dy: Float) {
                        cx += dx
                        cy += dy
                        lineTo(cx * s, cy * s)
                    }
                    rel(1.5f, -1.5f)
                    rel(2.4f, 2.4f)
                    rel(5f, -5f)
                    rel(1.5f, 1.5f)
                    rel(-6.5f, 6.5f)
                    close()
                }
                drawPath(check, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (p.dark) 0xFF000000.toInt() or p.def.paper else -1 })
            }
        }
}

/** 시·군·구 경계 선(자산) — 한 번만 읽는다 */
object BoundaryAsset {
    @Volatile private var cached: String? = null

    fun load(context: Context): String? =
        cached ?: runCatching { context.assets.open("regions/region-lines.json").bufferedReader().use { it.readText() } }.getOrNull()?.also { cached = it }
}

@Suppress("unused")
private fun debugOpacity(levels: List<Pair<Int, Double>>): String = levels.joinToString { "${it.first}:${f(it.second)}" }
