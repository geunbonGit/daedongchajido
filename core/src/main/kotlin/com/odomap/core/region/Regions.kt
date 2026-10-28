package com.odomap.core.region

import com.odomap.core.geo.haversineM
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

// 시·군·구 boundaries (tools/regions/out/regions.json, format odomap-regions/1 — see tools/regions/README.md) and point lookup.
//
// Lookup = the reference tools/regions/validate_regions.py (Index.locate), same answer for every point: the point is rounded to integer
// microdegrees (Python round = half-even = Math.rint), regions are tried in file order (bbox, then per polygon: even-odd ray casting of the
// outer ring, holes excluded), the first hit wins. Ray casting uses the same arithmetic (integer products, one rounded division, one
// rounded addition), so even points on a border agree.
// Speed: a coarse grid (0.02°). A cell that no ring edge or region bbox side comes within 1 µdeg of has one answer for all its points
// (adjacent such cells share it — flood-filled, computed once per component with the reference test); other cells keep the regions whose
// bbox meets them. A ring is skipped by its own bbox first (exact: outside it the even-odd count is 0 or even).

/** A 시·군·구 (or 세종 / 제주 행정시) — one of the 230 regions. */
class Region internal constructor(
    /** position in regions.json — the share-format region number (regionSet 1) */
    val index: Int,
    /** 5-digit 시군구 code, e.g. "41820" (merged cities use the parent code) */
    val code: String,
    /** short name, e.g. "가평군" — not unique (중구 ×5, 동구 ×5, …) */
    val name: String,
    /** official full name, e.g. "경기도 가평군" */
    val full: String,
    /** short unique UI name, e.g. "경기 가평군" */
    val label: String,
    val sido: String,
    val sidoCode: String,
    /** general districts merged into this city (e.g. 수원시: 장안구 …), empty otherwise */
    val districts: List<String>,
    /** a point guaranteed inside the region (degrees) */
    val centerLat: Double,
    val centerLng: Double,
    /** [minLat, minLng, maxLat, maxLng] in microdegrees */
    internal val bbox: IntArray,
    internal val polygons: Array<RegionPolygon>,
) {
    val minLatE6: Int get() = bbox[0]
    val minLngE6: Int get() = bbox[1]
    val maxLatE6: Int get() = bbox[2]
    val maxLngE6: Int get() = bbox[3]

    /** validate_regions.py locate() for one region (point in integer microdegrees) */
    internal fun contains(lat: Int, lng: Int): Boolean {
        val b = bbox
        if (lat < b[0] || lat > b[2] || lng < b[1] || lng > b[3]) return false
        for (polygon in polygons) if (polygon.contains(lat, lng)) return true
        return false
    }

    override fun toString(): String = "Region($code $label)"
}

/** outer ring + holes (even-odd) */
internal class RegionPolygon(val outer: RegionRing, val holes: Array<RegionRing>) {
    fun contains(lat: Int, lng: Int): Boolean {
        if (!outer.contains(lat, lng)) return false
        for (hole in holes) if (hole.contains(lat, lng)) return false
        return true
    }
}

/** ring = [lat0, lng0, lat1, lng1, …] integer microdegrees, closing point omitted */
internal class RegionRing(val coords: IntArray) {
    val minLat: Int
    val minLng: Int
    val maxLat: Int
    val maxLng: Int

    init {
        var a = Int.MAX_VALUE
        var b = Int.MAX_VALUE
        var c = Int.MIN_VALUE
        var d = Int.MIN_VALUE
        var k = 0
        while (k < coords.size) {
            a = min(a, coords[k])
            c = max(c, coords[k])
            b = min(b, coords[k + 1])
            d = max(d, coords[k + 1])
            k += 2
        }
        minLat = a
        minLng = b
        maxLat = c
        maxLng = d
    }

    /** validate_regions.py ring_contains: x = xi + (lat - yi) * (xj - xi) / (yj - yi) as Python computes it (exact int product, rounded division) */
    fun contains(lat: Int, lng: Int): Boolean {
        if (lat < minLat || lat >= maxLat || lng > maxLng || lng < minLng) return false
        val c = coords
        val n = c.size / 2
        var inside = false
        var j = n - 1
        for (k in 0 until n) {
            val yi = c[2 * k]
            val yj = c[2 * j]
            if ((yi > lat) != (yj > lat)) {
                val xi = c[2 * k + 1]
                val xj = c[2 * j + 1]
                val x = xi.toDouble() + ((lat - yi).toLong() * (xj - xi)).toDouble() / (yj - yi).toDouble()
                if (lng < x) inside = !inside
            }
            j = k
        }
        return inside
    }
}

