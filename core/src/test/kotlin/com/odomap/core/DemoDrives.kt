package com.odomap.core

import com.odomap.core.region.RegionIndex
import com.odomap.core.run.RawPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream

// The 14 demo drives exactly as tools/routes/out/*.jsonl (= app/src/main/assets/drives) and the region asset, for the region / share tests.
// Resources: regions/regions.json.gz and regions/demo-region-sequence.json.gz are gzipped copies of tools/regions/out/*; the five
// commute-N-pm drives are copied to regions/drives/ because drives/ holds an older pm route (kept for the car tests); the other nine drives
// in drives/ are byte-identical to tools/routes/out. Regenerate with `gzip -9 -n -c <file> > <copy>.gz` when those tools change.

class DemoDrive(
    val id: String,
    val title: String,
    /** meta.start (ISO, +09:00) */
    val start: String,
    /** gear D (ms) */
    val startedAt: Long,
    /** gear P (ms) */
    val endedAt: Long,
    val points: List<RawPoint>,
)

object DemoDrives {
    val ids: List<String> =
        listOf(
            "commute-1-am", "commute-1-pm", "commute-2-am", "commute-2-pm", "commute-3-am", "commute-3-pm", "commute-4-am", "commute-4-pm",
            "commute-5-am", "commute-5-pm", "weekend-gapyeong", "weekend-gapyeong-back", "trip-gangneung", "trip-coast",
        )

    private val cache = HashMap<String, DemoDrive>()

    fun gzText(path: String): String {
        val stream = DemoDrives::class.java.getResourceAsStream(path) ?: error("missing resource $path")
        return GZIPInputStream(stream).bufferedReader().use { it.readText() }
    }

    fun exists(path: String): Boolean = DemoDrives::class.java.getResource(path) != null

    fun load(id: String): DemoDrive =
        synchronized(cache) {
            cache.getOrPut(id) {
                val path = if (exists("/regions/drives/$id.jsonl.gz")) "/regions/drives/$id.jsonl.gz" else "/drives/$id.jsonl.gz"
                var title = ""
                var start = ""
                var startedAt: Long? = null
                var endedAt: Long? = null
                val points = ArrayList<RawPoint>()
                for (line in gzText(path).lineSequence()) {
                    if (line.isBlank()) continue
                    val o: JsonObject = Json.parseToJsonElement(line).jsonObject
                    fun num(key: String) = o[key]!!.jsonPrimitive.double
                    when (o["k"]!!.jsonPrimitive.content) {
                        "meta" -> {
                            title = o["title"]!!.jsonPrimitive.content
                            start = o["start"]!!.jsonPrimitive.content
                        }
                        "gear" -> {
                            val g = o["g"]!!.jsonPrimitive.content
                            val t = o["t"]!!.jsonPrimitive.long
                            if (g == "D" && startedAt == null) startedAt = t
                            if (g == "P") endedAt = t
                        }
                        "loc" -> points.add(RawPoint(lat = num("lat"), lng = num("lng"), t = num("t"), acc = num("acc"), speed = num("spd"), course = num("brg")))
                    }
                }
                DemoDrive(id, title, start, startedAt ?: points.first().t.toLong(), endedAt ?: points.last().t.toLong(), points)
            }
        }

    /** all 14 in start order (the order demo-region-sequence.json accumulates first visits in) */
    fun all(): List<DemoDrive> = ids.map { load(it) }.sortedBy { OffsetDateTime.parse(it.start).toInstant() }
}

object TestRegions {
    val index: RegionIndex by lazy {
        val stream = TestRegions::class.java.getResourceAsStream("/regions/regions.json.gz") ?: error("missing regions.json.gz")
        GZIPInputStream(stream).use { RegionIndex.load(it) }
    }
}
