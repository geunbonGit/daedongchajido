package com.odomap.core.explore

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Odomap additions (not in the TS original) for incremental map updates in the app: live edge changes of the walk being recorded,
// tile keys of changed nodes/edges (storage), and drawn lines per z14 tile. Nothing here changes the net or the walk.

/** An edge the walk created or counted a pass on: a snapshot of the edge (visits = cumulative passes in the net). */
data class ExploreEdgeChange(
    val edge: ExploreEdge,
    /** created by this walk (a first-time road) */
    val created: Boolean,
    /** passes this walk counted on it (≤ passCap) */
    val passes: Int,
) {
    /** thickness level now (1..5) */
    val level: Int get() = exploreLineLevel(edge.visits)

    /** z14 tile the edge is stored and drawn in */
    val tileKey: String get() = exploreEdgeTile(edge).key
}

/** [ExploreRecorder.liveUpdate]: settled numbers + edges changed since the given revision. */
data class ExploreLiveUpdate(
    /** pass to the next liveUpdate call */
    val revision: Int,
    /** time of the last settled step (ms, -Infinity before the first) */
    val settledT: Double,
    val pioneeredM: Double,
    val drivenM: Double,
    val changes: List<ExploreEdgeChange>,
)

/** tile a node is stored in (its position) */
fun exploreNodeTile(node: ExploreNode): ExploreTileXY = exploreTileOf(node.lat, node.lng)

/** tile an edge is stored / drawn in (its midpoint — exploreTilesOfGraph) */
fun exploreEdgeTile(edge: ExploreEdge): ExploreTileXY = exploreTileOf((edge.aLat + edge.bLat) / 2, (edge.aLng + edge.bLng) / 2)

/** Tile keys ("x/y") holding the given nodes / edge keys (unknown ids skipped), in first-seen order. */
fun exploreTileKeysOf(graph: ExploreGraph, nodeIds: Iterable<Int>, edgeKeys: Iterable<Long>): LinkedHashSet<String> {
    val out = LinkedHashSet<String>()
    for (id in nodeIds) graph.node(id)?.let { out.add(exploreNodeTile(it).key) }
    for (key in edgeKeys) graph.edgeByKey(key)?.let { out.add(exploreEdgeTile(it).key) }
    return out
}

/** Tiles to rewrite after a walk: those holding [ExploreGraph.dirtyNodes] / [ExploreGraph.dirtyEdges] (call before clearDirty). */
fun ExploreGraph.dirtyTileKeys(): LinkedHashSet<String> = exploreTileKeysOf(this, dirtyNodes, dirtyEdges)

/** Edges whose tile ([exploreEdgeTile]) is one of [tiles] ("x/y" z14 keys), in input order. */
fun exploreEdgesInTiles(edges: Iterable<ExploreEdge>, tiles: Collection<String>): List<ExploreEdge> {
    val filter = TileFilter(tiles)
    return edges.filter { filter.keyOf(it) != null }
}

/**
 * Drawn lines per tile: each edge goes to its own tile ([exploreEdgeTile]) and lines are grouped tile by tile, so a tile can be redrawn
 * alone (lines end at the tile border; ends are never moved by smoothing, so neighbouring tiles still meet). With [tiles], only those
 * (each present in the result, empty when it has no edge); without, every tile that has edges.
 */
fun exploreLinesByTile(
    edges: Iterable<ExploreEdge>,
    tiles: Collection<String>? = null,
    options: ExploreLinesOptions = ExploreLinesOptions(),
): LinkedHashMap<String, List<ExploreLine>> {
    val groups = LinkedHashMap<String, MutableList<ExploreEdge>>()
    if (tiles != null) {
        for (key in tiles) groups[key] = ArrayList()
        val filter = TileFilter(tiles)
        for (edge in edges) filter.keyOf(edge)?.let { groups[it]!!.add(edge) }
    } else {
        for (edge in edges) groups.getOrPut(exploreEdgeTile(edge).key) { ArrayList() }.add(edge)
    }
    val out = LinkedHashMap<String, List<ExploreLine>>()
    for ((key, list) in groups) out[key] = if (list.isEmpty()) emptyList() else exploreLines(list, options)
    return out
}

/** z14 tile membership: the column (cheap, exploreTileOf's x formula) first, the row (log/tan) only for matching columns */
private class TileFilter(tiles: Collection<String>) {
    private val keys = HashMap<Long, String>()
    private val xs = HashSet<Int>()
    private val n = StrictMath.pow(2.0, EXPLORE_TILE_Z.toDouble())

    init {
        for (key in tiles) {
            val slash = key.indexOf('/')
            if (slash <= 0) continue
            val x = key.substring(0, slash).toIntOrNull() ?: continue
            val y = key.substring(slash + 1).toIntOrNull() ?: continue
            keys[(x.toLong() shl 32) or (y.toLong() and 0xffffffffL)] = "$x/$y"
            xs.add(x)
        }
    }

    fun keyOf(edge: ExploreEdge): String? {
        val lng = (edge.aLng + edge.bLng) / 2
        val x = max(0.0, min(n - 1, floor(((lng + 180) / 360) * n))).toInt()
        if (x !in xs) return null
        val tile = exploreEdgeTile(edge)
        return keys[(tile.x.toLong() shl 32) or (tile.y.toLong() and 0xffffffffL)]
    }
}
