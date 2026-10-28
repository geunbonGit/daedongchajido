package com.odomap.core.share

import com.odomap.core.explore.DriveReplay
import com.odomap.core.explore.EXPLORE_METERS_PER_DEGREE
import com.odomap.core.explore.EXPLORE_RAD
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreLinesOptions
import com.odomap.core.explore.ExploreWalkSummary
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.explore.exploreLines
import com.odomap.core.explore.smoothExploreCoords
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.LatLng
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Share cards from engine data, with the privacy rules of docs/share-format.md:
//  - kind 1: the drive's own lines in the order driven (DriveReplay), each edge once, split into first-time / known parts (known: level of
//    the road now, this drive included); every edge driven within the first or last 300 m along the path is left out (flags bit0);
//  - kind 2: the whole net (exploreLines smooth) minus everything within 500 m of the two most frequent places — drive start/end points
//    grouped within 300 m, the two biggest groups (flags bit1).

/** cut from each end of a drive, along the path (m) */
const val SHARE_TRIM_M = 300.0

/** drive endpoints within this distance of a group's first point form one frequent place (m) */
const val SHARE_PLACE_CLUSTER_M = 300.0

/** lines are left out this close to a frequent place (m) */
const val SHARE_PLACE_HIDE_M = 500.0

object ShareCards {
    /**
     * A drive card (kind 1). Call right after the drive (levels are read from [graph]). Regions: numbers from [com.odomap.core.region]
     * (RegionTracker.newRegions / passed → Region.index), [visitedTotal] = visited regions after this drive.
     */
    fun drive(
        graph: ExploreGraph,
        summary: ExploreWalkSummary,
        startedAtMs: Long,
        endedAtMs: Long,
        title: String,
        newRegions: List<Int> = emptyList(),
        passedRegions: List<Int> = emptyList(),
        visitedTotal: Int = 0,
        hideEnds: Boolean = true,
        hideRadiusM: Double = 0.0,
    ): ShareCard.Drive = drive(DriveReplay.of(graph, summary), summary, startedAtMs, endedAtMs, title, newRegions, passedRegions, visitedTotal, hideEnds, hideRadiusM)

    /**
     * [drive] from a replay built right after the drive (DriveReplay.of — levels as they were then; reusable for the arrival animation).
     * [hideRadiusM]: optional extra straight-line hiding around the start / end (see [driveShareLines]; 0 = the spec's along-path rule only).
     */
    fun drive(
        replay: DriveReplay,
        summary: ExploreWalkSummary,
        startedAtMs: Long,
        endedAtMs: Long,
        title: String,
        newRegions: List<Int> = emptyList(),
        passedRegions: List<Int> = emptyList(),
        visitedTotal: Int = 0,
        hideEnds: Boolean = true,
        hideRadiusM: Double = 0.0,
    ): ShareCard.Drive =
        ShareCard.Drive(
            dateDays = shareDateDays(startedAtMs),
            startMin = shareStartMin(startedAtMs),
            durationS = max(0L, (endedAtMs - startedAtMs) / 1000).toInt(),
            distance10 = tens(summary.drivenM),
            pioneered10 = tens(summary.pioneeredM),
            title = title,
            visitedTotal = visitedTotal,
            newRegions = newRegions,
            passedRegions = passedRegions,
            lines = driveShareLines(replay, if (hideEnds) SHARE_TRIM_M else 0.0, hideRadiusM = if (hideEnds) hideRadiusM else 0.0),
            flags = if (hideEnds) SHARE_FLAG_HIDE_ENDS else 0,
        )

    /**
     * My-map card (kind 2) of the whole net. [drivenM] / [driveCount]: totals over all drives; [endpoints]: start and end point of every
     * drive (frequent places are computed from them; empty = nothing hidden).
     */
    fun atlas(
        graph: ExploreGraph,
        createdAtMs: Long,
        drivenM: Double,
        driveCount: Int,
        title: String,
        visitedTotal: Int,
        endpoints: List<LatLng>,
        hidePlaces: Boolean = true,
    ): ShareCard.Atlas {
        val places = if (hidePlaces) frequentPlaces(endpoints) else emptyList()
        val lines = atlasShareLines(graph)
        return ShareCard.Atlas(
            dateDays = shareDateDays(createdAtMs),
            distance10 = tens(drivenM),
            pioneered10 = tens(netLengthM(graph)),
            driveCount = driveCount,
            title = title,
            visitedTotal = visitedTotal,
            lines = if (places.isEmpty()) lines else hideNearPlaces(lines, places),
            flags = if (places.isEmpty()) 0 else SHARE_FLAG_HIDE_PLACES,
        )
    }

