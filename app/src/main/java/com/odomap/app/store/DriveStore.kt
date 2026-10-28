package com.odomap.app.store

import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.Fix
import com.odomap.core.explore.EdgePair
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * Drives on disk, one folder each: `drives/<id>/summary.json`, `fixes.jsonl` (raw fixes, appended while driving — enough to recompute the
 * drive later with a new rule set), `edges.json` (edges walked / created, for the detail map).
 * On-device only. TODO(privacy): encrypt at rest (see GraphStore) before release.
 */
class DriveStore(root: File) {
    private val dir = File(root, "drives")

    fun list(): List<DriveRecord> {
        val folders = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return folders.mapNotNull { f -> runCatching { recordOf(JSONObject(File(f, "summary.json").readText())) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    /** Opens the raw-fix log of a drive in progress. */
    fun openFixLog(id: String): FixLog = FixLog(File(File(dir, id).apply { mkdirs() }, "fixes.jsonl"))

    fun save(record: DriveRecord, walkEdges: List<EdgePair>, newEdges: List<EdgePair>, path: List<Int>? = null, pathT: List<Double>? = null) {
        val folder = File(dir, record.id).apply { mkdirs() }
        writeAtomic(File(folder, "edges.json"), JSONObject().put("walk", pairs(walkEdges)).put("new", pairs(newEdges)).toString())
        if (path != null) savePath(record.id, path, pathT ?: emptyList())
        writeAtomic(File(folder, "summary.json"), jsonOf(record).toString())
    }

    /**
     * The drive as driven (`path.json`): walkPath tokens (node ids of the cumulative graph, 0 = pen up + start node, negative = uncounted
     * step) and the time of each token (ms epoch, rounded). Node ids are only valid for the graph the drive was recorded (or rebuilt) on.
     */
    fun savePath(id: String, path: List<Int>, pathT: List<Double>) {
        val folder = File(dir, id).apply { mkdirs() }
        val p = JSONArray()
        for (v in path) p.put(v)
        val t = JSONArray()
        for (v in pathT) t.put(if (v.isFinite()) Math.round(v) else 0L)
        writeAtomic(File(folder, "path.json"), JSONObject().put("path", p).put("t", t).toString())
    }

    /** a path.json with at least one token (an empty one — nothing driven on the net — counts as missing and is rebuilt) */
    fun hasPath(id: String): Boolean = File(File(dir, id), "path.json").let { it.exists() && it.length() > 24 }

    /** (walkPath, walkPathT) of a stored drive, null without path.json */
    fun path(id: String): Pair<List<Int>, List<Double>>? {
        val file = File(File(dir, id), "path.json")
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val p = o.getJSONArray("path")
            val t = o.optJSONArray("t") ?: JSONArray()
            List(p.length()) { p.getInt(it) } to List(t.length()) { t.getLong(it).toDouble() }
        }.getOrNull()
    }

    fun edges(id: String): Pair<List<EdgePair>, List<EdgePair>>? {
        val file = File(File(dir, id), "edges.json")
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        return unpairs(o.getJSONArray("walk")) to unpairs(o.getJSONArray("new"))
    }

    /**
     * The drive's path for thumbnails / flags: raw fixes (fixes.jsonl) simplified to ~[toleranceM], cached as route.json.
     * Each point is [lat, lng]. Empty when the drive has no fix log.
     */
    fun route(id: String, toleranceM: Double = 12.0): List<Pair<Double, Double>> {
        val folder = File(dir, id)
        val cache = File(folder, "route.json")
        if (cache.exists()) {
            runCatching {
                val a = JSONArray(cache.readText())
                return List(a.length() / 2) { a.getDouble(it * 2) to a.getDouble(it * 2 + 1) }
            }
        }
        val log = File(folder, "fixes.jsonl")
        if (!log.exists()) return emptyList()
        val pts = ArrayList<Pair<Double, Double>>()
        log.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val o = JSONObject(line)
                pts.add(o.getDouble("lat") to o.getDouble("lng"))
            }
        }
        val simple = simplifyPath(pts, toleranceM)
        if (File(folder, "summary.json").exists()) {
            val a = JSONArray()
            for ((lat, lng) in simple) a.put(Math.round(lat * 1e6) / 1e6).put(Math.round(lng * 1e6) / 1e6)
            runCatching { writeAtomic(cache, a.toString()) }
        }
        return simple
    }

