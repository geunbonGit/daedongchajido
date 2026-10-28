package com.odomap.core.share

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.odomap.core.Diff
import com.odomap.core.TestRegions
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.LatLng
import io.nayuki.qrcodegen.QrCode
import io.nayuki.qrcodegen.QrSegment
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// Share format v1 (docs/share-format.md): codec units, privacy rules, fitting into a version ≤ 20 QR, and the test vectors for the web viewer
// (src/test/resources/share/vectors.json — rewritten with ./gradlew :core:test -PodomapWriteVectors, otherwise compared).
class ShareTest {
    private val index get() = TestRegions.index

    // ── codec

    @Test
    fun `base32 is RFC 4648 upper case without padding`() {
        val cases = listOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
        for ((raw, b32) in cases) {
            assertEquals(b32, ShareCodec.base32(raw.toByteArray()))
            assertEquals(raw, String(ShareCodec.unbase32(b32)))
            assertEquals(raw, String(ShareCodec.unbase32(b32.lowercase() + "====")))
            assertEquals(b32.length, ShareEncoder.base32Length(raw.length))
        }
        assertTrue(ShareCodec.base32(ByteArray(256) { it.toByte() }).all { it in 'A'..'Z' || it in '2'..'7' })
    }

    private fun onGrid(lat: Double, lng: Double, quantExp: Int = 4): GeoPoint {
        val s = ShareCodec.scaleOf(quantExp)
        return GeoPoint(Math.round(lat * s) / s, Math.round(lng * s) / s)
    }

    @Test
    fun `cards round-trip — varints, zigzag deltas across hemispheres, region lists, bitmap`() {
        val lines =
            listOf(
                ShareLine(1, true, listOf(onGrid(37.5495, 126.9139), onGrid(37.5501, 126.9150), onGrid(37.5512, 126.9171))),
                ShareLine(5, false, listOf(onGrid(-33.8688, 151.2093), onGrid(-33.8700, 151.2101))),
                ShareLine(3, false, listOf(onGrid(64.1466, -21.9426), onGrid(0.0, 0.0), onGrid(-0.0001, -179.9999))),
            )
        val drive = ShareCard.Drive(2490, 1439, 1_000_000, 22_010, 20_853, "강릉 여행", 229, listOf(0, 229, 128), listOf(5, 0, 229, 128), lines, 4, 3)
        assertEquals(drive, ShareCodec.decodePayload(ShareCodec.payload(drive)))
        val atlas = ShareCard.Atlas(0, 1, 0, 300_000, "", 0, lines.map { l -> l.copy(points = l.points.map { onGrid(it.lat, it.lng, 3) }) }, 3, 2)
        assertEquals(atlas, ShareCodec.decodePayload(ShareCodec.payload(atlas)))
        val regions = ShareCard.Regions(9999, 0, 0, 0, "정복", 3, listOf(0, 7, 8, 229))
        val bytes = ShareCodec.encode(regions)
        assertEquals(regions, ShareCodec.decode(bytes))
        // version, kind, flags, dateDays (2 bytes), 5 zero varints, title (1 + 6), regionSet, visitedTotal, bitmap 29
        assertEquals(1 + 1 + 1 + 2 + 1 + 1 + 1 + 1 + 1 + 7 + 1 + 1 + 29, bytes.size)
        assertEquals(0b1000_0001, bytes[bytes.size - 29].toInt() and 0xff) // regions 0 and 7 → byte 0, bits 0 and 7 (LSB first)
        assertEquals(1, bytes[bytes.size - 28].toInt() and 0xff) // region 8 → byte 1 bit 0
        assertEquals(0b0010_0000, bytes[bytes.size - 1].toInt() and 0xff) // region 229 → byte 28 bit 5
        // quantization: repeated points dropped, a line collapsing to one point dropped
        val messy = drive.copy(lines = listOf(ShareLine(2, false, listOf(GeoPoint(37.50001, 127.0), GeoPoint(37.50002, 127.00001), GeoPoint(37.6, 127.1))), ShareLine(2, false, listOf(GeoPoint(37.0, 127.0), GeoPoint(37.00002, 127.00002)))))
        val back = ShareCodec.decode(ShareCodec.encode(messy)) as ShareCard.Drive
        assertEquals(listOf(listOf(GeoPoint(37.5, 127.0), GeoPoint(37.6, 127.1))), back.lines.map { it.points })
    }