    /** Conquest card (kind 3): visited region numbers; [netM] = length of the map (first-time road over all drives). */
    fun regions(createdAtMs: Long, drivenM: Double, netM: Double, driveCount: Int, title: String, visited: Collection<Int>): ShareCard.Regions =
        ShareCard.Regions(
            dateDays = shareDateDays(createdAtMs),
            distance10 = tens(drivenM),
            pioneered10 = tens(netM),
            driveCount = driveCount,
            title = title,
            visitedTotal = visited.toSet().size,
            visited = visited.toSortedSet().toList(),
        )

    private fun tens(m: Double): Int = if (m.isFinite() && m > 0) Math.round(m / 10).toInt() else 0
}

/** total edge length of the net (m) — "length of my map" */
fun netLengthM(graph: ExploreGraph): Double = graph.edges().sumOf { exploreDistanceM(it.aLat, it.aLng, it.bLat, it.bLng) }

/**
 * The drive's lines in the order driven: consecutive edges with the same style (first-time road · level) joined, each edge drawn once
 * (where it was first driven), every edge with a pass inside the first or last [trimM] along the path left out, [smooth] as the map draws.
 * [hideRadiusM] > 0 also leaves out edges with an end that close (straight line) to the first / last point driven — the spec rule is along
 * the path only, which in winding streets can leave the line passing near home (demo commute-3-am: 52 m after 300 m of path).
 */
fun driveShareLines(replay: DriveReplay, trimM: Double = SHARE_TRIM_M, smooth: Boolean = true, hideRadiusM: Double = 0.0): List<ShareLine> {
    val segments = replay.segments
    val total = replay.lengthM
    val hidden = HashSet<Long>()
    if (trimM > 0) for (s in segments) if (s.startM < trimM || s.endM > total - trimM) hidden.add(s.key)
    val ends = listOfNotNull(replay.startPoint, replay.endPoint)
    if (hideRadiusM > 0) {
        for (s in segments) {
            if (ends.any { exploreDistanceM(s.fromLat, s.fromLng, it.lat, it.lng) < hideRadiusM || exploreDistanceM(s.toLat, s.toLng, it.lat, it.lng) < hideRadiusM }) hidden.add(s.key)
        }
    }
    val drawn = HashSet<Long>()
    val out = ArrayList<ShareLine>()
    var points: ArrayList<GeoPoint>? = null
    var level = 0
    var pioneered = false
    var lastTo = 0
    fun flush() {
        val p = points ?: return
        if (p.size >= 2) out.add(ShareLine(level, pioneered, if (smooth) smoothExploreCoords(p) else p))
        points = null
    }
    for (s in segments) {
        if (s.key in hidden || !drawn.add(s.key)) {
            flush()
            continue
        }
        var p = points
        if (p == null || s.lift || s.from != lastTo || s.level != level || s.pioneered != pioneered) {
            flush()
            p = arrayListOf(GeoPoint(s.fromLat, s.fromLng))
            points = p
            level = s.level
            pioneered = s.pioneered
        }
        p.add(GeoPoint(s.toLat, s.toLng))
        lastTo = s.to
    }
    flush()
    return out
}

/** The whole net as card lines: exploreLines(smooth = true) — junction to junction, one thickness level each. */
fun atlasShareLines(graph: ExploreGraph): List<ShareLine> =
    exploreLines(graph.edges(), ExploreLinesOptions(smooth = true)).map { ShareLine(it.level, false, it.coords) }

/**
 * Frequent places (home, work…) from drive start/end points: repeatedly the point with the most others within [clusterM] seeds a group of
 * those points (ties → earlier point), the group's mean is a place, its points are removed; the [count] biggest groups. Pure.
 */
