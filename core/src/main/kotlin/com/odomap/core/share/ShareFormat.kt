package com.odomap.core.share

import com.odomap.core.geo.GeoPoint

// QR share format v1 — docs/share-format.md. A card goes car → phone inside the URL fragment of a QR code (no server):
//   https://geunbongit.github.io/odomap/s/#<PAYLOAD>, PAYLOAD = RFC 4648 base32 (upper case, no '=') of the binary card.
// Binary: unsigned LEB128 varints (uv), zigzag + varint for signed (sv), str = uv byte length + UTF-8.
//   u8 version · u8 kind · u8 flags · uv dateDays · uv startMin · uv durationS · uv distance10 · uv pioneered10 · uv driveCount · str title
//   · u8 regionSet · uv visitedTotal · kind 1: uv n + n × u8 new regions, uv m + m × u8 passed regions · kind 3: 29-byte visited bitmap
//   · kind 1/2: u8 quantExp, uv lineCount, per line: u8 style (bit0..2 level, bit3 first-time road), uv pointCount,
//     pointCount × (sv dLat, sv dLng) — coordinate = round(deg × 10^quantExp), delta from the previous point (the first line's first
//     point from (0, 0), a later line's first point from the previous line's last point).
// Readers ignore bytes after the known fields; an unknown version / kind / regionSet means "update the app".

const val SHARE_URL_PREFIX = "https://geunbongit.github.io/odomap/s/#"
const val SHARE_VERSION = 1
const val SHARE_KIND_DRIVE = 1
const val SHARE_KIND_ATLAS = 2
const val SHARE_KIND_REGIONS = 3

/** flags bit0: the drive's start and end are cut off (300 m along the road) */
const val SHARE_FLAG_HIDE_ENDS = 1

/** flags bit1: lines near frequent places (home / work) are left out */
const val SHARE_FLAG_HIDE_PLACES = 2

/** region data set 1 = sgis-20261001: tools/regions/out/regions.json, 230 regions, number = array index */
const val SHARE_REGION_SET = 1
const val SHARE_REGION_BITMAP_BYTES = 29
const val SHARE_TITLE_MAX_BYTES = 48

private const val KST_OFFSET_MS = 9 * 3600 * 1000L
private const val DAY_MS = 86_400_000L

/** epoch day of 2020-01-01 */
private const val EPOCH_2020 = 18262L

/** Korean calendar date of a time as days since 2020-01-01 (dateDays). */
fun shareDateDays(ms: Long): Int = (Math.floorDiv(ms + KST_OFFSET_MS, DAY_MS) - EPOCH_2020).toInt()

/** Minutes since Korean midnight (startMin). */
fun shareStartMin(ms: Long): Int = (Math.floorMod(ms + KST_OFFSET_MS, DAY_MS) / 60_000).toInt()

/** One drawn line: thickness level 1..5, first-time road, points (lat/lng degrees). */
data class ShareLine(val level: Int, val pioneered: Boolean, val points: List<GeoPoint>) {
    /** style byte: bit0..2 level, bit3 first-time road */
    val style: Int get() = (level and 7) or (if (pioneered) 8 else 0)

    companion object {
        fun ofStyle(style: Int, points: List<GeoPoint>): ShareLine = ShareLine(style and 7, style and 8 != 0, points)
    }
}

/** A share card (docs/share-format.md). Distances in 10 m units as on the wire ([distanceM] / [pioneeredM] in metres). */
sealed class ShareCard {
    abstract val kind: Int
    abstract val flags: Int

    /** days since 2020-01-01 (Korean date): the drive's date, or the day the card was made (kinds 2·3) */
    abstract val dateDays: Int

    /** kind 1: this drive · kinds 2·3: all drives (10 m) */
    abstract val distance10: Int

    /** kind 1: first-time road this drive · kinds 2·3: all first-time road = length of the map (10 m) */
    abstract val pioneered10: Int

    /** ≤ 48 UTF-8 bytes (longer titles are cut on a character boundary); empty = the viewer names it by the date */
    abstract val title: String
    abstract val regionSet: Int