    @Test
    fun `titles are cut to 48 bytes on a character boundary`() {
        val exact = "가평에서춘천까지처음달려본가을길" // 16 syllables = 48 bytes
        assertEquals(48, exact.toByteArray().size)
        assertEquals(exact, ShareCodec.truncateTitle(exact))
        assertEquals(exact, ShareCodec.truncateTitle("$exact!")) // byte 48 is '!': cut exactly after the 16th syllable
        assertEquals("a" + exact.dropLast(1), ShareCodec.truncateTitle("a$exact")) // 49 bytes → 46
        assertEquals(exact, ShareCodec.truncateTitle(exact + "가"))
        assertEquals("", ShareCodec.truncateTitle(""))
        val card = ShareCard.Regions(0, 0, 0, 0, "a$exact", 0, emptyList())
        assertEquals("a" + exact.dropLast(1), ShareCodec.decode(ShareCodec.encode(card)).title)
    }

    @Test
    fun `newer version, kind or region set is unsupported, cut payloads are corrupt, trailing bytes are ignored`() {
        val card = ShareCard.Regions(100, 5, 5, 1, "x", 1, listOf(3))
        val bytes = ShareCodec.encode(card)
        fun expect(unsupported: Boolean, b: ByteArray) {
            try {
                ShareCodec.decode(b)
                fail("decoded ${b.toList()}")
            } catch (e: ShareFormatException) {
                assertEquals(e.message, unsupported, e.unsupported)
            }
        }
        expect(true, bytes.copyOf().also { it[0] = 2 })
        expect(true, bytes.copyOf().also { it[1] = 4 })
        // regionSet byte: after version, kind, flags, 6 varints (1+1+1+1+1+1 here) and the title (1 + 1)
        val regionSetAt = 3 + 6 + 2
        assertEquals(1, bytes[regionSetAt].toInt())
        expect(true, bytes.copyOf().also { it[regionSetAt] = 2 })
        expect(false, bytes.copyOf(bytes.size - 3))
        expect(false, ByteArray(0))
        assertEquals(card, ShareCodec.decode(bytes + byteArrayOf(7, 7, 7)))
        try {
            ShareCodec.decodePayload("AB!C")
            fail()
        } catch (e: ShareFormatException) {
            assertTrue(!e.unsupported)
        }
    }

    @Test
    fun `QR version arithmetic equals qrcodegen`() {
        for (prefix in listOf(0, 1, 40, 300)) {
            var n = 0
            while (n <= 1900) {
                val ours = ShareEncoder.qrVersion(prefix, n, 40)
                val segs = listOf(QrSegment.makeBytes(ByteArray(prefix) { 'a'.code.toByte() }), QrSegment.makeAlphanumeric("A".repeat(n)))
                val theirs = try {
                    QrCode.encodeSegments(segs, QrCode.Ecc.MEDIUM, 1, 40, 0, false).version
                } catch (e: io.nayuki.qrcodegen.DataTooLongException) {
                    -1
                }
                assertEquals("prefix $prefix chars $n", theirs, ours)
                n += if (n < 200) 1 else 7
            }
        }
        // the share URL prefix: version 20 holds 898 base32 characters = 561 payload bytes
        val prefix = SHARE_URL_PREFIX.toByteArray().size
        assertEquals(47, prefix)
        assertEquals(20, ShareEncoder.qrVersion(prefix, ShareEncoder.base32Length(561)))
        assertEquals(-1, ShareEncoder.qrVersion(prefix, ShareEncoder.base32Length(562)))
    }

    // ── privacy rules

