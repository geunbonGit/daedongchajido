package com.odomap.core.share

import com.odomap.core.explore.EXPLORE_METERS_PER_DEGREE
import com.odomap.core.explore.EXPLORE_RAD
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.geo.GeoPoint
import io.nayuki.qrcodegen.QrCode
import io.nayuki.qrcodegen.QrSegment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Fitting a card into one QR code (docs/share-format.md "크기 맞추기"):
//  1. lines → local equirectangular metres (cos of the middle latitude) → Douglas–Peucker with tolerance t. Each point's DP significance is
//     computed once (the deviation it was picked at, capped by its parent's), so "kept at t" = significance > t — the exact DP result;
//  2. quantExp 4 (≈ 11 m) when the whole extent is ≤ 40 km, else 3 (≈ 110 m);
//  3. t = 2 m if the QR (ECC M, byte segment for the URL prefix + alphanumeric segment for the base32 payload) is version ≤ 20, else the
//     smallest t in 2 m … 2 km that fits (bisection on log t); if 2 km still does not fit, the shortest lines go first, then t is searched
//     again (the spec asks it for kind 2; kind 1 does the same as a last resort);
//  4. lines left with < 2 points after quantization are dropped (ShareCodec).
// Kind 2 lines are first chained (greedy nearest end, reversed when needed) so each line starts near where the last one ended, and lines
// meeting end to start with the same style are joined (the joint is pinned — junctions stay exact).

/** A card ready to show: URL, QR modules and the card exactly as encoded. */
class ShareQr internal constructor(
    val url: String,
    val payload: String,
    /** the card as encoded — simplified, quantized: what a viewer decodes */
    val card: ShareCard,
    /** binary size before base32 */
    val bytes: Int,
    /** QR version 1..20 (size = 17 + 4 × version) */
    val version: Int,
    /** error correction used: 'M', or 'Q' / 'H' when the version had room to spare */
    val ecc: Char,
    /** Douglas–Peucker tolerance used (m; 0 without lines) */
    val toleranceM: Double,
    /** lines left out (shortest first) because 2 km did not fit */
    val droppedLines: Int,
    /** modules per side (no quiet zone — draw 4 light modules around it) */
    val size: Int,
    private val modules: BooleanArray,
) {
    /** dark module at column x, row y */
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]

    /** rows of modules, true = dark */
    fun toMatrix(): Array<BooleanArray> = Array(size) { y -> BooleanArray(size) { x -> modules[y * size + x] } }
}

object ShareEncoder {
    const val MAX_VERSION = 20
    const val MIN_TOLERANCE_M = 2.0
    const val MAX_TOLERANCE_M = 2000.0

    /** extent (m) up to which coordinates use 10^-4 degrees */
    const val FINE_EXTENT_M = 40_000.0

    /** kind 2: only this many longest lines are chained (chaining is quadratic; a version-20 code holds < 100 lines anyway) */
    const val MAX_CHAINED_LINES = 2000