    /** regions visited so far ("230곳 중 N곳") */
    abstract val visitedTotal: Int

    val distanceM: Double get() = distance10 * 10.0
    val pioneeredM: Double get() = pioneered10 * 10.0

    /** kind 1 — one drive: its lines split into first-time / known parts (known: thickness level of that road, this drive included). */
    data class Drive(
        override val dateDays: Int,
        /** start time, minutes since midnight */
        val startMin: Int,
        val durationS: Int,
        override val distance10: Int,
        override val pioneered10: Int,
        override val title: String,
        override val visitedTotal: Int,
        /** region numbers first visited on this drive, in order */
        val newRegions: List<Int>,
        /** region numbers passed on this drive (first visits included), in order */
        val passedRegions: List<Int>,
        val lines: List<ShareLine>,
        /** coordinate unit 10^-quantExp degrees — chosen by [ShareEncoder] (4 ≈ 11 m, 3 ≈ 110 m) */
        val quantExp: Int = 4,
        override val flags: Int = SHARE_FLAG_HIDE_ENDS,
        override val regionSet: Int = SHARE_REGION_SET,
    ) : ShareCard() {
        override val kind: Int get() = SHARE_KIND_DRIVE
    }

    /** kind 2 — my map: the whole net (exploreLines smooth) with thickness levels. */
    data class Atlas(
        override val dateDays: Int,
        override val distance10: Int,
        override val pioneered10: Int,
        val driveCount: Int,
        override val title: String,
        override val visitedTotal: Int,
        val lines: List<ShareLine>,
        val quantExp: Int = 3,
        override val flags: Int = SHARE_FLAG_HIDE_PLACES,
        override val regionSet: Int = SHARE_REGION_SET,
    ) : ShareCard() {
        override val kind: Int get() = SHARE_KIND_ATLAS
    }

    /** kind 3 — conquest map: visited regions only (smallest QR). */
    data class Regions(
        override val dateDays: Int,
        override val distance10: Int,
        override val pioneered10: Int,
        val driveCount: Int,
        override val title: String,
        override val visitedTotal: Int,
        /** visited region numbers, ascending (bitmap on the wire) */
        val visited: List<Int>,
        override val flags: Int = 0,
        override val regionSet: Int = SHARE_REGION_SET,
    ) : ShareCard() {
        override val kind: Int get() = SHARE_KIND_REGIONS
    }
}

/** Not a v1 card. [unsupported] = a newer version / kind / region set (viewer: "앱을 업데이트해 주세요"), otherwise corrupt. */
class ShareFormatException(message: String, val unsupported: Boolean = false) : IllegalArgumentException(message)