    private fun minDistanceM(lines: List<ShareLine>, p: LatLng): Double = lines.flatMap { it.points }.minOf { exploreDistanceM(it, p) }

    private fun lengthM(lines: List<ShareLine>): Double = lines.sumOf { l -> l.points.zipWithNext().sumOf { (a, b) -> exploreDistanceM(a, b) } }

    @Test
    fun `drive cards leave out 300 m along the path at both ends`() {
        val rows = ArrayList<String>()
        for (d in ShareFixtures.drives) {
            val segments = d.replay.segments
            val total = d.replay.lengthM
            // unsmoothed lines are node to node: every drawn step is an edge never driven in the first / last 300 m of the path
            val keyOf = HashMap<List<Double>, Long>()
            for (s in segments) {
                keyOf[listOf(s.fromLat, s.fromLng, s.toLat, s.toLng)] = s.key
                keyOf[listOf(s.toLat, s.toLng, s.fromLat, s.fromLng)] = s.key
            }
            val nearEnds = segments.filter { it.startM < SHARE_TRIM_M || it.endM > total - SHARE_TRIM_M }.map { it.key }.toSet()
            val drawnKeys = driveShareLines(d.replay, smooth = false).flatMap { l -> l.points.zipWithNext().map { (a, b) -> keyOf[listOf(a.lat, a.lng, b.lat, b.lng)]!! } }
            assertEquals(drawnKeys.size, drawnKeys.toSet().size)
            assertEquals(segments.map { it.key }.toSet() - nearEnds, drawnKeys.toSet())
            // the card: flag, and the path minus ~300 m at each end
            val hidden = d.card()
            val shown = d.card(hideEnds = false)
            assertEquals(SHARE_FLAG_HIDE_ENDS, hidden.flags)
            assertEquals(0, shown.flags)
            val first = d.drive.points.first()
            val last = d.drive.points.last()
            assertTrue(minDistanceM(shown.lines, first) < 40 && minDistanceM(shown.lines, last) < 40)
            assertTrue("${d.drive.id} drawn ${lengthM(hidden.lines)} of ${lengthM(shown.lines)}, path ${d.replay.lengthM}, near-end edges ${nearEnds.size}, re-passed ${segments.size - segments.map { it.key }.toSet().size}", lengthM(hidden.lines) in (lengthM(shown.lines) - 700)..(lengthM(shown.lines) - 550))
            val radius = d.card(hideRadiusM = SHARE_TRIM_M)
            rows.add("%s %.0f/%.0f m (+radius %.0f/%.0f)".format(d.drive.id, minDistanceM(hidden.lines, first), minDistanceM(hidden.lines, last), minDistanceM(radius.lines, first), minDistanceM(radius.lines, last)))
            assertTrue(minDistanceM(radius.lines, first) >= SHARE_TRIM_M - 25 && minDistanceM(radius.lines, last) >= SHARE_TRIM_M - 25)
        }
        println("[share] nearest drawn point to the first/last fix (along-path 300 m; with the optional 300 m radius): " + rows.joinToString(", "))
    }

