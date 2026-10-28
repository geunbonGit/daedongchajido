package com.odomap.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.odomap.app.engine.DriveEngine
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.SourceEvent
import com.odomap.app.replay.DemoDriveFormat
import com.odomap.app.share.CardFonts
import com.odomap.app.share.CardLook
import com.odomap.app.share.CardOutline
import com.odomap.app.share.CardRatio
import com.odomap.app.share.QrArt
import com.odomap.app.share.ShareCardArt
import com.odomap.app.share.ShareData
import com.odomap.app.share.ShareInputs
import com.odomap.app.share.cardNumbers
import com.odomap.app.share.cardScene
import com.odomap.app.ui.replay.ReplayArt
import com.odomap.app.ui.replay.ReplayBoard
import com.odomap.app.ui.replay.ReplayColors
import com.odomap.app.ui.replay.ReplayFonts
import com.odomap.app.ui.replay.ReplayMode
import com.odomap.app.ui.replay.ReplayPaths
import com.odomap.app.ui.replay.ReplayScene
import com.odomap.core.explore.DriveReplay
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.geo.GeoPoint
import com.odomap.core.region.RegionIndex
import com.odomap.core.region.RegionTracker
import com.odomap.core.share.SHARE_TRIM_M
import com.odomap.core.share.ShareCard
import com.odomap.core.share.ShareCards
import com.odomap.core.share.ShareCodec
import com.odomap.core.share.ShareEncoder
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.EnumMap
import java.util.EnumSet