    /** Simplifies / quantizes the card's lines until the QR fits [maxVersion] and builds it. Kind 3 is encoded as is. */
    fun encode(card: ShareCard, prefix: String = SHARE_URL_PREFIX, maxVersion: Int = MAX_VERSION): ShareQr {
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        var dropped = 0
        val lines =
            when (card) {
                is ShareCard.Drive -> card.lines
                is ShareCard.Atlas -> longest(card.lines, MAX_CHAINED_LINES).also { dropped = card.lines.size - it.size }
                is ShareCard.Regions -> null
            }
        var tolerance = 0.0
        val bytes: ByteArray
        if (lines == null || lines.isEmpty()) {
            bytes = ShareCodec.encode(withLines(card, lines ?: emptyList(), 4))
        } else {
            val fit = LineFit(lines, chain = card is ShareCard.Atlas)
            val cache = HashMap<Pair<Double, Int>, ByteArray>()
            fun bytesAt(t: Double, drop: Int): ByteArray = cache.getOrPut(t to drop) { ShareCodec.encode(withLines(card, fit.linesAt(t, drop), fit.quantExp)) }
            fun fits(t: Double, drop: Int): Boolean = qrVersion(prefixBytes.size, base32Length(bytesAt(t, drop).size), maxVersion) > 0
            tolerance = MIN_TOLERANCE_M
            var drop = 0
            if (!fits(MIN_TOLERANCE_M, 0)) {
                if (!fits(MAX_TOLERANCE_M, 0)) {
                    var lo = 1
                    var hi = fit.size
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (fits(MAX_TOLERANCE_M, mid)) hi = mid else lo = mid + 1
                    }
                    drop = lo
                }
                if (!fits(MIN_TOLERANCE_M, drop)) {
                    var lo = MIN_TOLERANCE_M
                    var hi = MAX_TOLERANCE_M
                    while (hi / lo > 1.02) {
                        val mid = sqrt(lo * hi)
                        if (fits(mid, drop)) hi = mid else lo = mid
                    }
                    tolerance = hi
                }
            }
            dropped += drop
            bytes = bytesAt(tolerance, drop)
        }
        val payload = ShareCodec.base32(bytes)
        val qr =
            QrCode.encodeSegments(
                listOf(QrSegment.makeBytes(prefixBytes), QrSegment.makeAlphanumeric(payload)),
                QrCode.Ecc.MEDIUM, 1, maxVersion, -1, true,
            )
        val modules = BooleanArray(qr.size * qr.size)
        for (y in 0 until qr.size) for (x in 0 until qr.size) modules[y * qr.size + x] = qr.getModule(x, y)
        return ShareQr(
            prefix + payload, payload, ShareCodec.decode(bytes), bytes.size, qr.version, qr.errorCorrectionLevel.name[0],
            tolerance, dropped, qr.size, modules,
        )
    }

    /** base32 characters for n bytes */
    fun base32Length(bytes: Int): Int = (bytes * 8 + 4) / 5

    /**
     * Smallest QR version ≤ maxVersion holding [prefixBytes] in byte mode + [alphanumericChars] in alphanumeric mode at ECC M, -1 if none
     * (same arithmetic as qrcodegen: mode 4 bits + count bits by version range + data bits ≤ data codewords × 8).
     */
    fun qrVersion(prefixBytes: Int, alphanumericChars: Int, maxVersion: Int = MAX_VERSION): Int {
        for (v in 1..min(40, maxVersion)) {
            val byteCount = if (v <= 9) 8 else 16
            val alnumCount = if (v <= 9) 9 else if (v <= 26) 11 else 13
            if (prefixBytes >= (1 shl byteCount) || alphanumericChars >= (1 shl alnumCount)) continue
            val bits = 4 + byteCount + 8 * prefixBytes + 4 + alnumCount + 11 * (alphanumericChars / 2) + 6 * (alphanumericChars % 2)
            if (bits <= dataCodewordsM(v) * 8) return v
        }
        return -1
    }

    private val ECC_PER_BLOCK_M = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28)
    private val BLOCKS_M = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49)

    /** data codewords at ECC M (qrcodegen getNumDataCodewords) */
    internal fun dataCodewordsM(version: Int): Int {
        var raw = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            raw -= (25 * align - 10) * align - 55
            if (version >= 7) raw -= 36
        }
        return raw / 8 - ECC_PER_BLOCK_M[version] * BLOCKS_M[version]
    }

    /** the n longest lines, in their original order */
    private fun longest(lines: List<ShareLine>, n: Int): List<ShareLine> {
        if (lines.size <= n) return lines
        val length = lines.map { l -> l.points.zipWithNext().sumOf { (a, b) -> exploreDistanceM(a, b) } }
        val keep = lines.indices.sortedWith(compareByDescending<Int> { length[it] }.thenBy { it }).take(n).toSortedSet()
        return keep.map { lines[it] }
    }

    private fun withLines(card: ShareCard, lines: List<ShareLine>, quantExp: Int): ShareCard =
        when (card) {
            is ShareCard.Drive -> card.copy(lines = lines, quantExp = quantExp)
            is ShareCard.Atlas -> card.copy(lines = lines, quantExp = quantExp)
            is ShareCard.Regions -> card
        }
}