    @Test
    fun `frequent places are home and work — the atlas keeps 500 m away from them`() {
        val places = frequentPlaces(ShareFixtures.endpoints)
        assertEquals(2, places.size)
        assertTrue(exploreDistanceM(places[0], GeoPoint(37.5495, 126.9139)) < 100) // 합정 (home): 13 of the 28 endpoints
        assertTrue(exploreDistanceM(places[1], GeoPoint(37.5006, 127.0366)) < 100) // 역삼 (work)
        val atlas = ShareCards.atlas(ShareFixtures.graph, ShareFixtures.createdAt, ShareFixtures.totalDrivenM, 14, "", 23, ShareFixtures.endpoints)
        assertEquals(SHARE_FLAG_HIDE_PLACES, atlas.flags)
        for (p in places) assertTrue(minDistanceM(atlas.lines, p) >= SHARE_PLACE_HIDE_M - 0.5)
        val qr = ShareEncoder.encode(atlas)
        // after simplification and 10^-3 degree quantization (≤ 78 m) the lines still stay clear
        for (p in places) assertTrue(minDistanceM((qr.card as ShareCard.Atlas).lines, p) >= 400)
        // without endpoints nothing is hidden
        val open = ShareCards.atlas(ShareFixtures.graph, ShareFixtures.createdAt, 0.0, 0, "", 0, emptyList())
        assertEquals(0, open.flags)
        assertTrue(minDistanceM(open.lines, places[0]) < 100)
        // pure helpers
        assertEquals(emptyList<GeoPoint>(), frequentPlaces(emptyList()))
        val line = ShareLine(1, false, listOf(GeoPoint(37.5, 126.99), GeoPoint(37.5, 127.01)))
        val cut = hideNearPlaces(listOf(line), listOf(GeoPoint(37.5, 127.0)), 500.0)
        assertEquals(2, cut.size)
        assertEquals(500.0, exploreDistanceM(cut[0].points.last(), GeoPoint(37.5, 127.0)), 0.5)
        assertEquals(500.0, exploreDistanceM(cut[1].points.first(), GeoPoint(37.5, 127.0)), 0.5)
    }

    // ── vectors

    private class Vector(val name: String, val about: String, val input: Map<String, Any?>, val card: ShareCard)

    private fun vectors(): List<Vector> {
        val f = ShareFixtures
        fun labels(numbers: List<Int>) = numbers.map { index.regions[it].label }
        fun driveVector(name: String, about: String, id: String, title: String? = null, hideEnds: Boolean = true): Vector {
            val d = f.driven(id)
            val card = d.card(title ?: d.drive.title, hideEnds)
            return Vector(
                name, about,
                linkedMapOf(
                    "drive" to id, "title" to card.title, "titleBytes" to card.title.toByteArray().size, "hideEnds" to hideEnds,
                    "drivenKm" to Math.round(d.summary.drivenM) / 1000.0, "pioneeredKm" to Math.round(d.summary.pioneeredM) / 1000.0,
                    "linesIn" to card.lines.size, "pointsIn" to card.lines.sumOf { it.points.size },
                    "newRegionLabels" to labels(card.newRegions), "passedRegionLabels" to labels(card.passedRegions),
                ),
                card,
            )
        }
        val exact48 = "가평에서춘천까지처음달려본가을길"
        val atlas = ShareCards.atlas(f.graph, f.createdAt, f.totalDrivenM, f.drives.size, "내 지도", f.visited.size, f.endpoints)
        val visited = index.indicesOf(f.visited)
        val tiny =
            ShareCard.Drive(
                dateDays = shareDateDays(1_792_969_920_000), startMin = shareStartMin(1_792_969_920_000), durationS = 125, distance10 = 123, pioneered10 = 45,
                title = "Tiny", visitedTotal = 2, newRegions = listOf(12), passedRegions = listOf(12, 2),
                lines =
                    listOf(
                        ShareLine(3, false, listOf(GeoPoint(37.5495, 126.9139), GeoPoint(37.5505, 126.915), GeoPoint(37.5512, 126.9171))),
                        ShareLine(1, true, listOf(GeoPoint(37.5512, 126.9171), GeoPoint(37.549, 126.9205))),
                    ),
                flags = 0,
            )
        return listOf(
            Vector("tiny", "hand-made drive card: two lines (known level 3, then a first-time line), one new region — start here", linkedMapOf("note" to "points are on the 10^-4 grid, nothing is simplified away"), tiny),
            driveVector("drive-commute", "5th morning commute: all known roads, levels 3–4, ends hidden", "commute-5-am"),
            driveVector("drive-new-regions", "weekend drive with 8 first-visit regions; title exactly 48 bytes (16 Hangul syllables)", "weekend-gapyeong", exact48),
            driveVector("drive-long-trip", "220 km trip: extent > 40 km → quantExp 3, tolerance raised to fit version 20", "trip-gangneung"),
            driveVector("drive-empty-title", "detour commute with a new region, empty title (viewer names it by date), ends not hidden (flags 0)", "commute-3-am", "", hideEnds = false),
            Vector(
                "atlas", "my map after the 14 demo drives, home/work (2 most frequent endpoints) hidden 500 m",
                linkedMapOf("drives" to f.drives.size, "linesIn" to atlas.lines.size, "pointsIn" to atlas.lines.sumOf { it.points.size }, "places" to frequentPlaces(f.endpoints).map { listOf(it.lat, it.lng) }),
                atlas,
            ),
            Vector(
                "regions", "conquest map: the 23 regions of the demo drives",
                linkedMapOf("visitedLabels" to labels(visited)),
                ShareCards.regions(f.createdAt, f.totalDrivenM, netLengthM(f.graph), f.drives.size, "정복 지도", visited),
            ),
            Vector(
                "regions-all-title-cut", "all 230 regions (full bitmap); title 'a' + 16 syllables = 49 bytes → cut to 46 on a character boundary",
                linkedMapOf("titleIn" to "a$exact48", "titleInBytes" to "a$exact48".toByteArray().size),
                ShareCards.regions(f.createdAt, 1_234_567.0, 98_765.0, 321, "a$exact48", (0 until 230).toList()),
            ),
        )
    }