/**
 * Share + replay on the 14 demo drives (Robolectric native graphics): every card kind is built from stored-style data (path → DriveReplay),
 * fitted into a QR, the QR is RENDERED (QrArt — the screen's drawing) and DECODED with ZXing (an independent decoder) back to the same URL
 * and card; the start/end hiding keeps every line 300 m away; the card art and replay frames are drawn (PNG in -PodomapRenderDir).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ShareReplayTest {
    private val assets = File("src/main/assets")
    private val outDir: String? = System.getProperty("odomap.render.dir")

    private class Demo(val record: DriveRecord, val replay: DriveReplay, val endpoints: List<GeoPoint>)

    /** all demo drives in order on one graph, each with its replay right after it and its region entries */
    private fun drives(index: RegionIndex): Pair<DriveEngine, List<Demo>> {
        val engine = DriveEngine()
        val ids = JSONArray(File(assets, "drives/index.json").readText()).let { a -> List(a.length()) { a.getJSONObject(it).getString("id") } }
        val visited = LinkedHashSet<String>()
        val out = ArrayList<Demo>()
        val paths = ArrayList<Triple<String, List<Int>, List<Double>>>()
        for (id in ids) {
            val events = File(assets, "drives/$id.jsonl").reader().use { DemoDriveFormat.parseDrive(it, id) }
            var title: String? = null
            val tr = RegionTracker(index, visited)
            var first: GeoPoint? = null
            var last: GeoPoint? = null
            for (e in events) {
                when (val ev = e.event) {
                    is SourceEvent.DemoBegin -> title = ev.title
                    is SourceEvent.State ->
                        if (ev.status.driving && !engine.isDriving) engine.start("t-$id", ev.status.t, title, "replay:$id")
                        else if (!ev.status.driving && engine.isDriving) {
                            val done = engine.finish(ev.status.t)!!
                            val seq = ArrayList<String>()
                            for (en in tr.entries) if (seq.lastOrNull() != en.region.code) seq.add(en.region.code)
                            visited.addAll(tr.visited)
                            val record = done.record.copy(regions = seq, firstVisits = tr.newRegions.map { it.code })
                            paths.add(Triple(record.id, done.path, done.pathT))
                            out.add(Demo(record, engine.replayOf(done.path, done.pathT, done.newEdges), listOfNotNull(first, last)))
                        }
                    is SourceEvent.Loc -> if (engine.isDriving) {
                        engine.push(ev.fix)
                        tr.push(ev.fix.t, ev.fix.lat, ev.fix.lng)
                        if (first == null) first = GeoPoint(ev.fix.lat, ev.fix.lng)
                        last = GeoPoint(ev.fix.lat, ev.fix.lng)
                    }
                }
            }
        }
        // replays as the app builds them later (stored path on today's net — levels now)
        val now = out.mapIndexed { i, d -> Demo(d.record, engine.replayOf(paths[i].second, paths[i].third, d.replay.segments.filter { it.pioneered }.map { com.odomap.core.explore.EdgePair(it.from, it.to) }), d.endpoints) }
        return engine to now
    }

    private fun decodeQr(bmp: Bitmap): String {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.of(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px))), hints).text
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = outDir ?: return
        File(dir).mkdirs()
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun cardsQrRoundTripAndReplay() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val index = RegionIndex.load(File(assets, "regions/regions.json").readBytes())
        val (engine, demos) = drives(index)
        val records = demos.map { it.record }
        val visited = records.flatMap { it.regions }.toSet()
        val inputs = ShareInputs(records, index, visited, engine.netLengthM())
        val fonts = CardFonts.of(app)
        val outline = CardOutline.parse(File(assets, "share/outline.json").readText())
        assertEquals(230, outline.regions.size)

        val gangneung = demos.first { it.record.title == "강릉 여행" }
        val cards =
            listOf(
                "drive" to ShareData.encodeDrive(ShareData.driveCard(inputs, gangneung.record, gangneung.replay, hideEnds = true), listOfNotNull(gangneung.replay.startPoint, gangneung.replay.endPoint) + gangneung.endpoints, hide = true).card,
                "drive-open" to ShareData.driveCard(inputs, gangneung.record, gangneung.replay, hideEnds = false),
                "map" to ShareCards.atlas(engine.graph, gangneung.record.endedAt, records.sumOf { it.distanceM }, records.size, "", visited.size, demos.flatMap { it.endpoints }, hidePlaces = true),
                "regions" to ShareCards.regions(gangneung.record.endedAt, records.sumOf { it.distanceM }, engine.netLengthM(), records.size, "", ShareData.indices(index, visited)),
            )
        for ((name, card) in cards) {
            val qr = ShareEncoder.encode(card)
            // the QR as the screen draws it (4-module quiet zone) → ZXing → the same URL → the same card
            val bmp = QrArt.bitmap(qr.size, qr::isDark, modulePx = 5)
            val url = decodeQr(bmp)
            assertEquals("$name: decoded URL", qr.url, url)
            assertEquals("$name: decoded card", qr.card, ShareCodec.decodeUrl(url))
            println("[share] $name: v${qr.version} ${qr.ecc} ${qr.size}×${qr.size} ${qr.bytes} B, url ${qr.url.length} chars, tol ${"%.0f".format(qr.toleranceM)} m, numbers ${cardNumbers(qr.card).let { "${it.hero}${it.unit} ${it.label} · ${it.stats}" }}")
            save(bmp, "ui3-qr-$name.png")
            for (ratio in CardRatio.entries) for (look in CardLook.entries) {
                if (name == "drive-open" && (ratio != CardRatio.SQUARE || look != CardLook.PAPER)) continue
                save(ShareCardArt.bitmap(cardScene(qr.card, ratio, look, outline), fonts), "ui3-card-$name-${ratio.id}-${look.id}.png")
            }
        }

        // 출발·도착 가리기: no decoded line segment within 300 m (straight line) of the drive's ends (net ends + first / last raw fix)
        for (d in demos) {
            val ends = listOfNotNull(d.replay.startPoint, d.replay.endPoint) + d.endpoints
            val card = ShareData.driveCard(inputs, d.record, d.replay, hideEnds = true)
            val raw = ShareData.closestToEnds(ShareEncoder.encode(card).card, ends)
            val qr = ShareData.encodeDrive(card, ends, hide = true)
            val closest = ShareData.closestToEnds(qr.card, ends)
            println("[share] hide ${d.record.title}: core only ${raw.toInt()} m → ${closest.toInt()} m, ${(qr.card as ShareCard.Drive).lines.size} lines")
            assertTrue("${d.record.title}: a line ${closest.toInt()} m from an end", closest >= SHARE_TRIM_M)
            assertEquals(qr.url, decodeQr(QrArt.bitmap(qr.size, qr::isDark, modulePx = 4)))
        }

        // replay: whole map (date order, camera flights), one drive (prior levels), one region (area)
        val colors = ReplayColors(0xF7F7F5, 0x262626, 0x1C1C1E, 0x8A8A8E, false)
        val rf = ReplayFonts(fonts.medium, androidx.core.content.res.ResourcesCompat.getFont(app, R.font.pretendard_semibold)!!, fonts.bold)
        val replays = demos.associate { it.record.id to it.replay }
        val all = ReplayScene.build(ReplayMode.All, records, replays, emptyList(), index, null, ReplayBoard.innerW, ReplayBoard.innerH)
        assertEquals(records.size, all.steps.size)
        assertTrue("total ${all.timeline.totalMs}", all.timeline.totalMs in 3500.0..12_800.0)
        assertTrue("camera flies between places", all.camera.flights.isNotEmpty())
        assertTrue("re-passes thicken", all.steps.any { s -> s.draw.any { it >= 2 } })
        assertTrue("first-visit names", all.steps.sumOf { it.entries.size } >= 10)
        assertEquals(records.sumOf { it.distanceM }, all.totalDrivenM, 1.0)
        val gIdx = records.indexOf(gangneung.record)
        val one = ReplayScene.build(ReplayMode.Drive(gangneung.record.id), listOf(gangneung.record), replays, demos.take(gIdx).map { it.replay }, index, null, ReplayBoard.innerW, ReplayBoard.innerH)
        assertEquals(1, one.steps.size)
        assertEquals(3500.0 + 300 + 360, one.timeline.totalMs, 1.0)
        val gapyeong = index.regions.first { it.name == "가평군" }.code
        val shapes = com.odomap.app.ui.regions.RegionShapes.precise(app, gapyeong)
        val rings = shapes?.rings?.map { r -> DoubleArray(r.size) { k -> if (k % 2 == 0) com.odomap.app.ui.map.yToLat(r[k + 1]) else com.odomap.app.ui.map.xToLng(r[k - 1]) } }
        val region = ReplayScene.build(ReplayMode.Region(gapyeong), records.filter { gapyeong in it.regions }, replays, emptyList(), index, rings, ReplayBoard.innerW, ReplayBoard.innerH)
        assertTrue(region.steps.isNotEmpty())
        assertTrue(region.area != null)
        println("[replay] all: ${all.steps.size} steps, ${"%.1f".format(all.timeline.totalMs / 1000)} s, end ${"%.1f".format(all.endMs / 1000)} s, flights ${all.camera.flights.size}, entries ${all.steps.sumOf { it.entries.size }}; region ${region.steps.size} steps")
        for ((name, scene) in listOf("all" to all, "drive" to one, "region" to region)) {
            val paths = ReplayPaths(scene)
            for (frac in listOf(0.15, 0.4, 0.7, 1.0)) {
                val t = scene.endMs * frac
                val k = 2f
                val bmp = Bitmap.createBitmap((ReplayBoard.W * k).toInt(), (ReplayBoard.H * k).toInt(), Bitmap.Config.ARGB_8888)
                ReplayArt.draw(Canvas(bmp), scene, paths, t, k, colors, rf)
                save(bmp, "ui3-replay-$name-${(frac * 100).toInt()}.png")
            }
        }
    }

    /** The app path: DriveRecorder stores the drives (path.json), ShareData.build makes every card from the stored data. */
    @Test
    fun recorderBuildsCardsFromStoredDrives() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory("odomap-share").toFile()
        val r = com.odomap.app.record.DriveRecorder(app, root, object : com.odomap.app.tts.Speaker {
            override fun speak(text: String) {}
            override fun shutdown() {}
        }).also { it.replaySpeed.value = 1_000_000 }
        val prep = listOf("commute-1-am", "commute-1-pm", "weekend-gapyeong", "weekend-gapyeong-back")
        r.playDemo(prep)
        val until = System.currentTimeMillis() + 180_000
        while (r.drives.value.size < prep.size || r.replayState.value.playing) {
            check(System.currentTimeMillis() < until) { "replay timeout" }
            Thread.sleep(50)
        }
        val inputs = ShareInputs(r.drives.value, r.regions.value, r.visited.value, r.net.value.netLengthM)
        kotlinx.coroutines.runBlocking {
            for (d in r.drives.value) for (hide in listOf(true, false)) {
                val qr = ShareData.build(r, inputs, com.odomap.app.share.ShareKind.DRIVE, d.id, hide, d.endedAt)!!
                val card = qr.card as ShareCard.Drive
                assertTrue("${d.title}: replay from path.json", r.replays(listOf(d.id))[d.id]!!.segments.isNotEmpty())
                println("[share] stored ${d.title} hide=$hide: ${card.lines.size} lines, ${card.lines.sumOf { it.points.size }} points, v${qr.version}")
                assertTrue("${d.title} hide=$hide has lines", card.lines.isNotEmpty())
                assertEquals(qr.url, decodeQr(QrArt.bitmap(qr.size, qr::isDark, modulePx = 4)))
            }
            for (kind in listOf(com.odomap.app.share.ShareKind.MAP, com.odomap.app.share.ShareKind.REGIONS)) {
                val qr = ShareData.build(r, inputs, kind, null, true, System.currentTimeMillis())!!
                assertEquals(qr.url, decodeQr(QrArt.bitmap(qr.size, qr::isDark, modulePx = 4)))
            }
        }
        root.deleteRecursively()
    }
}