fun frequentPlaces(endpoints: List<LatLng>, clusterM: Double = SHARE_PLACE_CLUSTER_M, count: Int = 2): List<GeoPoint> {
    val points = endpoints.filter { it.lat.isFinite() && it.lng.isFinite() }
    val alive = BooleanArray(points.size) { true }
    val out = ArrayList<GeoPoint>()
    // grid of clusterM cells for the neighbour search (one longitude scale for all: two points within clusterM are ≤ 2 columns apart)
    val cellDeg = clusterM / EXPLORE_METERS_PER_DEGREE
    val cosRef = if (points.isEmpty()) 1.0 else StrictMath.cos(points[0].lat * EXPLORE_RAD)
    val grid = HashMap<Long, MutableList<Int>>()
    fun cellOf(lat: Double, lng: Double): Pair<Long, Long> = floor(lat / cellDeg).toLong() to floor(lng * cosRef / cellDeg).toLong()
    points.forEachIndexed { i, p ->
        val (r, c) = cellOf(p.lat, p.lng)
        grid.getOrPut(r * 4_000_000L + c) { ArrayList() }.add(i)
    }
    fun neighbours(i: Int): List<Int> {
        val p = points[i]
        val (r, c) = cellOf(p.lat, p.lng)
        val near = ArrayList<Int>()
        for (dr in -1L..1L) for (dc in -2L..2L) {
            for (j in grid[(r + dr) * 4_000_000L + (c + dc)] ?: continue) {
                if (alive[j] && exploreDistanceM(p, points[j]) <= clusterM) near.add(j)
            }
        }
        return near
    }
    repeat(count) {
        var best = -1
        var bestN = 0
        for (i in points.indices) {
            if (!alive[i]) continue
            val n = neighbours(i).size
            if (n > bestN) {
                best = i
                bestN = n
            }
        }
        if (best < 0) return out
        val group = neighbours(best)
        var lat = 0.0
        var lng = 0.0
        for (j in group) {
            lat += points[j].lat
            lng += points[j].lng
            alive[j] = false
        }
        out.add(GeoPoint(lat / group.size, lng / group.size))
    }
    return out
}

/** The lines with every part within [radiusM] of a place cut out (a line can split in pieces; pieces under 2 points dropped). Pure. */
fun hideNearPlaces(lines: List<ShareLine>, places: List<LatLng>, radiusM: Double = SHARE_PLACE_HIDE_M): List<ShareLine> {
    if (places.isEmpty()) return lines
    val out = ArrayList<ShareLine>()
    for (line in lines) {
        var piece = ArrayList<GeoPoint>()
        fun close() {
            if (piece.size >= 2) out.add(ShareLine(line.level, line.pioneered, piece))
            piece = ArrayList()
        }
        val pts = line.points
        for (k in 1 until pts.size) {
            val a = pts[k - 1]
            val b = pts[k]
            val visible = visibleParts(a, b, places, radiusM)
            for ((u0, u1) in visible) {
                if (u0 > 0.0 || piece.isEmpty()) {
                    close()
                    piece.add(lerp(a, b, u0))
                }
                piece.add(lerp(a, b, u1))
                if (u1 < 1.0) close()
            }
            if (visible.isEmpty()) close()
        }
        close()
    }
    return out
}

/** parts [u0, u1] ⊂ [0, 1] of the segment a → b outside every circle (sorted, disjoint) */
private fun visibleParts(a: GeoPoint, b: GeoPoint, places: List<LatLng>, radiusM: Double): List<Pair<Double, Double>> {
    val hidden = ArrayList<Pair<Double, Double>>()
    for (c in places) {
        val kx = EXPLORE_METERS_PER_DEGREE * StrictMath.cos(c.lat * EXPLORE_RAD)
        val ky = EXPLORE_METERS_PER_DEGREE
        val px = (a.lng - c.lng) * kx
        val py = (a.lat - c.lat) * ky
        val dx = (b.lng - a.lng) * kx
        val dy = (b.lat - a.lat) * ky
        val qa = dx * dx + dy * dy
        val qb = 2 * (px * dx + py * dy)
        val qc = px * px + py * py - radiusM * radiusM
        if (qa == 0.0) {
            if (qc < 0) hidden.add(0.0 to 1.0)
            continue
        }
        val disc = qb * qb - 4 * qa * qc
        if (disc <= 0) continue
        val root = sqrt(disc)
        val t0 = max(0.0, (-qb - root) / (2 * qa))
        val t1 = min(1.0, (-qb + root) / (2 * qa))
        if (t0 < t1) hidden.add(t0 to t1)
    }
    if (hidden.isEmpty()) return listOf(0.0 to 1.0)
    hidden.sortBy { it.first }
    val out = ArrayList<Pair<Double, Double>>()
    var at = 0.0
    for ((h0, h1) in hidden) {
        if (h0 > at) out.add(at to h0)
        at = max(at, h1)
    }
    if (at < 1.0) out.add(at to 1.0)
    return out
}

private fun lerp(a: GeoPoint, b: GeoPoint, u: Double): GeoPoint =
    when (u) {
        0.0 -> a
        1.0 -> b
        else -> GeoPoint(a.lat + (b.lat - a.lat) * u, a.lng + (b.lng - a.lng) * u)
    }