/** The lines of a card prepared once: metric projection, DP significance per point, drop order, quantExp. */
private class LineFit(input: List<ShareLine>, chain: Boolean) {
    private class Work(val style: Int, val lat: DoubleArray, val lng: DoubleArray, val pinned: BooleanArray) {
        lateinit var sig: DoubleArray
        var lengthM = 0.0
    }

    private val lines: List<Work> = if (chain) chained(input) else input.filter { it.points.size >= 2 }.map(::work)
    val quantExp: Int
    private val scale: Double

    /** line indices, shortest first */
    private val dropOrder: IntArray
    val size: Int get() = lines.size

    init {
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        for (w in lines) {
            for (v in w.lat) {
                minLat = min(minLat, v)
                maxLat = max(maxLat, v)
            }
            for (v in w.lng) {
                minLng = min(minLng, v)
                maxLng = max(maxLng, v)
            }
        }
        val midLat = if (lines.isEmpty()) 0.0 else (minLat + maxLat) / 2
        val kx = EXPLORE_METERS_PER_DEGREE * StrictMath.cos(midLat * EXPLORE_RAD)
        val ky = EXPLORE_METERS_PER_DEGREE
        val extent = if (lines.isEmpty()) 0.0 else max((maxLat - minLat) * ky, (maxLng - minLng) * kx)
        quantExp = if (extent <= ShareEncoder.FINE_EXTENT_M) 4 else 3
        scale = ShareCodec.scaleOf(quantExp)
        for (w in lines) {
            val x = DoubleArray(w.lat.size) { (w.lng[it] - minLng) * kx }
            val y = DoubleArray(w.lat.size) { (w.lat[it] - minLat) * ky }
            w.sig = dpSignificance(x, y, w.pinned)
            for (k in 1 until x.size) w.lengthM += sqrt((x[k] - x[k - 1]) * (x[k] - x[k - 1]) + (y[k] - y[k - 1]) * (y[k] - y[k - 1]))
        }
        dropOrder = lines.indices.sortedWith(compareBy<Int> { lines[it].lengthM }.thenBy { it }).toIntArray()
    }

    /** lines simplified at tolerance t without the `drop` shortest, quantized (dequantized coordinates), < 2 points left out */
    fun linesAt(t: Double, drop: Int): List<ShareLine> {
        val skip = BooleanArray(lines.size)
        for (k in 0 until min(drop, lines.size)) skip[dropOrder[k]] = true
        val out = ArrayList<ShareLine>()
        for ((i, w) in lines.withIndex()) {
            if (skip[i]) continue
            val points = ArrayList<GeoPoint>()
            var pLat = Long.MIN_VALUE
            var pLng = Long.MIN_VALUE
            for (k in w.lat.indices) {
                if (!(w.sig[k] > t)) continue
                val qLat = Math.round(w.lat[k] * scale)
                val qLng = Math.round(w.lng[k] * scale)
                if (qLat == pLat && qLng == pLng) continue
                points.add(GeoPoint(qLat / scale, qLng / scale))
                pLat = qLat
                pLng = qLng
            }
            if (points.size >= 2) out.add(ShareLine.ofStyle(w.style, points))
        }
        return out
    }

    private fun work(line: ShareLine): Work =
        Work(line.style, DoubleArray(line.points.size) { line.points[it].lat }, DoubleArray(line.points.size) { line.points[it].lng }, BooleanArray(line.points.size))