    private fun lineJson(l: ShareLine): Map<String, Any?> = linkedMapOf("style" to l.style, "level" to l.level, "new" to l.pioneered, "points" to l.points.map { listOf(it.lat, it.lng) })

    private fun decodedJson(c: ShareCard): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "version" to SHARE_VERSION, "kind" to c.kind, "flags" to c.flags, "dateDays" to c.dateDays,
            "startMin" to ((c as? ShareCard.Drive)?.startMin ?: 0), "durationS" to ((c as? ShareCard.Drive)?.durationS ?: 0),
            "distance10" to c.distance10, "pioneered10" to c.pioneered10,
            "driveCount" to when (c) {
                is ShareCard.Drive -> 0
                is ShareCard.Atlas -> c.driveCount
                is ShareCard.Regions -> c.driveCount
            },
            "title" to c.title, "regionSet" to c.regionSet, "visitedTotal" to c.visitedTotal,
        )
        when (c) {
            is ShareCard.Drive -> {
                m["newRegions"] = c.newRegions
                m["passedRegions"] = c.passedRegions
                m["quantExp"] = c.quantExp
                m["lines"] = c.lines.map(::lineJson)
            }
            is ShareCard.Atlas -> {
                m["quantExp"] = c.quantExp
                m["lines"] = c.lines.map(::lineJson)
            }
            is ShareCard.Regions -> m["visited"] = c.visited
        }
        return m
    }

    @Test
    fun `share vectors — version 20 at most, QR reads back, decode = encoded card, vectors_json up to date`() {
        val prefixBytes = SHARE_URL_PREFIX.toByteArray().size
        val out = ArrayList<Any?>()
        val table = ArrayList<String>()
        for (v in vectors()) {
            val t0 = System.nanoTime()
            val qr = ShareEncoder.encode(v.card)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue(v.name, qr.version in 1..20)
            assertEquals(v.name, ShareEncoder.qrVersion(prefixBytes, qr.payload.length), qr.version)
            assertEquals(SHARE_URL_PREFIX + qr.payload, qr.url)
            val decoded = ShareCodec.decodeUrl(qr.url)
            assertEquals(v.name, qr.card, decoded)
            val bytes = ShareCodec.unbase32(qr.payload)
            assertEquals(qr.bytes, bytes.size)
            assertArrayEquals(v.name, bytes, ShareCodec.encode(decoded))
            // the module matrix is a QR code of the URL (zxing reads it)
            val matrix = BitMatrix(qr.size)
            for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.isDark(x, y)) matrix.set(x, y)
            assertEquals(v.name, qr.url, Decoder().decode(matrix).text)
            val lines = (decoded as? ShareCard.Drive)?.lines ?: (decoded as? ShareCard.Atlas)?.lines ?: emptyList()
            val quantExp = (decoded as? ShareCard.Drive)?.quantExp ?: (decoded as? ShareCard.Atlas)?.quantExp
            table.add(
                "%-22s kind %d  lines %3d  points %4d  bytes %3d  base32 %4d  QR v%-2d %c  quantExp %s  tolerance %6.1f m  dropped %d  %3.0f ms".format(
                    v.name, decoded.kind, lines.size, lines.sumOf { it.points.size }, qr.bytes, qr.payload.length, qr.version, qr.ecc,
                    quantExp ?: "-", qr.toleranceM, qr.droppedLines, ms,
                ),
            )
            out.add(
                linkedMapOf(
                    "name" to v.name, "about" to v.about, "input" to v.input, "payload" to qr.payload, "url" to qr.url, "bytes" to qr.bytes,
                    "qr" to linkedMapOf("version" to qr.version, "ecc" to qr.ecc.toString(), "size" to qr.size),
                    "toleranceM" to Math.round(qr.toleranceM * 1000) / 1000.0, "droppedLines" to qr.droppedLines,
                    "decoded" to decodedJson(decoded),
                ),
            )
        }
        for (row in table) println("[share] $row")
        val doc =
            linkedMapOf(
                "format" to "odomap-share-vectors/1",
                "spec" to "docs/share-format.md",
                "generatedBy" to "core/src/test/kotlin/com/odomap/core/share/ShareTest.kt (./gradlew :core:test -PodomapWriteVectors)",
                "urlPrefix" to SHARE_URL_PREFIX,
                "regionSet" to SHARE_REGION_SET,
                "notes" to
                    listOf(
                        "decode 'payload' (base32, no padding) and compare with 'decoded' field by field",
                        "points = [lat, lng] degrees = quantized integer / 10^quantExp — divide (q / 1e4), do not multiply by 1e-4, for bit-equal numbers",
                        "region numbers index the regions array of tools/regions/out/regions.json (regionSet 1); 'input' holds labels for reading only",
                        "dateDays = days since 2020-01-01 (Korean date), startMin = minutes since midnight KST",
                    ),
                "vectors" to out,
            )
        val text = JsonOut.render(doc) + "\n"
        val file = File("src/test/resources/share/vectors.json")
        if (System.getProperty("odomap.writeVectors") == "true" || !file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(text)
            println("[share] wrote ${file.path} (${text.length} chars)")
        } else {
            val diff = Diff("vectors.json")
            diff.cmp("", Json.parseToJsonElement(file.readText()), Json.parseToJsonElement(text))
            assertTrue(
                "vectors.json is out of date (engine or format changed?) — regenerate with ./gradlew :core:test -PodomapWriteVectors:\n" + diff.errors.joinToString("\n"),
                diff.errors.isEmpty(),
            )
        }
    }
}

/** Small JSON writer for the vectors file: objects one key per line, arrays of numbers / of points on one line. */
internal object JsonOut {
    fun render(value: Any?): String = StringBuilder().also { write(it, value, "") }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: String) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val inner = "$indent  "
                var i = 0
                for ((k, x) in v) {
                    sb.append(inner)
                    quote(sb, k.toString())
                    sb.append(": ")
                    write(sb, x, inner)
                    if (++i < v.size) sb.append(',')
                    sb.append('\n')
                }
                sb.append(indent).append('}')
            }
            is List<*> -> {
                val flat = v.all { it == null || it is Number || it is String || it is Boolean || (it is List<*> && it.all { x -> x is Number }) }
                if (flat) {
                    sb.append('[')
                    v.forEachIndexed { i, x ->
                        if (i > 0) sb.append(',')
                        write(sb, x, indent)
                    }
                    sb.append(']')
                    return
                }
                sb.append("[\n")
                val inner = "$indent  "
                v.forEachIndexed { i, x ->
                    sb.append(inner)
                    write(sb, x, inner)
                    if (i < v.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(indent).append(']')
            }
            else -> error("json: ${v::class}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
