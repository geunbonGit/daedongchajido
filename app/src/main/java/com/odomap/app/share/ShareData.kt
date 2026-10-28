package com.odomap.app.share

import com.odomap.app.model.DriveRecord
import com.odomap.app.record.DriveRecorder
import com.odomap.core.explore.DriveReplay
import com.odomap.core.explore.ExploreWalkSummary
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.LatLng
import com.odomap.core.region.RegionIndex
import com.odomap.core.explore.EXPLORE_METERS_PER_DEGREE
import com.odomap.core.share.SHARE_TRIM_M
import com.odomap.core.share.hideNearPlaces
import com.odomap.core.share.SHARE_URL_PREFIX
import com.odomap.core.share.ShareCard
import com.odomap.core.share.ShareCards
import com.odomap.core.share.ShareEncoder
import com.odomap.core.share.ShareQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The three share cards (design.md 4.8): 이번 드라이브 · 내 지도 · 가 본 곳 */
enum class ShareKind(val label: String) {
    DRIVE("이번 드라이브"),
    MAP("내 지도"),
    REGIONS("가 본 곳"),
}

/** What the share screen needs from the app (a snapshot — the card is built from stored data). */
class ShareInputs(
    val drives: List<DriveRecord>,
    val index: RegionIndex?,
    /** visited 시·군·구 codes (drives + my roads) */
    val visited: Set<String>,
    /** length of my map (m) */
    val netM: Double,
)

/**
 * Card builders on stored data (core ShareCards): a stored drive is replayed from its path.json on today's net (DriveReplay — the levels are
 * the roads' levels now, this drive included, as the spec asks), the whole map from the graph. Regions on the wire = Region.index.
 */
object ShareData {
    /** an ExploreWalkSummary with only the two numbers ShareCards.drive reads */
    private fun summaryOf(r: DriveRecord): ExploreWalkSummary =
        ExploreWalkSummary(
            pioneeredM = r.pioneeredM, onFootM = r.distanceM, newNodes = 0, newEdges = emptyList(), walkEdges = emptyList(), walkPasses = emptyList(),
            walkPath = emptyList(), walkS = 0.0, runS = 0.0, vehicleS = 0.0, walkM = 0.0, runM = 0.0, vehicleM = 0.0,
        )

    /** region numbers (Region.index) of codes, unknown codes dropped */
    fun indices(index: RegionIndex?, codes: Collection<String>): List<Int> = if (index == null) emptyList() else codes.mapNotNull { index.byCode(it)?.index }

    /** visited regions right after drive [r]: every region of the drives up to it (start order) */
    private fun visitedAfter(inputs: ShareInputs, r: DriveRecord): Int =
        inputs.drives.filter { it.startedAt <= r.startedAt }.flatMap { it.regions }.toSet().size

    fun driveCard(inputs: ShareInputs, r: DriveRecord, replay: DriveReplay, hideEnds: Boolean): ShareCard.Drive =
        ShareCards.drive(
            replay = replay,
            summary = summaryOf(r),
            startedAtMs = r.startedAt,
            endedAtMs = r.endedAt,
            title = r.title ?: "",
            newRegions = indices(inputs.index, r.firstVisits),
            passedRegions = indices(inputs.index, r.regions.distinct()),
            visitedTotal = visitedAfter(inputs, r),
            hideEnds = hideEnds,
            hideRadiusM = if (hideEnds) SHARE_TRIM_M else 0.0,
        )

    /**
     * The drive card into a QR with the start / end hidden for sure: core already leaves out edges within 300 m (along the path and straight
     * line), but the drawn line is smoothed, simplified (Douglas–Peucker, tens of m) and quantized (≈ 11 m), so a piece can end up a little
     * closer. Every part within the radius of [ends] is cut out (hideNearPlaces), the QR is built, and the DECODED lines are checked
     * segment by segment; while some segment is closer than 300 m the radius grows by the shortfall + 10 m and the card is built again.
     */
    fun encodeDrive(card: ShareCard.Drive, ends: List<LatLng>, hide: Boolean, prefix: String = SHARE_URL_PREFIX): ShareQr {
        if (!hide || ends.isEmpty()) return ShareEncoder.encode(card, prefix)
        var radius = SHARE_TRIM_M
        var qr: ShareQr? = null
        repeat(5) {
            val cut = card.copy(lines = hideNearPlaces(card.lines, ends, radius))
            val made = ShareEncoder.encode(cut, prefix)
            qr = made
            val closest = closestToEnds(made.card, ends)
            if (closest >= SHARE_TRIM_M) return made
            radius += SHARE_TRIM_M - closest + 10
        }
        return qr!!
    }