    /** greedy chaining by nearest end (reversing lines), joining same-style lines that meet end to start */
    private fun chained(input: List<ShareLine>): List<Work> {
        val src = input.filter { it.points.size >= 2 }
        val used = BooleanArray(src.size)
        val out = ArrayList<Work>()
        var lat = ArrayList<Double>()
        var lng = ArrayList<Double>()
        var pins = ArrayList<Boolean>()
        var style = -1
        fun flush() {
            if (lat.isEmpty()) return
            out.add(Work(style, lat.toDoubleArray(), lng.toDoubleArray(), pins.toBooleanArray()))
            lat = ArrayList()
            lng = ArrayList()
            pins = ArrayList()
        }
        var endLat = 0.0
        var endLng = 0.0
        for (step in src.indices) {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            var bestReversed = false
            if (step == 0) {
                best = 0
            } else {
                val k = StrictMath.cos(endLat * EXPLORE_RAD)
                for (i in src.indices) {
                    if (used[i]) continue
                    val p = src[i].points
                    val ds = d2(endLat, endLng, p[0], k)
                    val de = d2(endLat, endLng, p[p.size - 1], k)
                    if (ds < bestD) {
                        best = i
                        bestD = ds
                        bestReversed = false
                    }
                    if (de < bestD) {
                        best = i
                        bestD = de
                        bestReversed = true
                    }
                }
            }
            used[best] = true
            val line = src[best]
            val points = if (bestReversed) line.points.asReversed() else line.points
            val joins = lat.isNotEmpty() && line.style == style && points[0].lat == endLat && points[0].lng == endLng
            if (joins) {
                pins[pins.size - 1] = true
            } else {
                flush()
                style = line.style
            }
            for (k in (if (joins) 1 else 0) until points.size) {
                lat.add(points[k].lat)
                lng.add(points[k].lng)
                pins.add(false)
            }
            endLat = points[points.size - 1].lat
            endLng = points[points.size - 1].lng
        }
        flush()
        return out
    }

    private fun d2(lat: Double, lng: Double, p: GeoPoint, cosLat: Double): Double {
        val dy = p.lat - lat
        val dx = (p.lng - lng) * cosLat
        return dx * dx + dy * dy
    }
}

/** DP significance: ends and pinned points +∞, others the deviation they were picked at, capped by the parent's (0 = never kept) */
internal fun dpSignificance(x: DoubleArray, y: DoubleArray, pinned: BooleanArray): DoubleArray {
    val n = x.size
    val sig = DoubleArray(n)
    if (n == 0) return sig
    val from = ArrayList<Int>()
    val to = ArrayList<Int>()
    val cap = ArrayList<Double>()
    sig[0] = Double.POSITIVE_INFINITY
    var start = 0
    for (k in 1 until n) {
        if (k == n - 1 || pinned[k]) {
            sig[k] = Double.POSITIVE_INFINITY
            from.add(start)
            to.add(k)
            cap.add(Double.POSITIVE_INFINITY)
            start = k
        }
    }
    while (from.isNotEmpty()) {
        val a = from.removeAt(from.size - 1)
        val b = to.removeAt(to.size - 1)
        val c = cap.removeAt(cap.size - 1)
        if (b - a < 2) continue
        val bx = x[b] - x[a]
        val by = y[b] - y[a]
        val len2 = bx * bx + by * by
        var worst = -1
        var worstD = 0.0
        for (i in a + 1 until b) {
            val px = x[i] - x[a]
            val py = y[i] - y[a]
            val d: Double
            if (len2 == 0.0) {
                d = sqrt(px * px + py * py)
            } else {
                val f = max(0.0, min(1.0, (px * bx + py * by) / len2))
                val ex = px - f * bx
                val ey = py - f * by
                d = sqrt(ex * ex + ey * ey)
            }
            if (d > worstD) {
                worst = i
                worstD = d
            }
        }
        if (worst < 0) continue
        val s = min(worstD, c)
        sig[worst] = s
        from.add(a)
        to.add(worst)
        cap.add(s)
        from.add(worst)
        to.add(b)
        cap.add(s)
    }
    return sig
}