    /** every raw fix of a drive (fixes.jsonl), in file order */
    fun fixes(id: String): List<Fix> {
        val log = File(File(dir, id), "fixes.jsonl")
        if (!log.exists()) return emptyList()
        val out = ArrayList<Fix>()
        log.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val o = JSONObject(line)
                out.add(
                    Fix(
                        o.getLong("t"), o.getDouble("lat"), o.getDouble("lng"),
                        if (o.has("acc")) o.getDouble("acc").toFloat() else null,
                        if (o.has("spd")) o.getDouble("spd").toFloat() else null,
                        if (o.has("brg")) o.getDouble("brg").toFloat() else null,
                    ),
                )
            }
        }
        return out
    }

    /** Removes a folder left by a drive that never finished (no summary). */
    fun discard(id: String) {
        val folder = File(dir, id)
        if (!File(folder, "summary.json").exists()) folder.deleteRecursively()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    fun delete(id: String) {
        File(dir, id).deleteRecursively()
    }

    class FixLog(file: File) {
        private val out: BufferedWriter = file.bufferedWriter()

        fun append(fix: Fix) {
            out.write(
                buildString {
                    append("{\"t\":").append(fix.t).append(",\"lat\":").append(fix.lat).append(",\"lng\":").append(fix.lng)
                    fix.acc?.let { append(",\"acc\":").append(it) }
                    fix.speed?.let { append(",\"spd\":").append(it) }
                    fix.bearing?.let { append(",\"brg\":").append(it) }
                    append("}\n")
                },
            )
        }

        fun close() {
            runCatching { out.close() }
        }
    }

    companion object {
        /** Douglas–Peucker on lat/lng with a local metric (metres) */
        fun simplifyPath(points: List<Pair<Double, Double>>, toleranceM: Double): List<Pair<Double, Double>> {
            if (points.size <= 2) return points
            val lat0 = points.sumOf { it.first } / points.size
            val kx = Math.cos(Math.toRadians(lat0)) * 111_320.0
            val ky = 110_574.0
            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to points.size - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                val ax = points[a].second * kx
                val ay = points[a].first * ky
                val dx = points[b].second * kx - ax
                val dy = points[b].first * ky - ay
                val len2 = dx * dx + dy * dy
                var best = -1.0
                var at = -1
                for (i in a + 1 until b) {
                    val px = points[i].second * kx - ax
                    val py = points[i].first * ky - ay
                    val t = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    val d = Math.hypot(px - t * dx, py - t * dy)
                    if (d > best) {
                        best = d
                        at = i
                    }
                }
                if (best > toleranceM && at > 0) {
                    keep[at] = true
                    stack.addLast(a to at)
                    stack.addLast(at to b)
                }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }

        private fun pairs(list: List<EdgePair>): JSONArray {
            val a = JSONArray()
            for (p in list) a.put(p.a).put(p.b)
            return a
        }

        private fun unpairs(a: JSONArray): List<EdgePair> = List(a.length() / 2) { EdgePair(a.getInt(it * 2), a.getInt(it * 2 + 1)) }

        fun jsonOf(r: DriveRecord): JSONObject =
            JSONObject()
                .put("id", r.id)
                .put("title", r.title ?: JSONObject.NULL)
                .put("startedAt", r.startedAt)
                .put("endedAt", r.endedAt)
                .put("distanceM", r.distanceM)
                .put("pioneeredM", r.pioneeredM)
                .put("edgeCount", r.edgeCount)
                .put("newEdgeCount", r.newEdgeCount)
                .put("repassEdges", r.repassEdges)
                .put("maxPasses", r.maxPasses)
                .put("fixCount", r.fixCount)
                .put("source", r.source)
                .put("bounds", r.bounds?.let { JSONArray().put(it.minLat).put(it.minLng).put(it.maxLat).put(it.maxLng) } ?: JSONObject.NULL)
                .put("regions", JSONArray(r.regions))
                .put("firstVisits", JSONArray(r.firstVisits))

        fun recordOf(o: JSONObject): DriveRecord =
            DriveRecord(
                id = o.getString("id"),
                title = if (o.isNull("title")) null else o.getString("title"),
                startedAt = o.getLong("startedAt"),
                endedAt = o.getLong("endedAt"),
                distanceM = o.getDouble("distanceM"),
                pioneeredM = o.getDouble("pioneeredM"),
                edgeCount = o.optInt("edgeCount"),
                newEdgeCount = o.optInt("newEdgeCount"),
                repassEdges = o.optInt("repassEdges"),
                maxPasses = o.optInt("maxPasses"),
                fixCount = o.optInt("fixCount"),
                source = o.optString("source"),
                bounds = o.optJSONArray("bounds")?.let { Bounds(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) },
                regions = o.optJSONArray("regions")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                firstVisits = o.optJSONArray("firstVisits")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            )
    }
}