/** Binary + base32 encoding of [ShareCard] exactly as given (no simplification — see [ShareEncoder] for fitting a QR). */
object ShareCodec {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Card → bytes. Lines are quantized (round(deg × 10^quantExp)), repeated points dropped, lines left with < 2 points dropped;
     * the title is cut to 48 bytes. decode(encode(card)) is the card as a viewer sees it.
     */
    fun encode(card: ShareCard): ByteArray {
        val w = Writer()
        w.u8(SHARE_VERSION)
        w.u8(card.kind)
        w.u8(card.flags)
        w.uv(card.dateDays.toLong())
        w.uv((card as? ShareCard.Drive)?.startMin?.toLong() ?: 0)
        w.uv((card as? ShareCard.Drive)?.durationS?.toLong() ?: 0)
        w.uv(card.distance10.toLong())
        w.uv(card.pioneered10.toLong())
        w.uv(
            when (card) {
                is ShareCard.Drive -> 0
                is ShareCard.Atlas -> card.driveCount.toLong()
                is ShareCard.Regions -> card.driveCount.toLong()
            },
        )
        w.bytes(truncateUtf8(card.title, SHARE_TITLE_MAX_BYTES), withLength = true)
        w.u8(card.regionSet)
        w.uv(card.visitedTotal.toLong())
        when (card) {
            is ShareCard.Drive -> {
                w.uv(card.newRegions.size.toLong())
                for (r in card.newRegions) w.u8(r)
                w.uv(card.passedRegions.size.toLong())
                for (r in card.passedRegions) w.u8(r)
                writeLines(w, card.quantExp, card.lines)
            }
            is ShareCard.Atlas -> writeLines(w, card.quantExp, card.lines)
            is ShareCard.Regions -> {
                val bitmap = ByteArray(SHARE_REGION_BITMAP_BYTES)
                for (r in card.visited) {
                    require(r in 0 until SHARE_REGION_BITMAP_BYTES * 8) { "region number $r" }
                    bitmap[r / 8] = (bitmap[r / 8].toInt() or (1 shl (r % 8))).toByte()
                }
                w.bytes(bitmap, withLength = false)
            }
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): ShareCard {
        val r = Reader(bytes)
        val version = r.u8()
        if (version != SHARE_VERSION) throw ShareFormatException("version $version", unsupported = true)
        val kind = r.u8()
        if (kind !in SHARE_KIND_DRIVE..SHARE_KIND_REGIONS) throw ShareFormatException("kind $kind", unsupported = true)
        val flags = r.u8()
        val dateDays = r.int()
        val startMin = r.int()
        val durationS = r.int()
        val distance10 = r.int()
        val pioneered10 = r.int()
        val driveCount = r.int()
        val title = String(r.bytes(r.int()), Charsets.UTF_8)
        val regionSet = r.u8()
        if (regionSet != SHARE_REGION_SET) throw ShareFormatException("regionSet $regionSet", unsupported = true)
        val visitedTotal = r.int()
        return when (kind) {
            SHARE_KIND_DRIVE -> {
                val newRegions = List(r.count(1)) { r.u8() }
                val passed = List(r.count(1)) { r.u8() }
                val (quantExp, lines) = readLines(r)
                ShareCard.Drive(dateDays, startMin, durationS, distance10, pioneered10, title, visitedTotal, newRegions, passed, lines, quantExp, flags, regionSet)
            }
            SHARE_KIND_ATLAS -> {
                val (quantExp, lines) = readLines(r)
                ShareCard.Atlas(dateDays, distance10, pioneered10, driveCount, title, visitedTotal, lines, quantExp, flags, regionSet)
            }
            else -> {
                val bitmap = r.bytes(SHARE_REGION_BITMAP_BYTES)
                val visited = (0 until SHARE_REGION_BITMAP_BYTES * 8).filter { bitmap[it / 8].toInt() and (1 shl (it % 8)) != 0 }
                ShareCard.Regions(dateDays, distance10, pioneered10, driveCount, title, visitedTotal, visited, flags, regionSet)
            }
        }
    }

    fun payload(card: ShareCard): String = base32(encode(card))

    fun decodePayload(payload: String): ShareCard = decode(unbase32(payload))

    /** a share URL (or just the part after '#') */
    fun decodeUrl(url: String): ShareCard = decodePayload(url.substringAfter('#'))

    /** RFC 4648 base32, upper case, no padding */
    fun base32(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** base32 → bytes (lower case accepted, '=' padding ignored, leftover bits < 8 dropped) */
    fun unbase32(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 5 / 8 + 1)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            val v =
                when (ch) {
                    in 'A'..'Z' -> ch - 'A'
                    in 'a'..'z' -> ch - 'a'
                    in '2'..'7' -> ch - '2' + 26
                    else -> throw ShareFormatException("base32 character '$ch'")
                }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xff)
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    /** UTF-8 bytes of the text, cut to at most maxBytes on a character boundary */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end -= 1
        return bytes.copyOf(end)
    }

    /** the title as it will read after encoding */
    fun truncateTitle(title: String): String = String(truncateUtf8(title, SHARE_TITLE_MAX_BYTES), Charsets.UTF_8)

