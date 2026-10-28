package com.odomap.core.region

import com.odomap.core.DemoDrives
import com.odomap.core.TestRegions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// Region lookup and tracking against tools/regions/validate_regions.py (the reference) and its output demo-region-sequence.json.
class RegionTest {
    private val index get() = TestRegions.index

    // ── reference: a literal port of validate_regions.py (no grid, no ring bbox) to check the fast path against

    private fun refRing(ring: IntArray, lat: Int, lng: Int): Boolean {
        var inside = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = ring[2 * i]
            val xi = ring[2 * i + 1]
            val yj = ring[2 * j]
            val xj = ring[2 * j + 1]
            if ((yi > lat) != (yj > lat)) {
                val x = xi + ((lat - yi).toLong() * (xj - xi)).toDouble() / (yj - yi)
                if (lng < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun refLocate(lat: Double, lng: Double): Int {
        val la = Math.rint(lat * 1e6).toInt()
        val ln = Math.rint(lng * 1e6).toInt()
        for (r in index.regions) {
            if (!(r.minLatE6 <= la && la <= r.maxLatE6 && r.minLngE6 <= ln && ln <= r.maxLngE6)) continue
            for (poly in r.polygons) {
                if (!refRing(poly.outer.coords, la, ln)) continue
                if (poly.holes.any { refRing(it.coords, la, ln) }) continue
                return r.index
            }
        }
        return -1
    }

    @Test
    fun `asset loads — 230 regions, region set 1, attribution`() {
        assertEquals(230, index.regions.size)
        assertEquals(1, index.regionSet)
        assertEquals("ver20261001", index.sourceVersion)
        assertTrue(index.attribution.contains("CC BY 4.0"))
        index.regions.forEachIndexed { i, r -> assertEquals(i, r.index) }
        assertEquals(230, index.regions.map { it.label }.toSet().size)
        assertEquals("경기 가평군", index.byCode("41820")!!.label)
        println("[regions] ${index.gridInfo}")
    }

    @Test
    fun `point tests of validate_regions_py`() {
        // (place, lat, lng, expected short name) — validate_regions.py TESTS
        val tests =
            listOf(
                Triple("서울 합정역", 37.5495 to 126.9139, "마포구"),
                Triple("역삼역", 37.5006 to 127.0366, "강남구"),
                Triple("남이섬 (routes NAMI 좌표)", 37.7906 to 127.5258, "춘천시"),
                Triple("가평역", 37.8145 to 127.5106, "가평군"),
                Triple("강릉 경포", 37.7519 to 128.8761, "강릉시"),
                Triple("주문진항", 37.8925 to 128.8215, "강릉시"),
                Triple("양양 낙산", 38.1245 to 128.6275, "양양군"),
                Triple("속초항", 38.2070 to 128.5918, "속초시"),
                Triple("제주공항", 33.5104 to 126.4914, "제주시"),
                Triple("수원 화성행궁", 37.2818 to 127.0135, "수원시"),
                Triple("서귀포 중문", 33.2496 to 126.4120, "서귀포시"),
                Triple("독도 동도", 37.2408 to 131.8696, "울릉군"),
                Triple("광주 5·18 민주광장", 35.1468 to 126.9197, "동구"),
                Triple("인천공항", 37.4602 to 126.4407, "영종구"),
            )
        // the same 14 with the reference's full names (demo-region-sequence.json pointTests)
        val expectedFull = Json.parseToJsonElement(DemoDrives.gzText("/regions/demo-region-sequence.json.gz")).jsonObject["pointTests"]!!.jsonArray
        assertEquals(14, expectedFull.size)
        tests.forEachIndexed { i, (place, at, want) ->
            val r = index.regionAt(at.first, at.second)
            assertEquals(place, want, r?.name)
            assertEquals(place, expectedFull[i].jsonObject["got"]!!.jsonPrimitive.content, r?.full)
        }
        assertNull("동해 바다", index.regionAt(37.5, 130.0))
        assertNull("out of range", index.regionAt(0.0, 0.0))
        assertNull(index.regionAt(Double.NaN, 127.0))
    }

    @Test
    fun `grid lookup equals the reference everywhere — random, land, border-hugging and drive points`() {
        val random = Random(20261001)
        val regions = index.regions
        var checked = 0
        fun check(lat: Double, lng: Double) {
            val want = refLocate(lat, lng)
            val got = index.indexAt(lat, lng)
            if (want != got) throw AssertionError("($lat, $lng): grid $got reference $want")
            checked += 1
        }
        val minLat = regions.minOf { it.minLatE6 } / 1e6
        val maxLat = regions.maxOf { it.maxLatE6 } / 1e6
        val minLng = regions.minOf { it.minLngE6 } / 1e6
        val maxLng = regions.maxOf { it.maxLngE6 } / 1e6
        repeat(60_000) { check(minLat + random.nextDouble() * (maxLat - minLat), minLng + random.nextDouble() * (maxLng - minLng)) }
        // inside random region bboxes (mostly land)
        repeat(60_000) {
            val r = regions[random.nextInt(regions.size)]
            check((r.minLatE6 + random.nextDouble() * (r.maxLatE6 - r.minLatE6)) / 1e6, (r.minLngE6 + random.nextDouble() * (r.maxLngE6 - r.minLngE6)) / 1e6)
        }
        // on and within a few µdeg of ring vertices and edge midpoints (exact border arithmetic)
        repeat(40_000) {
            val r = regions[random.nextInt(regions.size)]
            val poly = r.polygons[random.nextInt(r.polygons.size)]
            val ring = poly.outer.coords
            val k = random.nextInt(ring.size / 2)
            val k2 = (k + 1) % (ring.size / 2)
            val mid = random.nextBoolean()
            val y = if (mid) (ring[2 * k] + ring[2 * k2]) / 2 else ring[2 * k]
            val x = if (mid) (ring[2 * k + 1] + ring[2 * k2 + 1]) / 2 else ring[2 * k + 1]
            check((y + random.nextInt(7) - 3) / 1e6, (x + random.nextInt(7) - 3) / 1e6)
        }
        for (drive in DemoDrives.all()) for (p in drive.points) check(p.lat, p.lng)
        println("[regions] grid = reference for $checked points")
    }

    @Test
    fun `drive region sequences match demo-region-sequence_json`() {
        val reference = Json.parseToJsonElement(DemoDrives.gzText("/regions/demo-region-sequence.json.gz")).jsonObject
        val drives = reference["drives"]!!.jsonArray
        val visited = LinkedHashSet<String>()
        val firstVisits = ArrayList<String>()
        val ordered = DemoDrives.all()
        assertEquals(drives.map { it.jsonObject["id"]!!.jsonPrimitive.content }, ordered.map { it.id })
        var entries = 0
        for ((d, drive) in drives.zip(ordered)) {
            val want = d.jsonObject
            assertEquals(want["title"]!!.jsonPrimitive.content, drive.title)
            assertEquals(drive.id, want["points"]!!.jsonPrimitive.int, drive.points.size)
            val tracker = RegionTracker(index, visited)
            for (p in drive.points) tracker.push(p.t.toLong(), p.lat, p.lng)
            assertEquals(drive.id, want["unclassifiedPoints"]!!.jsonPrimitive.int, tracker.unclassified)
            val wantEntries = want["regions"]!!.jsonArray.map { it.jsonObject }
            val got = tracker.entries
            assertEquals(drive.id + " " + got.map { it.region.label }, wantEntries.size, got.size)
            for ((w, g) in wantEntries.zip(got)) {
                assertEquals(drive.id, w["code"]!!.jsonPrimitive.content, g.region.code)
                assertEquals(drive.id, w["label"]!!.jsonPrimitive.content, g.region.label)
                assertEquals(drive.id, w["t"]!!.jsonPrimitive.long, g.atMs)
                assertEquals(drive.id, w["start"]!!.jsonPrimitive.boolean, g.start)
                assertEquals(drive.id, w["firstVisit"]!!.jsonPrimitive.boolean, g.firstVisit)
                // the entry fix
                val fix = drive.points.first { it.t.toLong() == g.atMs }
                assertEquals(w["lat"]!!.jsonPrimitive.double, fix.lat, 0.0)
                assertEquals(w["lng"]!!.jsonPrimitive.double, fix.lng, 0.0)
                if (g.firstVisit) firstVisits.add(g.region.code)
                entries += 1
            }
            assertEquals(tracker.newRegions.map { it.code }, got.filter { it.firstVisit }.map { it.region.code })
            assertEquals(tracker.passed.map { it.code }, got.map { it.region.code }.distinct())
            visited.addAll(tracker.visited)
            println("[regions] ${drive.id}: " + got.joinToString(" → ") { (if (it.firstVisit) "*" else "") + it.region.label })
        }
        assertEquals(reference["firstVisitsInOrder"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }, firstVisits)
        assertEquals(reference["totalDistinct"]!!.jsonPrimitive.int, visited.size)
        println("[regions] ${ordered.size} drives, $entries entries, ${visited.size} regions visited — identical to demo-region-sequence.json")
    }

    @Test
    fun `border flicker is not announced, sea fixes keep the region`() {
        val seocho = index.regionAt(37.4934, 127.0142)!! // 교대역
        val gangnam = index.regionAt(37.5006, 127.0366)!! // 역삼역
        assertEquals("서초구", seocho.name)
        assertEquals("강남구", gangnam.name)
        val tracker = RegionTracker(index, setOf(seocho.code))
        var t = 1_000_000L
        val start = tracker.push(t, 37.4934, 127.0142)!!
        assertTrue(start.start && !start.firstVisit)
        // 강남대로 flicker: alternate every 10 s for 5 minutes — never 30 s in 강남 in a row
        repeat(30) { i ->
            t += 10_000
            val e = if (i % 2 == 0) tracker.push(t, 37.5006, 127.0366) else tracker.push(t, 37.4934, 127.0142)
            assertNull(e)
        }
        assertEquals(seocho, tracker.current)
        // 강남 for 20 s, a fix at sea (unlocated — does not restart the wait), 강남 again: confirmed at 30 s, entry time = first 강남 fix
        t += 10_000
        val first = t
        assertNull(tracker.push(t, 37.5006, 127.0366))
        t += 20_000
        assertNull(tracker.push(t, 37.0, 125.0))
        t += 10_000
        val entered = tracker.push(t, 37.5006, 127.0366)!!
        assertEquals(gangnam, entered.region)
        assertEquals(first, entered.atMs)
        assertTrue(entered.firstVisit && !entered.start)
        assertEquals(1, tracker.unclassified)
        assertEquals(listOf(seocho, gangnam), tracker.passed)
        assertEquals(listOf(gangnam), tracker.newRegions)
        assertEquals(setOf(seocho.code, gangnam.code), tracker.visited)
    }

    @Test
    fun `nearest unvisited regions from 합정 after the demo drives`() {
        val visited = LinkedHashSet<String>()
        for (drive in DemoDrives.all()) {
            val tracker = RegionTracker(index, visited)
            for (p in drive.points) tracker.push(p.t.toLong(), p.lat, p.lng)
            visited.addAll(tracker.visited)
        }
        val near = index.nearestUnvisited(37.5495, 126.9139, visited)
        println("[regions] 합정 → " + near.joinToString { "%s %.1f km %s(%.0f°)".format(it.region.label, it.distanceKm, it.direction, it.bearingDeg) })
        assertEquals(5, near.size)
        assertTrue(near.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
        assertTrue(near.none { it.region.code in visited })
        // 은평구 / 영등포구 are right next to 마포구 and were never driven
        assertTrue(near.take(3).any { it.region.label == "서울 영등포구" || it.region.label == "서울 은평구" })
        assertTrue(index.nearestUnvisited(37.5495, 126.9139, visited, limit = 50, maxKm = 10.0).all { it.distanceKm <= 10.0 })
        // bearing sanity: 강릉 is east of 합정, 제주 south
        val east = initialBearingDeg(37.5495, 126.9139, 37.7519, 128.8761)
        assertTrue("east $east", east in 70.0..90.0)
        assertEquals("동", compassKo(east))
        assertEquals("남", compassKo(initialBearingDeg(37.5495, 126.9139, 33.5104, 126.4914)))
    }

    @Test
    fun `speed — load and lookups`() {
        val bytes = java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/regions/regions.json.gz")!!).readBytes()
        RegionIndex.load(bytes) // warm-up
        val t0 = System.nanoTime()
        val loaded = RegionIndex.load(bytes)
        val loadMs = (System.nanoTime() - t0) / 1e6
        val fixes = DemoDrives.all().flatMap { it.points }
        var sink = 0
        repeat(3) { for (p in fixes) sink += loaded.indexAt(p.lat, p.lng) } // warm-up
        val t1 = System.nanoTime()
        for (p in fixes) sink += loaded.indexAt(p.lat, p.lng)
        val driveUs = (System.nanoTime() - t1) / 1e3 / fixes.size
        // worst case: Seoul (every cell crossed by a border)
        val random = Random(7)
        val seoul = List(200_000) { (37.45 + random.nextDouble() * 0.2) to (126.8 + random.nextDouble() * 0.35) }
        for (p in seoul) sink += loaded.indexAt(p.first, p.second)
        val t2 = System.nanoTime()
        for (p in seoul) sink += loaded.indexAt(p.first, p.second)
        val seoulUs = (System.nanoTime() - t2) / 1e3 / seoul.size
        println("[regions] load %.0f ms (%d bytes); lookup %.2f µs/fix on the demo drives (%d fixes), %.2f µs in central Seoul (sink %d)".format(loadMs, bytes.size, driveUs, fixes.size, seoulUs, sink))
        assertTrue("drive lookup $driveUs µs", driveUs < 20)
        assertTrue("seoul lookup $seoulUs µs", seoulUs < 20)
    }
}
