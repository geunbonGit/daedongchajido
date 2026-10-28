package com.odomap.core.explore

import com.odomap.core.geo.GeoPoint
import kotlin.math.abs

// Odomap (not in the TS original): the drive as it was driven, for the arrival replay animation and the share card.
// walkPath (+ walkPathT) becomes ordered moves over net edges: each segment is one edge in the direction driven, with its style
// (first-time road · thickness level now), whether the step counted a pass, whether a new stroke starts there, the distance driven so far
// and the time. Re-passes stay in (an out-and-back draws the road twice); levels are read from the net when the replay is built.

/** One edge driven, in the direction driven. */
data class ReplaySegment(
    /** node ids, from → to as driven */
    val from: Int,
    val to: Int,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    /** the edge was created by this drive (first-time road) */
    val pioneered: Boolean,
    /** thickness level of the edge (1..5, this drive included) */
    val level: Int,
    /** the step counted a pass (false = a short re-pass that did not count, e.g. stop-and-go jitter) */
    val counted: Boolean,
    /** a new stroke starts here (first segment, or the path broke before it: unbridged gap, filter restart) */
    val lift: Boolean,
    /** distance along the drive at the start / end (m, edge lengths, re-passes included) */
    val startM: Double,
    val endM: Double,
    /** time at the start / end (ms) — several edges reached by one step share its time span by distance; NaN without walkPathT */
    val startT: Double,
    val endT: Double,
) {
    val key: Long get() = exploreEdgeKey(from, to)
}

/** Consecutive segments of one stroke with the same style, as one polyline (drawing). */
data class ReplayPiece(
    val pioneered: Boolean,
    val level: Int,
    val coords: List<GeoPoint>,
    /** distance along the drive at each coordinate (m) */
    val alongM: List<Double>,
    val startT: Double,
    val endT: Double,
)

class DriveReplay(val segments: List<ReplaySegment>) {
    /** distance along the edges driven (m) — close to the summary's drivenM (resampled path length) */
    val lengthM: Double get() = segments.lastOrNull()?.endM ?: 0.0
    val startT: Double get() = segments.firstOrNull()?.startT ?: Double.NaN
    val endT: Double get() = segments.lastOrNull()?.endT ?: Double.NaN

    /** first / last point driven on the net (null when empty) */
    val startPoint: GeoPoint? get() = segments.firstOrNull()?.let { GeoPoint(it.fromLat, it.fromLng) }
    val endPoint: GeoPoint? get() = segments.lastOrNull()?.let { GeoPoint(it.toLat, it.toLng) }

    /** index of the segment being driven at distance m (clamped; -1 when empty) */
    fun indexAtM(m: Double): Int = search { segments[it].endM < m }

    /** index of the segment being driven at time t (clamped; -1 when empty or without times) */
    fun indexAtT(t: Double): Int = if (startT.isNaN()) -1 else search { segments[it].endT < t }

    /** Consecutive segments with the same style and no new stroke, joined into polylines (in order). */
    fun pieces(): List<ReplayPiece> {
        val out = ArrayList<ReplayPiece>()
        var coords = ArrayList<GeoPoint>()
        var along = ArrayList<Double>()
        var first: ReplaySegment? = null
        var last: ReplaySegment? = null
        fun flush() {
            val f = first ?: return
            out.add(ReplayPiece(f.pioneered, f.level, coords, along, f.startT, last!!.endT))
            coords = ArrayList()
            along = ArrayList()
            first = null
        }
        for (s in segments) {
            val prev = last
            if (prev == null || s.lift || s.from != prev.to || s.pioneered != prev.pioneered || s.level != prev.level) {
                flush()
                first = s
                coords.add(GeoPoint(s.fromLat, s.fromLng))
                along.add(s.startM)
            }
            coords.add(GeoPoint(s.toLat, s.toLng))
            along.add(s.endM)
            last = s
        }
        flush()
        return out
    }

    /** first index where before(index) is false, clamped to the last segment */
    private inline fun search(before: (Int) -> Boolean): Int {
        if (segments.isEmpty()) return -1
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (before(mid)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** The finished (or live) drive of [summary] on [graph] (call before another drive changes the levels). */
        fun of(graph: ExploreGraph, summary: ExploreWalkSummary): DriveReplay = of(graph, summary.walkPath, summary.walkPathT, summary.newEdges)

        fun of(graph: ExploreGraph, path: List<Int>, pathT: List<Double>, newEdges: Collection<EdgePair>): DriveReplay {
            val fresh = HashSet<Long>(newEdges.size * 2)
            for (e in newEdges) fresh.add(exploreEdgeKey(e.a, e.b))
            val timed = pathT.size == path.size
            val out = ArrayList<ReplaySegment>()
            var at: Int? = null
            var atT = Double.NaN
            var lifted = true
            var along = 0.0
            var k = 0
            while (k < path.size) {
                val token = path[k]
                if (token == 0) {
                    val start = if (k + 1 < path.size) path[k + 1] else null
                    if (start == null || start != at) lifted = true
                    at = start
                    if (timed && k + 1 < path.size) atT = pathT[k + 1]
                    k += 2
                    continue
                }
                val to = abs(token)
                val from = at
                at = to
                val endT = if (timed) pathT[k] else Double.NaN
                val edge = if (from == null) null else graph.edge(from, to)
                if (from == null || edge == null) {
                    lifted = true
                    atT = endT
                    k += 1
                    continue
                }
                val forward = edge.a == from
                val fLat = if (forward) edge.aLat else edge.bLat
                val fLng = if (forward) edge.aLng else edge.bLng
                val tLat = if (forward) edge.bLat else edge.aLat
                val tLng = if (forward) edge.bLng else edge.aLng
                val length = exploreDistanceM(fLat, fLng, tLat, tLng)
                out.add(
                    ReplaySegment(
                        from, to, fLat, fLng, tLat, tLng, fresh.contains(exploreEdgeKey(from, to)), exploreLineLevel(edge.visits), token > 0, lifted,
                        along, along + length, atT, endT,
                    ),
                )
                along += length
                lifted = false
                atT = endT
                k += 1
            }
            return DriveReplay(if (timed) spreadTimes(out) else out)
        }

        /** edges reached by one step share one time: spread that step's span over them by distance */
        private fun spreadTimes(segments: List<ReplaySegment>): List<ReplaySegment> {
            val out = ArrayList<ReplaySegment>(segments.size)
            var i = 0
            while (i < segments.size) {
                var j = i
                while (j + 1 < segments.size && segments[j + 1].endT == segments[i].endT) j += 1
                val s0 = segments[i].startT
                val s1 = segments[i].endT
                val total = segments[j].endM - segments[i].startM
                for (m in i..j) {
                    val s = segments[m]
                    if (i == j || !(total > 0) || s0.isNaN()) {
                        out.add(s)
                        continue
                    }
                    val a = s0 + (s1 - s0) * ((s.startM - segments[i].startM) / total)
                    val b = if (m == j) s1 else s0 + (s1 - s0) * ((s.endM - segments[i].startM) / total)
                    out.add(s.copy(startT = a, endT = b))
                }
                i = j + 1
            }
            return out
        }
    }
}
