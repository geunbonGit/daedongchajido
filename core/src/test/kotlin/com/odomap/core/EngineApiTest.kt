package com.odomap.core

import com.odomap.core.car.CarProfile
import com.odomap.core.explore.DriveReplay
import com.odomap.core.explore.ExploreEdgeChange
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreLinesOptions
import com.odomap.core.explore.ExploreRecorder
import com.odomap.core.explore.ExploreWalkSummary
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.explore.dirtyTileKeys
import com.odomap.core.explore.exploreEdgeKey
import com.odomap.core.explore.exploreEdgeTile
import com.odomap.core.explore.exploreEdgesInTiles
import com.odomap.core.explore.exploreLines
import com.odomap.core.explore.exploreLinesByTile
import com.odomap.core.explore.exploreTileOf
import com.odomap.core.explore.exploreTilesOfGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The app team's engine requests: drivenM, live edge changes since a revision, tile keys of changes, lines per tile, the drive replay.
class EngineApiTest {
    private fun drive(graph: ExploreGraph, id: String): ExploreWalkSummary {
        val d = DemoDrives.load(id)
        return computeExploreWalk(graph, d.startedAt.toDouble(), d.points, config = CarProfile.pipeline)
    }

    @Test
    fun `drivenM is the counted driven distance and walkPathT times every path token`() {
        val graph = CarProfile.newGraph()
        val d = DemoDrives.load("commute-1-am")
        val s = drive(graph, "commute-1-am")
        assertEquals(s.onFootM, s.drivenM, 0.0)
        assertTrue(s.drivenM > 14_000)
        assertEquals(s.walkPath.size, s.walkPathT.size)
        assertTrue(s.walkPathT.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(s.walkPathT.first() >= d.points.first().t && s.walkPathT.last() <= d.points.last().t)
        // phone defaults: the extra field does not touch the result
        val phone = computeExploreWalk(ExploreGraph(), d.startedAt.toDouble(), d.points.take(600))
        assertEquals(phone.walkPath.size, phone.walkPathT.size)
    }

    @Test
    fun `live updates carry only the edges changed since the last revision and add up to the drive`() {
        val graph = CarProfile.newGraph()
        drive(graph, "commute-1-am")
        drive(graph, "commute-1-pm")
        val d = DemoDrives.load("commute-3-am") // part known (levels go up), part new
        val recorder = ExploreRecorder(graph, d.startedAt.toDouble(), config = CarProfile.pipeline)
        val seen = LinkedHashMap<Long, ExploreEdgeChange>()
        var revision = 0
        var sent = 0
        var updates = 0
        for (chunk in d.points.chunked(30)) {
            recorder.push(chunk)
            val update = recorder.liveUpdate(revision)
            val summary = recorder.summary()
            assertEquals(summary.pioneeredM, update.pioneeredM, 0.0)
            assertEquals(summary.drivenM, update.drivenM, 0.0)
            assertEquals(update.changes.size, update.changes.map { exploreEdgeKey(it.edge.a, it.edge.b) }.toSet().size)
            for (c in update.changes) seen[exploreEdgeKey(c.edge.a, c.edge.b)] = c
            sent += update.changes.size
            updates += 1
            revision = update.revision
        }
        val final = recorder.finish()
        for (c in recorder.liveUpdate(revision).changes) seen[exploreEdgeKey(c.edge.a, c.edge.b)] = c
        // the union of the deltas is the drive: every walk edge, with its final visits, new ones flagged
        assertEquals(final.walkEdges.map { exploreEdgeKey(it.a, it.b) }.toSet(), seen.keys)
        val fresh = final.newEdges.map { exploreEdgeKey(it.a, it.b) }.toSet()
        for ((key, c) in seen) {
            assertEquals(graph.edgeByKey(key)!!.visits, c.edge.visits)
            assertEquals(key in fresh, c.created)
            assertTrue(c.passes >= 1)
        }
        // revision 0 = everything; a revision from the end = nothing
        assertEquals(seen.keys, recorder.liveUpdate(0).changes.map { exploreEdgeKey(it.edge.a, it.edge.b) }.toSet())
        assertEquals(0, recorder.liveUpdate(recorder.liveUpdate(0).revision).changes.size)
        println("[engine] live: $updates updates sent $sent edge changes for ${seen.size} edges (${fresh.size} new) — summary() would copy the whole lists each time")
        assertTrue(sent < 1.3 * seen.size)
    }

    @Test
    fun `dirty tile keys are the tiles exploreTilesOfGraph writes`() {
        val graph = CarProfile.newGraph()
        drive(graph, "commute-1-am")
        graph.clearDirty()
        drive(graph, "commute-3-am")
        val keys = graph.dirtyTileKeys()
        // the app's hand-decoded version (GraphStore.saveDirty)
        val manual = HashSet<String>()
        for (id in graph.dirtyNodes) graph.node(id)?.let { manual.add(exploreTileOf(it.lat, it.lng).key) }
        for (key in graph.dirtyEdges) {
            val (a, b) = ExploreGraph.edgeEnds(key)
            graph.edge(a, b)?.let { manual.add(exploreTileOf((it.aLat + it.bLat) / 2, (it.aLng + it.bLng) / 2).key) }
        }
        assertEquals(manual, keys.toSet())
        val written = exploreTilesOfGraph(graph, keys)
        val writtenEdges = written.values.flatMap { t -> t.e.map { exploreEdgeKey(it.a, it.b) } }.toSet()
        assertTrue(writtenEdges.containsAll(graph.dirtyEdges))
        println("[engine] commute-3-am touched ${keys.size} of ${exploreTilesOfGraph(graph).size} tiles")
    }

    @Test
    fun `lines per tile cover the net and a subset redraws alone`() {
        val graph = CarProfile.newGraph()
        for (id in listOf("commute-1-am", "commute-1-pm", "commute-3-am")) drive(graph, id)
        val options = ExploreLinesOptions(smooth = true, simplifyM = 1.0)
        val byTile = exploreLinesByTile(graph.edges(), options = options)
        val counted = HashMap<String, Int>()
        for (e in graph.edges()) counted.merge(exploreEdgeTile(e).key, 1, Int::plus)
        assertEquals(counted.keys, byTile.keys)
        for ((key, lines) in byTile) {
            val raw = exploreLines(exploreEdgesInTiles(graph.edges(), listOf(key)), ExploreLinesOptions(nodes = true))
            assertEquals(key, counted[key], raw.sumOf { it.nodes!!.size - 1 })
            assertTrue(lines.isNotEmpty())
        }
        val some = byTile.keys.take(3) + "1/1"
        val part = exploreLinesByTile(graph.edges(), some, options)
        assertEquals(some, part.keys.toList())
        assertEquals(emptyList<Any>(), part["1/1"])
        for (key in some.dropLast(1)) assertEquals(byTile[key], part[key])
    }

    @Test
    fun `drive replay — ordered edges with style, distance and time`() {
        val graph = CarProfile.newGraph()
        drive(graph, "commute-1-am")
        val d = DemoDrives.load("commute-3-am")
        val s = drive(graph, "commute-3-am")
        val replay = DriveReplay.of(graph, s)
        val segs = replay.segments
        assertTrue(segs.isNotEmpty())
        assertTrue(segs.first().lift)
        // every edge of the walk is driven, new ones flagged, in path order
        assertEquals(s.walkEdges.map { exploreEdgeKey(it.a, it.b) }.toSet(), segs.map { it.key }.toSet())
        val fresh = s.newEdges.map { exploreEdgeKey(it.a, it.b) }.toSet()
        assertTrue(segs.all { it.pioneered == (it.key in fresh) })
        assertTrue(segs.zipWithNext().all { (a, b) -> b.startM == a.endM && (b.lift || b.from == a.to) })
        assertTrue(segs.zipWithNext().all { (a, b) -> a.startT <= a.endT && a.endT <= b.startT + 1e-6 })
        assertTrue(replay.startT >= d.points.first().t && replay.endT <= d.points.last().t)
        val newM = segs.filter { it.pioneered }.sumOf { it.endM - it.startM }
        println(
            "[engine] replay commute-3-am: ${segs.size} segments, %.2f km (drivenM %.2f km), new %.2f km (pioneered %.2f km), %d pieces, %d strokes, %.0f s"
                .format(replay.lengthM / 1000, s.drivenM / 1000, newM / 1000, s.pioneeredM / 1000, replay.pieces().size, segs.count { it.lift }, (replay.endT - replay.startT) / 1000),
        )
        assertEquals(s.drivenM, replay.lengthM, 0.03 * s.drivenM)
        assertEquals(s.pioneeredM, newM, 0.03 * s.drivenM)
        // pieces join consecutive same-style segments
        val pieces = replay.pieces()
        assertEquals(segs.size, pieces.sumOf { it.coords.size - 1 })
        assertTrue(pieces.zipWithNext().all { (a, b) -> a.endT <= b.startT + 1e-6 })
        // lookups
        assertEquals(0, replay.indexAtM(0.0))
        assertEquals(segs.size - 1, replay.indexAtM(1e9))
        val mid = replay.indexAtT((replay.startT + replay.endT) / 2)
        assertTrue(segs[mid].startT <= (replay.startT + replay.endT) / 2 + 1e-6)
    }
}