    /** shortest distance (m) from any decoded line segment of a drive card to [ends] */
    fun closestToEnds(card: ShareCard, ends: List<LatLng>): Double {
        val lines = (card as? ShareCard.Drive)?.lines ?: return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (e in ends) {
            val kx = EXPLORE_METERS_PER_DEGREE * Math.cos(Math.toRadians(e.lat))
            val ky = EXPLORE_METERS_PER_DEGREE
            for (l in lines) {
                val pts = l.points
                for (i in pts.indices) {
                    val ax = (pts[i].lng - e.lng) * kx
                    val ay = (pts[i].lat - e.lat) * ky
                    if (i == 0) {
                        best = minOf(best, Math.hypot(ax, ay))
                        continue
                    }
                    val bx = (pts[i - 1].lng - e.lng) * kx
                    val by = (pts[i - 1].lat - e.lat) * ky
                    val dx = ax - bx
                    val dy = ay - by
                    val len2 = dx * dx + dy * dy
                    val u = if (len2 > 0) (-(bx * dx + by * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                    best = minOf(best, Math.hypot(bx + u * dx, by + u * dy))
                }
            }
        }
        return best
    }

    /**
     * Builds the card of [kind] and fits it into a QR. [driveId]: the drive of the DRIVE card. [hide]: 출발·도착 가리기 (drive: 300 m along the
     * path + 300 m straight line; my map: frequent places). Null when there is nothing to share.
     */
    suspend fun build(recorder: DriveRecorder, inputs: ShareInputs, kind: ShareKind, driveId: String?, hide: Boolean, nowMs: Long, prefix: String = SHARE_URL_PREFIX): ShareQr? {
        val card: ShareCard =
            when (kind) {
                ShareKind.DRIVE -> {
                    val r = inputs.drives.firstOrNull { it.id == driveId } ?: inputs.drives.maxByOrNull { it.startedAt } ?: return null
                    val replay = recorder.replays(listOf(r.id))[r.id] ?: return null
                    // the ends: where the drive met the net and the first / last raw fix (the real start / parking spot)
                    val route = recorder.route(r.id)
                    val ends = listOfNotNull(replay.startPoint, replay.endPoint) +
                        listOfNotNull(route.firstOrNull(), route.lastOrNull()).map { GeoPoint(it.first, it.second) }
                    return withContext(Dispatchers.Default) { encodeDrive(driveCard(inputs, r, replay, hide), ends, hide, prefix) }
                }
                ShareKind.MAP -> {
                    if (inputs.drives.isEmpty()) return null
                    val endpoints = ArrayList<LatLng>()
                    for (d in inputs.drives) {
                        val route = recorder.route(d.id)
                        if (route.isEmpty()) continue
                        endpoints.add(GeoPoint(route.first().first, route.first().second))
                        endpoints.add(GeoPoint(route.last().first, route.last().second))
                    }
                    recorder.withGraph { g ->
                        ShareCards.atlas(
                            graph = g,
                            createdAtMs = nowMs,
                            drivenM = inputs.drives.sumOf { it.distanceM },
                            driveCount = inputs.drives.size,
                            title = "",
                            visitedTotal = inputs.visited.size,
                            endpoints = endpoints,
                            hidePlaces = hide,
                        )
                    }
                }
                ShareKind.REGIONS -> {
                    if (inputs.visited.isEmpty()) return null
                    ShareCards.regions(nowMs, inputs.drives.sumOf { it.distanceM }, inputs.netM, inputs.drives.size, "", indices(inputs.index, inputs.visited))
                }
            }
        return withContext(Dispatchers.Default) { ShareEncoder.encode(card, prefix) }
    }
}
