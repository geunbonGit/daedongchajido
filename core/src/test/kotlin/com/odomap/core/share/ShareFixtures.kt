package com.odomap.core.share

import com.odomap.core.DemoDrive
import com.odomap.core.DemoDrives
import com.odomap.core.TestRegions
import com.odomap.core.car.CarProfile
import com.odomap.core.explore.DriveReplay
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreWalkSummary
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.LatLng
import com.odomap.core.region.RegionTracker

/** The 14 demo drives in start order into one car net, regions tracked, each drive's replay taken right after it (levels as then). */
object ShareFixtures {
    class Driven(val drive: DemoDrive, val summary: ExploreWalkSummary, val replay: DriveReplay, val tracker: RegionTracker, val visitedTotal: Int) {
        fun card(title: String = drive.title, hideEnds: Boolean = true, hideRadiusM: Double = 0.0): ShareCard.Drive =
            ShareCards.drive(
                replay, summary, drive.startedAt, drive.endedAt, title,
                tracker.newRegions.map { it.index }, tracker.passed.map { it.index }, visitedTotal, hideEnds, hideRadiusM,
            )
    }

    val graph: ExploreGraph = CarProfile.newGraph()
    val drives: List<Driven>
    val visited = LinkedHashSet<String>()

    init {
        val out = ArrayList<Driven>()
        for (drive in DemoDrives.all()) {
            val summary = computeExploreWalk(graph, drive.startedAt.toDouble(), drive.points, config = CarProfile.pipeline)
            val tracker = RegionTracker(TestRegions.index, visited)
            for (p in drive.points) tracker.push(p.t.toLong(), p.lat, p.lng)
            visited.addAll(tracker.visited)
            out.add(Driven(drive, summary, DriveReplay.of(graph, summary), tracker, visited.size))
        }
        drives = out
    }

    fun driven(id: String): Driven = drives.first { it.drive.id == id }

    /** first and last fix of every drive */
    val endpoints: List<LatLng> get() = drives.flatMap { listOf(GeoPoint(it.drive.points.first().lat, it.drive.points.first().lng), GeoPoint(it.drive.points.last().lat, it.drive.points.last().lng)) }

    val totalDrivenM: Double get() = drives.sumOf { it.summary.drivenM }

    /** the atlas / conquest cards are made right after the last drive */
    val createdAt: Long get() = drives.last().drive.endedAt
}
