package com.odomap.app.engine

import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.Fix
import com.odomap.core.car.CarProfile
import com.odomap.core.explore.EdgePair
import com.odomap.core.explore.ExploreEdge
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreRecorder
import com.odomap.core.explore.ExploreTileXY
import com.odomap.core.explore.exploreTileBounds
import com.odomap.core.explore.exploreTileOf
import com.odomap.core.explore.ExploreWalkSummary

/** Live numbers and lines of the drive in progress. */
data class LiveDrive(
    val id: String,
    val title: String?,
    val startedAt: Long,
    val distanceM: Double,
    val pioneeredM: Double,
    val fixCount: Int,
    /** this drive's known (already driven) parts, levels from the cumulative net */
    val inkGeoJson: String,
    /** this drive's first-time parts */
    val newGeoJson: String,
    /** time (ms epoch) of the last settled step — fixes after it are not in the lines yet (drawn as the live tail) */
    val settledT: Long = startedAt,
)

/** a z14 tile with my roads: centre and number of edges */
data class TileDot(val lat: Double, val lng: Double, val edges: Int)

fun tileDotsGeoJson(dots: List<TileDot>): String {
    if (dots.isEmpty()) return EMPTY_FEATURES
    val sb = StringBuilder(64 + dots.size * 96)
    sb.append("""{"type":"FeatureCollection","features":[""")
    dots.forEachIndexed { i, d ->
        if (i > 0) sb.append(',')
        sb.append("""{"type":"Feature","properties":{"w":""").append(d.edges).append("""},"geometry":{"type":"Point","coordinates":[""")
        sb.append(fmt6(d.lng)).append(',').append(fmt6(d.lat)).append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

/**
 * A finished drive: the stored summary plus its edges (for the detail map) and the path as driven (walkPath node tokens + their times —
 * [com.odomap.core.explore.DriveReplay] for the replay screen and the share card).
 */
class FinishedDrive(
    val record: DriveRecord,
    val walkEdges: List<EdgePair>,
    val newEdges: List<EdgePair>,
    val path: List<Int> = emptyList(),
    val pathT: List<Double> = emptyList(),
)

/**
 * The drive computation around one cumulative [ExploreGraph] (pure Kotlin — unit-tested on the JVM).
 * Not thread-safe: the caller confines it to one thread (the recorder's engine dispatcher).
 */
class DriveEngine(val graph: ExploreGraph = CarProfile.newGraph()) {
    private class Active(
        val id: String,
        val title: String?,
        val source: String,
        val startedAt: Long,
        val recorder: ExploreRecorder,
    ) {
        var fixCount = 0
        var lastT = startedAt
        var bounds: Bounds? = null
    }

    private var active: Active? = null

    val isDriving: Boolean get() = active != null
    val activeId: String? get() = active?.id

    fun start(id: String, startedAt: Long, title: String?, source: String) {
        check(active == null) { "drive already active" }
        active = Active(id, title, source, startedAt, ExploreRecorder(graph, startedAt.toDouble(), config = CarProfile.pipeline))
    }

    fun push(fix: Fix) {
        val a = active ?: return
        if (fix.t < a.startedAt) return
        a.recorder.push(fix.toRawPoint())
        a.fixCount += 1
        a.lastT = maxOf(a.lastT, fix.t)
        a.bounds = (a.bounds ?: Bounds.of(fix.lat, fix.lng)).extend(fix.lat, fix.lng)
    }

    /** Settled-so-far numbers; [withLines] also builds this drive's GeoJSON (costlier — call ~1/s). */
    fun live(withLines: Boolean): LiveDrive? {
        val a = active ?: return null
        val s = a.recorder.summary()
        val settled = a.recorder.settled().first
        return LiveDrive(
            a.id, a.title, a.startedAt, s.onFootM, s.pioneeredM, a.fixCount,
            if (withLines) edgesToGeoJson(knownEdges(s)) else "",
            if (withLines) edgesToGeoJson(edgesOf(s.newEdges)) else "",
            if (settled.isFinite()) settled.toLong() else a.startedAt,
        )
    }

    /** Ends the drive (settles everything). Returns null when nothing was driven (no fix / zero length). */
    fun finish(endedAt: Long): FinishedDrive? {
        val a = active ?: return null
        active = null
        val s = a.recorder.finish()
        if (a.fixCount == 0 && s.walkEdges.isEmpty()) return null
        val record =
            DriveRecord(
                id = a.id,
                title = a.title,
                startedAt = a.startedAt,
                endedAt = maxOf(endedAt, a.lastT),
                distanceM = s.onFootM,
                pioneeredM = s.pioneeredM,
                edgeCount = s.walkEdges.size,
                newEdgeCount = s.newEdges.size,
                repassEdges = s.walkPasses.size,
                maxPasses = s.walkPasses.maxOfOrNull { it.passes } ?: if (s.walkEdges.isEmpty()) 0 else 1,
                fixCount = a.fixCount,
                source = a.source,
                bounds = boundsOfEdges(edgesOf(s.walkEdges)) ?: a.bounds,
            )
        return FinishedDrive(record, s.walkEdges, s.newEdges, s.walkPath, s.walkPathT)
    }

    /** Drops the active drive without settling (data reset). The graph keeps what was already added. */
    fun abandon() {
        active = null
    }

    fun netGeoJson(): String = edgesToGeoJson(graph.edges())

    /** a stored drive as driven, on today's net (levels now) — node ids of [path] must belong to this graph */
    fun replayOf(path: List<Int>, pathT: List<Double>, newEdges: List<EdgePair>): com.odomap.core.explore.DriveReplay =
        com.odomap.core.explore.DriveReplay.of(graph, path, pathT, newEdges)

    /** this drive's lines (all its edges, today's levels) — the drive screen draws it in ink over the dimmed net */
    fun driveLinesGeoJson(walk: List<EdgePair>): String = edgesToGeoJson(edgesOf(walk))

    /** z14 tiles holding my roads: centre + edge count (far-zoom dots and the off-screen guide) */
    fun netTiles(): List<TileDot> {
        val counts = HashMap<Long, Int>()
        for (e in graph.edges()) {
            val t = exploreTileOf((e.aLat + e.bLat) / 2, (e.aLng + e.bLng) / 2)
            val key = t.x.toLong() shl 32 or t.y.toLong()
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.map { (key, n) ->
            val b = exploreTileBounds(ExploreTileXY((key shr 32).toInt(), (key and 0xFFFFFFFFL).toInt()))
            TileDot((b.minLat + b.maxLat) / 2, (b.minLng + b.maxLng) / 2, n)
        }
    }

    /** length of roads driven three times or more (level 3+) */
    fun frequentLengthM(): Double = graph.edges().filter { it.visits >= 3 }.sumOf { edgeLengthM(it) }

    /** my roads' length per 시·군·구 (by edge midpoint) */
    fun lengthByRegion(index: com.odomap.core.region.RegionIndex): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (e in graph.edges()) {
            val r = index.regionAt((e.aLat + e.bLat) / 2, (e.aLng + e.bLng) / 2) ?: continue
            out[r.code] = (out[r.code] ?: 0.0) + edgeLengthM(e)
        }
        return out
    }

    fun netLengthM(): Double = graph.edges().sumOf { edgeLengthM(it) }

    fun netBounds(): Bounds? = boundsOfEdges(graph.edges())

    fun edgesOf(pairs: List<EdgePair>): List<ExploreEdge> = pairs.mapNotNull { graph.edge(it.a, it.b) }

    private fun knownEdges(s: ExploreWalkSummary): List<ExploreEdge> {
        val fresh = HashSet<Long>(s.newEdges.size * 2)
        for (p in s.newEdges) fresh.add(p.a.toLong() shl 32 or p.b.toLong())
        return s.walkEdges.filter { !fresh.contains(it.a.toLong() shl 32 or it.b.toLong()) }.mapNotNull { graph.edge(it.a, it.b) }
    }

    /** GeoJSON (ink, new) of a stored drive's edges with today's levels. */
    fun driveGeoJson(walk: List<EdgePair>, fresh: List<EdgePair>): Pair<String, String> {
        val freshKeys = HashSet<Long>(fresh.size * 2)
        for (p in fresh) freshKeys.add(p.a.toLong() shl 32 or p.b.toLong())
        val ink = walk.filter { !freshKeys.contains(it.a.toLong() shl 32 or it.b.toLong()) }
        return edgesToGeoJson(edgesOf(ink)) to edgesToGeoJson(edgesOf(fresh))
    }
}
