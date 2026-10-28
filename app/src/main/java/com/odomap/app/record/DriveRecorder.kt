package com.odomap.app.record

import android.content.Context
import android.util.Log
import com.odomap.app.R
import com.odomap.app.drive.LiveEventSource
import com.odomap.app.engine.DriveEngine
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.engine.FinishedDrive
import com.odomap.app.engine.LiveDrive
import com.odomap.app.engine.TileDot
import com.odomap.app.engine.tileDotsGeoJson
import com.odomap.app.engine.formatKm
import com.odomap.app.location.EventSource
import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.DriveStatus
import com.odomap.app.model.Fix
import com.odomap.app.model.SourceEvent
import com.odomap.app.replay.ReplaySource
import com.odomap.app.replay.ReplayState
import com.odomap.app.store.DriveStore
import com.odomap.app.store.GraphStore
import com.odomap.app.tts.Speaker
import com.odomap.core.region.Region
import com.odomap.core.region.RegionIndex
import com.odomap.core.region.RegionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The cumulative map: drawn net + totals. */
data class NetState(
    val geoJson: String = EMPTY_FEATURES,
    /** bumps on every change (cheap equality for the map) */
    val version: Int = 0,
    val netLengthM: Double = 0.0,
    val bounds: Bounds? = null,
    val driveCount: Int = 0,
    val drivenM: Double = 0.0,
    val pioneeredM: Double = 0.0,
    val loaded: Boolean = false,
    /** z14 tiles with my roads (far-zoom dots, off-screen guide) */
    val tiles: List<TileDot> = emptyList(),
    val dotsGeoJson: String = EMPTY_FEATURES,
    /** roads driven three times or more */
    val frequentM: Double = 0.0,
    /** length of my roads per 시·군·구 code (edge midpoints) */
    val regionM: Map<String, Double> = emptyMap(),
)

/** A stored drive ready to draw: its lines (today's levels) and its path (simplified raw fixes — start/end flags). */
data class DriveDetail(val record: DriveRecord, val linesGeoJson: String, val route: List<Pair<Double, Double>>)

/**
 * App-scoped recorder: consumes one [EventSource] (live car or demo replay), starts a drive when the state says driving (gear D / speed rule),
 * feeds fixes into the [DriveEngine] on the cumulative graph, and on park (P) stores the drive + changed graph tiles, publishes the arrival
 * summary and speaks it. All engine work runs on one thread. The foreground [RecordingService] keeps the process alive for live recording.
 */