/** An unvisited region and how far its center is (straight line). */
data class RegionDistance(
    val region: Region,
    val distanceKm: Double,
    /** initial bearing from the query point, degrees clockwise from north */
    val bearingDeg: Double,
) {
    /** 8-point Korean compass word for [bearingDeg]: 북, 북동, 동, 남동, 남, 남서, 서, 북서 */
    val direction: String get() = compassKo(bearingDeg)
}

/** The boundary set (regions.json) with a fast point lookup. Immutable, thread-safe after load. */
class RegionIndex private constructor(
    /** regions in file order — index = share-format region number */
    val regions: List<Region>,
    /** regions.json source.version, e.g. "ver20261001" */
    val sourceVersion: String,
    /** attribution to show in the app (CC BY 4.0, regions.json source.attribution) */
    val attribution: String,
    val attributionEn: String,
) {
    private val byCode: Map<String, Region> = regions.associateBy { it.code }

    /** share-format region set: 1 = sgis-20261001 (230 regions), 0 = unknown data */
    val regionSet: Int = if (sourceVersion == "ver20261001" && regions.size == 230) 1 else 0

    // grid: cells[r * cols + c] ≥ 0 region index for the whole cell · -1 no region · ≤ -2 → candidate list -2 - v (candStart/cand)
    private val lat0: Int
    private val lng0: Int
    private val latMax: Int
    private val lngMax: Int
    private val rows: Int
    private val cols: Int
    private val cells: IntArray
    private val candStart: IntArray
    private val cand: IntArray

    /** grid statistics: cells, mixed cells (border passes), uniform components */
    val gridInfo: String

    init {
        var a = Int.MAX_VALUE
        var b = Int.MAX_VALUE
        var c = Int.MIN_VALUE
        var d = Int.MIN_VALUE
        for (r in regions) {
            a = min(a, r.bbox[0])
            b = min(b, r.bbox[1])
            c = max(c, r.bbox[2])
            d = max(d, r.bbox[3])
        }
        if (regions.isEmpty()) {
            a = 0
            b = 0
            c = 0
            d = 0
        }
        lat0 = a
        lng0 = b
        latMax = c
        lngMax = d
        rows = (c - a) / CELL_E6 + 1
        cols = (d - b) / CELL_E6 + 1
        val grid = buildGrid()
        cells = grid.cells
        candStart = grid.candStart
        cand = grid.cand
        gridInfo = grid.info
    }

    fun byCode(code: String): Region? = byCode[code]

    /** region numbers (share format) of the given codes, ascending; unknown codes skipped */
    fun indicesOf(codes: Iterable<String>): List<Int> = codes.mapNotNull { byCode[it]?.index }.distinct().sorted()

    /** The region containing the point, null at sea / outside the data (validate_regions.py Index.locate). */
    fun regionAt(lat: Double, lng: Double): Region? {
        val i = indexAt(lat, lng)
        return if (i >= 0) regions[i] else null
    }

    /** [regionAt] as a region number, -1 when none */
    fun indexAt(lat: Double, lng: Double): Int {
        if (!lat.isFinite() || !lng.isFinite()) return -1
        val la = Math.rint(lat * 1e6)
        val ln = Math.rint(lng * 1e6)
        if (la < lat0 || la > latMax || ln < lng0 || ln > lngMax) return -1
        val y = la.toInt()
        val x = ln.toInt()
        val v = cells[((y - lat0) / CELL_E6) * cols + (x - lng0) / CELL_E6]
        if (v >= -1) return v
        val list = -2 - v
        for (k in candStart[list] until candStart[list + 1]) {
            val ri = cand[k]
            if (regions[ri].contains(y, x)) return ri
        }
        return -1
    }

    /**
     * Unvisited regions nearest to the point by straight-line distance to their [Region.centerLat]/[Region.centerLng] ("안 가 본 동네로"),
     * nearest first, at most [limit], none farther than [maxKm].
     */
    fun nearestUnvisited(lat: Double, lng: Double, visited: Set<String>, limit: Int = 5, maxKm: Double = 200.0): List<RegionDistance> {
        val out = ArrayList<RegionDistance>()
        for (r in regions) {
            if (r.code in visited) continue
            val km = haversineM(lat, lng, r.centerLat, r.centerLng) / 1000
            if (km <= maxKm) out.add(RegionDistance(r, km, initialBearingDeg(lat, lng, r.centerLat, r.centerLng)))
        }
        out.sortWith(compareBy<RegionDistance> { it.distanceKm }.thenBy { it.region.index })
        return out.take(max(0, limit))
    }

    /** reference lookup over all regions (grid construction) */
    private fun scan(lat: Int, lng: Int): Int {
        for (r in regions) if (r.contains(lat, lng)) return r.index
        return -1
    }

    private class Grid(val cells: IntArray, val candStart: IntArray, val cand: IntArray, val info: String)

    private fun buildGrid(): Grid {
        val n = rows * cols
        val mixed = BooleanArray(n)
        for (r in regions) {
            for (polygon in r.polygons) {
                for (ring in listOf(polygon.outer) + polygon.holes) {
                    val c = ring.coords
                    val m = c.size / 2
                    var j = m - 1
                    for (k in 0 until m) {
                        markSegment(mixed, c[2 * j], c[2 * j + 1], c[2 * k], c[2 * k + 1])
                        j = k
                    }
                }
            }
            val b = r.bbox
            markSegment(mixed, b[0], b[1], b[0], b[3])
            markSegment(mixed, b[2], b[1], b[2], b[3])
            markSegment(mixed, b[0], b[1], b[2], b[1])
            markSegment(mixed, b[0], b[3], b[2], b[3])
        }
        // candidate lists of mixed cells: regions whose bbox meets the cell, in file order
        val cells = IntArray(n)
        var lists = 0
        for (k in 0 until n) if (mixed[k]) cells[k] = -2 - lists++
        val count = IntArray(lists + 1)
        forBboxCells { k, _ -> if (mixed[k]) count[-2 - cells[k]] += 1 }
        val candStart = IntArray(lists + 1)
        for (k in 0 until lists) candStart[k + 1] = candStart[k] + count[k]
        val fill = candStart.copyOf()
        val cand = IntArray(candStart[lists])
        forBboxCells { k, ri -> if (mixed[k]) cand[fill[-2 - cells[k]]++] = ri }
        // uniform cells: flood-fill 4-connected components, one reference test each
        val done = BooleanArray(n)
        val queue = IntArray(n)
        var tail = 0
        fun enqueue(k: Int) {
            if (mixed[k] || done[k]) return
            done[k] = true
            queue[tail++] = k
        }
        var components = 0
        for (start in 0 until n) {
            if (mixed[start] || done[start]) continue
            components += 1
            val answer = scan(lat0 + (start / cols) * CELL_E6 + CELL_E6 / 2, lng0 + (start % cols) * CELL_E6 + CELL_E6 / 2)
            var head = 0
            tail = 0
            enqueue(start)
            while (head < tail) {
                val k = queue[head++]
                cells[k] = answer
                val row = k / cols
                val col = k % cols
                if (row > 0) enqueue(k - cols)
                if (row < rows - 1) enqueue(k + cols)
                if (col > 0) enqueue(k - 1)
                if (col < cols - 1) enqueue(k + 1)
            }
        }
        return Grid(cells, candStart, cand, "grid ${rows}×$cols (${CELL_E6 / 1e6}°), $lists mixed cells, ${n - lists} uniform in $components components")
    }

    private inline fun forBboxCells(action: (Int, Int) -> Unit) {
        for (r in regions) {
            val b = r.bbox
            for (row in rowOf(b[0])..rowOf(b[2])) for (col in colOf(b[1])..colOf(b[3])) action(row * cols + col, r.index)
        }
    }

    private fun rowOf(lat: Int): Int = Math.floorDiv(lat - lat0, CELL_E6).coerceIn(0, rows - 1)

    private fun colOf(lng: Int): Int = Math.floorDiv(lng - lng0, CELL_E6).coerceIn(0, cols - 1)

    /** marks every cell whose points the segment comes within 1 µdeg of (row by row, the segment clipped to the row band ± 1) */
    private fun markSegment(mixed: BooleanArray, y0: Int, x0: Int, y1: Int, x1: Int) {
        val yLo = min(y0, y1)
        val yHi = max(y0, y1)
        for (row in rowOf(yLo - 1)..rowOf(yHi + 1)) {
            val bandLo = lat0.toLong() + row.toLong() * CELL_E6 - 1
            val bandHi = lat0.toLong() + (row + 1).toLong() * CELL_E6
            val xa: Double
            val xb: Double
            if (y0 == y1) {
                if (y0 < bandLo || y0 > bandHi) continue
                xa = min(x0, x1).toDouble()
                xb = max(x0, x1).toDouble()
            } else {
                val ya = max(yLo.toLong(), bandLo).toDouble()
                val yb = min(yHi.toLong(), bandHi).toDouble()
                if (ya > yb) continue
                val pa = x0 + (ya - y0) * (x1 - x0) / (y1 - y0)
                val pb = x0 + (yb - y0) * (x1 - x0) / (y1 - y0)
                xa = min(pa, pb)
                xb = max(pa, pb)
            }
            val c0 = colOf(Math.floor(xa).toInt() - 1)
            val c1 = colOf(Math.ceil(xb).toInt() + 1)
            for (col in c0..c1) mixed[row * cols + col] = true
        }
    }

    companion object {
        /** grid cell size (microdegrees): 0.02° ≈ 2.2 km × 1.8 km */
        const val CELL_E6 = 20_000

        /** Parses regions.json (format odomap-regions/1). */
        fun load(json: String): RegionIndex = parse(json.toByteArray(Charsets.UTF_8))

        fun load(input: InputStream): RegionIndex = parse(input.readBytes())

        fun load(bytes: ByteArray): RegionIndex = parse(bytes)

        private fun parse(bytes: ByteArray): RegionIndex {
            val r = JsonReader(bytes)
            val regions = ArrayList<Region>()
            var format: String? = null
            var version = ""
            var attribution = ""
            var attributionEn = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "format" -> format = r.nextString()
                    "source" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "version" -> version = r.nextString()
                                "attribution" -> attribution = r.nextString()
                                "attributionEn" -> attributionEn = r.nextString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                    "regions" -> {
                        r.beginArray()
                        while (r.hasNext()) regions.add(parseRegion(r, regions.size))
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            require(format == "odomap-regions/1") { "regions json: unknown format $format" }
            return RegionIndex(regions, version, attribution, attributionEn)
        }

        private fun parseRegion(r: JsonReader, index: Int): Region {
            var code = ""
            var name = ""
            var full = ""
            var label = ""
            var sido = ""
            var sidoCode = ""
            var districts: List<String> = emptyList()
            var center = DoubleArray(2)
            var bbox = IntArray(4)
            val polygons = ArrayList<RegionPolygon>()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "code" -> code = r.nextString()
                    "name" -> name = r.nextString()
                    "full" -> full = r.nextString()
                    "label" -> label = r.nextString()
                    "sido" -> sido = r.nextString()
                    "sidoCode" -> sidoCode = r.nextString()
                    "districts" -> districts = r.nextStringList()
                    "center" -> center = r.nextDoubleArray()
                    "bbox" -> bbox = r.nextIntArray()
                    "polygons" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            val rings = ArrayList<RegionRing>()
                            r.beginArray()
                            while (r.hasNext()) rings.add(RegionRing(r.nextIntArray()))
                            r.endArray()
                            if (rings.isNotEmpty()) polygons.add(RegionPolygon(rings[0], rings.drop(1).toTypedArray()))
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            require(bbox.size == 4 && center.size == 2) { "regions json: region $code needs bbox[4] and center[2]" }
            return Region(index, code, name, full, label, sido, sidoCode, districts, center[0], center[1], bbox, polygons.toTypedArray())
        }
    }
}

/** Initial great-circle bearing a → b (degrees clockwise from north, 0 ≤ … < 360). */
fun initialBearingDeg(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val rad = Math.PI / 180
    val p1 = aLat * rad
    val p2 = bLat * rad
    val dl = (bLng - aLng) * rad
    val y = StrictMath.sin(dl) * StrictMath.cos(p2)
    val x = StrictMath.cos(p1) * StrictMath.sin(p2) - StrictMath.sin(p1) * StrictMath.cos(p2) * StrictMath.cos(dl)
    val deg = StrictMath.atan2(y, x) / rad
    return (deg + 360) % 360
}

private val COMPASS_KO = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")

/** 8-point compass word (Korean) for a bearing in degrees. */
fun compassKo(bearingDeg: Double): String = COMPASS_KO[(Math.floorMod(Math.round(bearingDeg / 45.0), 8L)).toInt()]