    /** quantized line points with repeats dropped: [lat0, lng0, lat1, lng1, …] */
    internal fun quantize(points: List<GeoPoint>, quantExp: Int): LongArray {
        val scale = scaleOf(quantExp)
        val out = LongArray(points.size * 2)
        var n = 0
        for (p in points) {
            val qLat = Math.round(p.lat * scale)
            val qLng = Math.round(p.lng * scale)
            if (n >= 2 && out[n - 2] == qLat && out[n - 1] == qLng) continue
            out[n++] = qLat
            out[n++] = qLng
        }
        return out.copyOf(n)
    }

    internal fun scaleOf(quantExp: Int): Double {
        require(quantExp in 0..9) { "quantExp $quantExp" }
        var s = 1.0
        repeat(quantExp) { s *= 10 }
        return s
    }

    private fun writeLines(w: Writer, quantExp: Int, lines: List<ShareLine>) {
        val quantized = ArrayList<Pair<Int, LongArray>>(lines.size)
        for (line in lines) {
            require(line.level in 1..5) { "line level ${line.level}" }
            val q = quantize(line.points, quantExp)
            if (q.size >= 4) quantized.add(line.style to q)
        }
        w.u8(quantExp)
        w.uv(quantized.size.toLong())
        var pLat = 0L
        var pLng = 0L
        for ((style, q) in quantized) {
            w.u8(style)
            w.uv((q.size / 2).toLong())
            var k = 0
            while (k < q.size) {
                w.sv(q[k] - pLat)
                w.sv(q[k + 1] - pLng)
                pLat = q[k]
                pLng = q[k + 1]
                k += 2
            }
        }
    }

    private fun readLines(r: Reader): Pair<Int, List<ShareLine>> {
        val quantExp = r.u8()
        if (quantExp > 9) throw ShareFormatException("quantExp $quantExp")
        val scale = scaleOf(quantExp)
        val lines = ArrayList<ShareLine>()
        var lat = 0L
        var lng = 0L
        repeat(r.count(3)) {
            val style = r.u8()
            val points = ArrayList<GeoPoint>()
            repeat(r.count(2)) {
                lat += r.sv()
                lng += r.sv()
                points.add(GeoPoint(lat / scale, lng / scale))
            }
            lines.add(ShareLine.ofStyle(style, points))
        }
        return quantExp to lines
    }

    private class Writer {
        private var buf = ByteArray(512)
        private var size = 0

        fun u8(v: Int) {
            require(v in 0..255) { "u8 $v" }
            put(v)
        }

        fun uv(v: Long) {
            require(v >= 0) { "uv $v" }
            var x = v
            while (x >= 0x80) {
                put(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            put(x.toInt())
        }

        fun sv(v: Long) = uv((v shl 1) xor (v shr 63))

        fun bytes(b: ByteArray, withLength: Boolean) {
            if (withLength) uv(b.size.toLong())
            for (x in b) put(x.toInt() and 0xff)
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun put(v: Int) {
            if (size == buf.size) buf = buf.copyOf(size * 2)
            buf[size++] = v.toByte()
        }
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0

        fun u8(): Int {
            if (i >= b.size) throw ShareFormatException("truncated card")
            return b[i++].toInt() and 0xff
        }

        fun uv(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val x = u8()
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 56) throw ShareFormatException("varint too long")
            }
        }

        fun sv(): Long {
            val u = uv()
            return (u ushr 1) xor -(u and 1)
        }

        fun int(): Int {
            val v = uv()
            if (v > Int.MAX_VALUE) throw ShareFormatException("value $v too large")
            return v.toInt()
        }

        /** an element count, sanity-checked against the bytes left (each element takes at least minBytes) */
        fun count(minBytes: Int): Int {
            val n = int()
            if (n.toLong() * minBytes > b.size - i) throw ShareFormatException("truncated card")
            return n
        }

        fun bytes(n: Int): ByteArray {
            if (n > b.size - i) throw ShareFormatException("truncated card")
            val out = b.copyOfRange(i, i + n)
            i += n
            return out
        }
    }
}