class DriveRecorder(
    private val context: Context,
    root: File,
    private val speaker: Speaker,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "odomap-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val graphStore = GraphStore(root)
    private val driveStore = DriveStore(root)
    private lateinit var engine: DriveEngine

    private val _net = MutableStateFlow(NetState())
    val net: StateFlow<NetState> = _net.asStateFlow()
    private val _drives = MutableStateFlow<List<DriveRecord>>(emptyList())
    val drives: StateFlow<List<DriveRecord>> = _drives.asStateFlow()
    private val _live = MutableStateFlow<LiveDrive?>(null)
    val live: StateFlow<LiveDrive?> = _live.asStateFlow()
    private val _puck = MutableStateFlow<Fix?>(null)
    val puck: StateFlow<Fix?> = _puck.asStateFlow()
    private val _status = MutableStateFlow<DriveStatus?>(null)
    val status: StateFlow<DriveStatus?> = _status.asStateFlow()
    private val _arrival = MutableStateFlow<DriveRecord?>(null)
    val arrival: StateFlow<DriveRecord?> = _arrival.asStateFlow()
    private val _sourceName = MutableStateFlow("—")
    val sourceName: StateFlow<String> = _sourceName.asStateFlow()
    /** the drive's raw fixes after the settled point up to now (GeoJSON LineString) — drawn as the live tail */
    private val _tail = MutableStateFlow<String?>(null)
    val tail: StateFlow<String?> = _tail.asStateFlow()

    /** 시·군·구 경계(assets/regions/regions.json) — 읽은 뒤에 채워진다 */
    private val _regions = MutableStateFlow<RegionIndex?>(null)
    val regions: StateFlow<RegionIndex?> = _regions.asStateFlow()

    /** 주행 중 지금 지역(확정된 것) */
    private val _region = MutableStateFlow<Region?>(null)
    val region: StateFlow<Region?> = _region.asStateFlow()

    /** 가 본 시·군·구 코드(드라이브 기록 + 내 길이 지나는 곳) */
    private val _visited = MutableStateFlow<Set<String>>(emptySet())
    val visited: StateFlow<Set<String>> = _visited.asStateFlow()
    private var tracker: RegionTracker? = null

    /** true once every stored drive has its path (replay / share). Drives from before path.json are rebuilt once at start. */
    private val _pathsReady = MutableStateFlow(false)
    val pathsReady: StateFlow<Boolean> = _pathsReady.asStateFlow()

    val replayState = MutableStateFlow(ReplayState())
    val replaySpeed = MutableStateFlow(60)

    private var liveRequested = false
    private var liveJob: Job? = null
    private var replayJob: Job? = null

    // drive bookkeeping (engine thread)
    private val recent = ArrayDeque<Fix>()
    private var fixLog: DriveStore.FixLog? = null
    private var pendingTitle: String? = null
    private var pendingSource: String? = null
    private var lastNumbersAt = 0L
    private var lastLinesAt = 0L
    private var lastPuckAt = 0L
    private var lastFix: Fix? = null
    private val driveFixes = ArrayList<Fix>()
    private var settledT = 0L
    private val routeCache = object : LinkedHashMap<String, List<Pair<Double, Double>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<Double, Double>>>?): Boolean = size > 80
    }

    init {
        scope.launch {
            engine = DriveEngine(graphStore.load())
            _regions.value =
                runCatching { context.assets.open("regions/regions.json").use { RegionIndex.load(it) } }
                    .onFailure { Log.w(TAG, "regions unavailable", it) }
                    .getOrNull()
            refreshNet()
            ensurePaths()
        }
    }

    // ---- control (any thread) ----

    /** Start listening to the car (called by RecordingService once permissions allow). */
    fun startLive() {
        scope.launch {
            liveRequested = true
            if (replayJob == null) attachLive()
        }
    }

    fun stopLive() {
        scope.launch {
            liveRequested = false
            liveJob?.cancelAndJoin()
            liveJob = null
        }
    }

    /** Play demo drives in order (replaces the live source until done). */
    fun playDemo(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            replayJob?.cancelAndJoin()
            liveJob?.cancelAndJoin()
            liveJob = null
            lastFix?.let { if (engine.isDriving) finishDrive(it.t) }
            val source = ReplaySource(context.assets, ids, replaySpeed, replayState)
            _sourceName.value = source.name
            replayJob =
                scope.launch {
                    try {
                        source.events().collect { handle(it) }
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            if (engine.isDriving) finishDrive(lastFix?.t ?: System.currentTimeMillis())
                            replayJob = null
                            if (liveRequested) attachLive()
                        }
                    }
                }
        }
    }

    fun stopDemo() {
        scope.launch { replayJob?.cancelAndJoin() }
    }

    fun dismissArrival() {
        _arrival.value = null
    }

    /** 전체 삭제: every drive, the whole net. */
    fun resetAll(onDone: () -> Unit = {}) {
        scope.launch {
            replayJob?.cancelAndJoin()
            engine.abandon()
            fixLog?.close()
            fixLog = null
            driveStore.clear()
            graphStore.clear()
            engine = DriveEngine(com.odomap.core.car.CarProfile.newGraph())
            _live.value = null
            _tail.value = null
            _arrival.value = null
            _puck.value = null
            _region.value = null
            tracker = null
            routeCache.clear()
            driveFixes.clear()
            recent.clear()
            lastFix = null
            refreshNet()
            onDone()
        }
    }

    /**
     * 드라이브 하나 지우기: 남은 드라이브를 옛 차례대로 새 그래프에 다시 달려(저장된 원 위치) 내 지도와 처음 달린 길·첫 방문을 다시 셈한다.
     */
    fun deleteDrive(id: String, onDone: () -> Unit = {}) {
        scope.launch {
            if (engine.isDriving) return@launch
            driveStore.delete(id)
            routeCache.remove(id)
            if (_arrival.value?.id == id) _arrival.value = null
            rebuild(keepRecords = false)
            refreshNet()
            onDone()
        }
    }

    /**
     * Every stored drive driven again, in the old order, on a new graph from its raw fixes (engine thread). Stores the edges and the path
     * as driven (path.json — node ids of the new graph) and swaps the graph in. [keepRecords]: keep the stored summaries as they are (only
     * the edges / paths are written — the backfill of drives recorded before path.json existed); otherwise numbers and first visits are
     * counted again (a drive was deleted).
     */
    private fun rebuild(keepRecords: Boolean) {
        val keep = driveStore.list().sortedBy { it.startedAt }
        val fresh = DriveEngine(com.odomap.core.car.CarProfile.newGraph())
        val visited = LinkedHashSet<String>()
        for (d in keep) {
            val fixes = driveStore.fixes(d.id)
            if (fixes.isEmpty()) continue
            fresh.start(d.id, d.startedAt, d.title, d.source)
            val tr = _regions.value?.let { RegionTracker(it, visited) }
            for (f in fixes) {
                fresh.push(f)
                tr?.push(f.t, f.lat, f.lng)
            }
            val done = fresh.finish(d.endedAt) ?: continue
            val record =
                when {
                    keepRecords -> d
                    tr != null -> {
                        val seq = ArrayList<String>()
                        for (e in tr.entries) if (seq.lastOrNull() != e.region.code) seq.add(e.region.code)
                        done.record.copy(regions = seq, firstVisits = tr.newRegions.map { it.code })
                    }
                    else -> done.record
                }
            if (tr != null) visited.addAll(tr.visited)
            driveStore.save(record, done.walkEdges, done.newEdges, done.path, done.pathT)
        }
        graphStore.clear()
        graphStore.saveDirty(fresh.graph)
        engine = fresh
    }

    private fun ensurePaths() {
        val missing = driveStore.list().count { !driveStore.hasPath(it.id) }
        if (missing > 0 && !engine.isDriving) {
            val t0 = System.nanoTime()
            rebuild(keepRecords = true)
            Log.i(TAG, "paths: rebuilt ${driveStore.list().size} drives ($missing without path) in ${(System.nanoTime() - t0) / 1_000_000} ms")
            refreshNet()
        }
        _pathsReady.value = true
    }

    /**
     * Stored drives as driven (DriveReplay on today's net — levels now), in [ids] order. Drives without a path are left out.
     * Waits for the one-time path backfill.
     */
    suspend fun replays(ids: List<String>): Map<String, com.odomap.core.explore.DriveReplay> {
        pathsReady.first { it }
        return withContext(dispatcher) {
            val out = LinkedHashMap<String, com.odomap.core.explore.DriveReplay>()
            for (id in ids) {
                val (path, pathT) = driveStore.path(id) ?: continue
                val fresh = driveStore.edges(id)?.second ?: emptyList()
                out[id] = engine.replayOf(path, pathT, fresh)
            }
            out
        }
    }

    /** Runs [block] with today's net on the engine thread (share cards of the whole map). */
    suspend fun <T> withGraph(block: (com.odomap.core.explore.ExploreGraph) -> T): T {
        pathsReady.first { it }
        return withContext(dispatcher) { block(engine.graph) }
    }

    suspend fun detail(id: String): DriveDetail? =
        withContext(dispatcher) {
            val record = _drives.value.firstOrNull { it.id == id } ?: return@withContext null
            val route = routeOf(id)
            val (walk, _) = driveStore.edges(id) ?: return@withContext DriveDetail(record, EMPTY_FEATURES, route)
            DriveDetail(record, engine.driveLinesGeoJson(walk), route)
        }

    /** a stored drive's simplified path (thumbnails, flags) */
    suspend fun route(id: String): List<Pair<Double, Double>> = withContext(dispatcher) { routeOf(id) }

    private fun routeOf(id: String): List<Pair<Double, Double>> {
        routeCache[id]?.let { return it }
        val r = driveStore.route(id)
        routeCache[id] = r
        return r
    }

    // ---- engine thread ----

    private fun attachLive() {
        if (liveJob != null) return
        val source: EventSource =
            try {
                LiveEventSource(context)
            } catch (t: Throwable) {
                Log.w(TAG, "live source unavailable", t)
                return
            }
        _sourceName.value = source.name
        liveJob =
            scope.launch {
                try {
                    source.events().collect { handle(it) }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(TAG, "live source failed", t)
                }
            }
    }

    private fun handle(e: SourceEvent) {
        when (e) {
            is SourceEvent.DemoBegin -> {
                recent.clear() // a demo drive's clock is unrelated to the previous one's
                pendingTitle = e.title
                pendingSource = "replay:${e.demoId}"
            }
            is SourceEvent.State -> {
                _status.value = e.status
                if (e.status.driving && !engine.isDriving) {
                    startDrive(e.status.t)
                } else if (!e.status.driving && engine.isDriving) {
                    finishDrive(e.status.t)
                }
            }
            is SourceEvent.Loc -> onFix(e.fix)
        }
    }

    private fun onFix(fix: Fix) {
        lastFix = fix
        if (recent.isNotEmpty() && fix.t < recent.last().t) recent.clear() // clock went backwards
        recent.addLast(fix)
        while (recent.isNotEmpty() && fix.t - recent.first().t > RECENT_MS) recent.removeFirst()
        val now = System.nanoTime() / 1_000_000
        if (now - lastPuckAt >= PUCK_MS) {
            lastPuckAt = now
            _puck.value = fix
        }
        if (!engine.isDriving) return
        engine.push(fix)
        fixLog?.append(fix)
        driveFixes.add(fix)
        trackRegion(fix)
        publishLive(now, force = false)
        if (now - lastTailAt >= PUCK_MS) {
            lastTailAt = now
            publishTail()
        }
    }

    private var lastTailAt = 0L

    /** 지역 들어섬: 지금 지역을 알리고, 처음 와 본 곳이면 말한다(출발 지역은 말하지 않는다) */
    private fun trackRegion(fix: Fix) {
        val t = tracker ?: return
        val entry = t.push(fix.t, fix.lat, fix.lng) ?: return
        _region.value = entry.region
        if (entry.firstVisit && !entry.start) speaker.speak(context.getString(R.string.tts_first_visit, entry.region.name))
    }

    /** raw fixes after the settled point (binary search), as one line ending at the latest fix */
    private fun publishTail() {
        if (driveFixes.isEmpty()) {
            _tail.value = null
            return
        }
        var lo = 0
        var hi = driveFixes.size - 1
        val from = settledT - 2_000
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (driveFixes[mid].t < from) lo = mid + 1 else hi = mid
        }
        val start = maxOf(0, lo - 1)
        if (driveFixes.size - start < 2) {
            _tail.value = null
            return
        }
        val sb = StringBuilder(64 + (driveFixes.size - start) * 26)
        sb.append("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"level":1},"geometry":{"type":"LineString","coordinates":[""")
        for (i in start until driveFixes.size) {
            if (i > start) sb.append(',')
            val f = driveFixes[i]
            sb.append('[').append(com.odomap.app.engine.fmt6(f.lng)).append(',').append(com.odomap.app.engine.fmt6(f.lat)).append(']')
        }
        sb.append("]}}]}")
        _tail.value = sb.toString()
        // the head of the list before the settled point is no longer needed
        if (start > 512) driveFixes.subList(0, start - 256).clear()
    }

    private fun publishLive(now: Long, force: Boolean) {
        val lines = force || now - lastLinesAt >= LINES_MS
        if (!lines && now - lastNumbersAt < NUMBERS_MS) return
        lastNumbersAt = now
        val prev = _live.value
        val snap = engine.live(withLines = lines) ?: return
        if (lines) {
            // the tail starts where the drawn lines end — only move it when the lines move (numbers update 4× as often)
            settledT = snap.settledT
            lastLinesAt = now
            _live.value = snap
        } else {
            _live.value = snap.copy(inkGeoJson = prev?.inkGeoJson ?: EMPTY_FEATURES, newGeoJson = prev?.newGeoJson ?: EMPTY_FEATURES)
        }
    }

    private fun startDrive(t: Long) {
        val id = "d" + System.currentTimeMillis()
        val source = pendingSource ?: _sourceName.value
        engine.start(id, t, pendingTitle, source)
        pendingTitle = null
        pendingSource = null
        _arrival.value = null
        driveFixes.clear()
        settledT = t
        fixLog = driveStore.openFixLog(id)
        _region.value = null
        tracker = _regions.value?.let { RegionTracker(it, _visited.value) }
        // fixes that arrived just before the state change (speed rule starts in the past; gear and fixes may race)
        for (fix in recent) if (fix.t >= t && fix.t <= t + RECENT_MS) {
            engine.push(fix)
            fixLog?.append(fix)
            driveFixes.add(fix)
            trackRegion(fix)
        }
        publishLive(System.nanoTime() / 1_000_000, force = true)
    }

    private fun finishDrive(t: Long) {
        lastFix?.let { _puck.value = it } // the throttled puck may lag the last fix (fast replay)
        val id = engine.activeId
        fixLog?.close()
        fixLog = null
        val done: FinishedDrive? = engine.finish(t)
        val tr = tracker
        tracker = null
        _region.value = null
        val finished =
            if (done != null && tr != null) {
                val seq = ArrayList<String>()
                for (e in tr.entries) if (seq.lastOrNull() != e.region.code) seq.add(e.region.code)
                FinishedDrive(done.record.copy(regions = seq, firstVisits = tr.newRegions.map { it.code }), done.walkEdges, done.newEdges, done.path, done.pathT)
            } else {
                done
            }
        _live.value = null
        _tail.value = null
        driveFixes.clear()
        if (finished == null) {
            id?.let { driveStore.discard(it) }
            graphStore.saveDirty(engine.graph)
            return
        }
        driveStore.save(finished.record, finished.walkEdges, finished.newEdges, finished.path, finished.pathT)
        graphStore.saveDirty(engine.graph)
        refreshNet()
        _arrival.value = finished.record
        speaker.speak(arrivalSpeech(finished.record))
    }

    private fun arrivalSpeech(r: DriveRecord): String =
        if (r.pioneeredM >= 50) context.getString(R.string.tts_arrival, formatKm(r.pioneeredM))
        else context.getString(R.string.tts_arrival_none)

    private fun refreshNet() {
        val drives = driveStore.list()
        val tiles = engine.netTiles()
        val regionM = _regions.value?.let { engine.lengthByRegion(it) } ?: emptyMap()
        val visited = LinkedHashSet<String>()
        for (d in drives.sortedBy { it.startedAt }) visited.addAll(d.regions)
        for ((code, m) in regionM) if (m >= 200) visited.add(code)
        // drives, visited and net change together (no frame with 14 drives on an empty map)
        _drives.value = drives
        _visited.value = visited
        _net.value =
            NetState(
                geoJson = engine.netGeoJson(),
                version = _net.value.version + 1,
                netLengthM = engine.netLengthM(),
                bounds = engine.netBounds(),
                driveCount = drives.size,
                drivenM = drives.sumOf { it.distanceM },
                pioneeredM = drives.sumOf { it.pioneeredM },
                loaded = true,
                tiles = tiles,
                dotsGeoJson = tileDotsGeoJson(tiles),
                frequentM = engine.frequentLengthM(),
                regionM = regionM,
            )
    }

    companion object {
        private const val TAG = "DriveRecorder"
        private const val RECENT_MS = 60_000L
        private const val PUCK_MS = 100L
        private const val NUMBERS_MS = 250L
        private const val LINES_MS = 1000L
    }
}
