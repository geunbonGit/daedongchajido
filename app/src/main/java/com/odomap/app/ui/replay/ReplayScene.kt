package com.odomap.app.ui.replay

import com.odomap.app.model.DriveRecord
import com.odomap.core.explore.DriveReplay
import com.odomap.core.explore.exploreLineLevel
import com.odomap.core.region.RegionIndex
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

// 다시 보기 계산(발자취 services/explore/exploreGrowth + growthDraw 시간표, design-footprint-ref 6): 드라이브마다 한 단계, 단계 안에서 앞 82%
// 동안 그 드라이브를 달린 차례대로(DriveReplay) 그리고 나머지는 머문다. 선은 한 잉크, 이 재생 안에서 다시 지날 때마다 굵기 단계(지도와 같은
// exploreLineLevel)가 오르면 한 단계 굵게 다시 그린다. 시각 t(ms)만으로 그림이 정해진다(프레임 수와 상관없음). Android 없음 — JVM에서 시험한다.

/** 다시 볼 범위 */
sealed interface ReplayMode {
    data class Drive(val id: String) : ReplayMode

    data class Region(val code: String) : ReplayMode

    data object All : ReplayMode

    companion object {
        fun of(what: String): ReplayMode =
            when {
                what == "all" -> All
                what.startsWith("region:") -> Region(what.removePrefix("region:"))
                else -> Drive(what)
            }
    }
}

object ReplayRules {
    const val STEP_MS = 1100.0
    const val EMPTY_STEP_MS = 250.0
    const val MIN_MS = 3500.0
    const val MAX_MS = 12000.0
    const val LEAD_IN_MS = 300.0
    const val DRAW_SHARE = 0.82
    const val SETTLE_MS = 360.0
    /** 이보다 작은 범위는 이 크기(m)로 보고 맞춘다(차 지도 minSpan) */
    const val MIN_SPAN_M = 400.0
    /** 끝에 전체로 물러나는 시간 */
    const val PULLBACK_MS = 1400.0
    /** 카메라 옮기기 500 + 130·S (800~1400ms), van Wijk ρ */
    const val RHO = 1.6
    /** 새 지역 이름이 날짜 줄에 머무는 시간(다음 단계로 넘어가도 이만큼은 보이고, 지나면 날짜로 돌아간다) */
    const val ENTRY_HOLD_MS = 1600.0
}

/** 월드 좌표(m, 등장방형 — 가운데 위도 lat0 기준, x 동쪽 · y 남쪽) */
class WorldProjection(val lat0: Double, val lng0: Double) {
    val kx = M_PER_DEG * cos(Math.toRadians(lat0))
    val ky = M_PER_DEG

    fun x(lng: Double): Float = ((lng - lng0) * kx).toFloat()

    fun y(lat: Double): Float = (-(lat - lat0) * ky).toFloat()

    companion object {
        const val M_PER_DEG = 6371008.8 * Math.PI / 180
    }
}

/** 월드 상자 */
data class WBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val w: Float get() = maxX - minX
    val h: Float get() = maxY - minY
    val cx: Float get() = (minX + maxX) / 2
    val cy: Float get() = (minY + maxY) / 2

    fun union(o: WBox) = WBox(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))
}

/**
 * 한 단계(드라이브 하나). 선분 i = [x0, y0, x1, y1] at xy[4i]. draw[i] = 그릴 굵기 단계(0 = 그리지 않음 — 이미 같은 굵기로 그린 길을 지남),
 * cum[i] = 선분 i 끝까지 누적 길이(m, 앞머리가 달린 차례). joined[i] = 앞 선분에서 이어진다(같은 굵기로 이어 그릴 수 있다).
 */
class ReplayStep(
    val label: String,
    val xy: FloatArray,
    val draw: ByteArray,
    val cum: FloatArray,
    val joined: BooleanArray,
    val drivenM: Double,
    val freshM: Double,
    val bounds: WBox?,
    /** 처음 들어선 지역: 선분 번호 → 날짜 줄 */
    val entries: List<Pair<Int, String>>,
) {
    val count: Int get() = draw.size
    val length: Float get() = if (cum.isEmpty()) 0f else cum[cum.size - 1]
    val drawable: Boolean = draw.any { it > 0 }

    /** 누적 길이 a에 있는 선분(끝이 a 이상인 첫 선분) */
    fun indexAt(a: Float): Int {
        var lo = 0
        var hi = count - 1
        if (hi < 0) return -1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < a) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 누적 길이 a의 자리 */
    fun pointAt(a: Float): Pair<Float, Float>? {
        val i = indexAt(a)
        if (i < 0) return null
        val start = if (i == 0) 0f else cum[i - 1]
        val len = cum[i] - start
        val f = if (len > 0) ((a - start) / len).coerceIn(0f, 1f) else 1f
        return (xy[4 * i] + (xy[4 * i + 2] - xy[4 * i]) * f) to (xy[4 * i + 1] + (xy[4 * i + 3] - xy[4 * i + 1]) * f)
    }
}

/** 시간표(exploreGrowth buildGrowthTimeline) */
class ReplayTimeline(val starts: DoubleArray, val drawEnds: DoubleArray, val ends: DoubleArray, val totalMs: Double) {
    val n: Int get() = starts.size

    /** t에 그리는 단계. 처음 빈 종이 동안 -1, 끝난 뒤 n */
    fun stepAt(t: Double): Int {
        if (n == 0 || t < starts[0]) return -1
        if (t >= ends[n - 1]) return n
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** k단계를 t에 얼마나 그렸는지(0~1, smoothstep) */
    fun progress(k: Int, t: Double): Double {
        if (k < 0) return 0.0
        if (k >= n) return 1.0
        val span = drawEnds[k] - starts[k]
        val r = if (span > 0) (t - starts[k]) / span else 1.0
        if (r <= 0) return 0.0
        if (r >= 1) return 1.0
        return r * r * (3 - 2 * r)
    }

    companion object {
        fun of(steps: List<ReplayStep>): ReplayTimeline {
            val n = steps.size
            val weights = steps.map { if (it.drawable) ReplayRules.STEP_MS else ReplayRules.EMPTY_STEP_MS }
            val raw = weights.sum()
            val total = if (n == 0) 0.0 else min(ReplayRules.MAX_MS, max(ReplayRules.MIN_MS, raw))
            val scale = if (raw > 0) total / raw else 0.0
            val starts = DoubleArray(n)
            val drawEnds = DoubleArray(n)
            val ends = DoubleArray(n)
            var at = if (n == 0) 0.0 else ReplayRules.LEAD_IN_MS
            for (i in 0 until n) {
                val d = weights[i] * scale
                starts[i] = at
                drawEnds[i] = at + d * ReplayRules.DRAW_SHARE
                at += d
                ends[i] = at
            }
            return ReplayTimeline(starts, drawEnds, ends, if (n == 0) 0.0 else at + ReplayRules.SETTLE_MS)
        }
    }
}

/** 카메라 한 자리: 가운데(월드 m) · 상자 안쪽 폭에 보이는 월드 폭(m) */
data class View(val cx: Double, val cy: Double, val w: Double)

/** 카메라 옮기기 하나: at(ms)부터 from → to (van Wijk 줌·이동, 끝을 부드럽게) */
class Flight(val at: Double, val from: View, val to: View, fixedMs: Double? = null) {
    private val rho = ReplayRules.RHO
    private val rho2 = rho * rho
    private val rho4 = rho2 * rho2
    private val dx = to.cx - from.cx
    private val dy = to.cy - from.cy
    private val d2 = dx * dx + dy * dy
    private val d1 = sqrt(d2)
    private val r0: Double
    private val S: Double

    init {
        if (d2 < 1e-6) {
            r0 = 0.0
            S = ln(max(1e-9, to.w) / max(1e-9, from.w)) / rho
        } else {
            val b0 = (to.w * to.w - from.w * from.w + rho4 * d2) / (2 * from.w * rho2 * d1)
            val b1 = (to.w * to.w - from.w * from.w - rho4 * d2) / (2 * to.w * rho2 * d1)
            r0 = ln(sqrt(b0 * b0 + 1) - b0)
            val r1 = ln(sqrt(b1 * b1 + 1) - b1)
            S = (r1 - r0) / rho
        }
    }

    /** 500 + 130·|S| (800~1400ms) */
    val durationMs: Double = fixedMs ?: (500 + 130 * kotlin.math.abs(S)).coerceIn(800.0, 1400.0)

    fun at(u: Double): View {
        val e = if (u <= 0) 0.0 else if (u >= 1) 1.0 else if (u < 0.5) 4 * u * u * u else 1 - (-2 * u + 2).let { it * it * it } / 2
        if (d2 < 1e-6) return View(from.cx + e * dx, from.cy + e * dy, from.w * exp(rho * e * S))
        val s = e * S
        val coshr0 = cosh(r0)
        val k = from.w / (rho2 * d1) * (coshr0 * tanh(rho * s + r0) - sinh(r0))
        return View(from.cx + k * dx, from.cy + k * dy, from.w * coshr0 / cosh(rho * s + r0))
    }

    fun viewAt(t: Double): View = at((t - at) / durationMs)
}

/** 카메라: 처음 자리 + 옮기기들(시각 순). 옮기는 중에 다음 옮기기가 오면 그 자리에서 다시 떠난다 */
class Camera(val first: View, val flights: List<Flight>) {
    fun at(t: Double): View {
        var v = first
        for (f in flights) {
            if (t < f.at) break
            v = f.viewAt(t)
        }
        return v
    }

    val endMs: Double get() = flights.lastOrNull()?.let { it.at + it.durationMs } ?: 0.0
}

/** 지역 모양(월드 좌표 고리들) */
class ReplayArea(val rings: List<FloatArray>, val bounds: WBox)

class ReplayScene(
    val mode: ReplayMode,
    val steps: List<ReplayStep>,
    val timeline: ReplayTimeline,
    val camera: Camera,
    val projection: WorldProjection,
    val area: ReplayArea?,
    val totalDrivenM: Double,
    val totalFreshM: Double,
    /** 재생이 끝나는 시각(꼬리·물러나기 포함) */
    val endMs: Double,
) {
    /** t까지 오른 달린 길 · 처음 달린 길(m) — 그리는 만큼 보간 */
    fun numbersAt(t: Double): Pair<Double, Double> {
        val k = timeline.stepAt(t)
        if (k < 0) return 0.0 to 0.0
        var driven = 0.0
        var fresh = 0.0
        val upto = min(k, steps.size)
        for (i in 0 until upto) {
            driven += steps[i].drivenM
            fresh += steps[i].freshM
        }
        if (k < steps.size) {
            val f = timeline.progress(k, t)
            driven += steps[k].drivenM * f
            fresh += steps[k].freshM * f
        }
        return driven to fresh
    }

    /** 날짜 줄: 지금 단계의 날짜(새 지역에 막 들어섰으면 '가평군 · 2026년 10월 31일') */
    fun labelAt(t: Double): String {
        if (steps.isEmpty()) return ""
        // finished: the last date (a region name that was just entered gives way to it)
        if (t >= timeline.totalMs) return steps.last().label
        val k = timeline.stepAt(t).coerceIn(0, steps.size - 1)
        // 이번 단계 또는 바로 앞 단계에서 들어선 지역(머무는 시간 안)
        for (j in k downTo max(0, k - 1)) {
            val s = steps[j]
            if (s.entries.isEmpty() || s.length <= 0f) continue
            val f = timeline.progress(j, t)
            val a = (f * s.length).toFloat()
            var hit: Pair<Int, String>? = null
            for (e in s.entries) if (e.first == 0 || s.cum[e.first - 1] <= a) hit = e
            if (hit != null) {
                val enteredAt = timeAtLength(j, if (hit.first == 0) 0f else s.cum[hit.first - 1])
                if (t - enteredAt < ReplayRules.ENTRY_HOLD_MS) return hit.second
            }
        }
        return steps[k].label
    }

    /** k단계에서 누적 길이 a에 앞머리가 닿는 시각(smoothstep의 역) */
    private fun timeAtLength(k: Int, a: Float): Double {
        val s = steps[k]
        val f = if (s.length > 0) (a / s.length).toDouble().coerceIn(0.0, 1.0) else 0.0
        // smoothstep 역함수: r = 0.5 − sin(asin(1 − 2f)/3)
        val r = 0.5 - kotlin.math.sin(kotlin.math.asin(1 - 2 * f) / 3)
        return timeline.starts[k] + r * (timeline.drawEnds[k] - timeline.starts[k])
    }

    companion object {
        /**
         * Builds the scene. [records] = the drives in this replay (any order — sorted by start), [replays] by id, [prior] = drives before them
         * whose passes count first (single drive: the levels the roads had before it), [area] = region outline rings in [lat0, lng0, …] degrees.
         */
        fun build(
            mode: ReplayMode,
            records: List<DriveRecord>,
            replays: Map<String, DriveReplay>,
            prior: List<DriveReplay>,
            index: RegionIndex?,
            areaRings: List<DoubleArray>?,
            boxW: Double,
            boxH: Double,
        ): ReplayScene {
            val drives = records.sortedBy { it.startedAt }.filter { replays[it.id]?.segments?.isNotEmpty() == true }
            // projection centre: everything in the replay (and the region)
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLng = Double.MAX_VALUE
            var maxLng = -Double.MAX_VALUE
            fun see(lat: Double, lng: Double) {
                minLat = min(minLat, lat); maxLat = max(maxLat, lat); minLng = min(minLng, lng); maxLng = max(maxLng, lng)
            }
            for (d in drives) for (s in replays.getValue(d.id).segments) {
                see(s.fromLat, s.fromLng)
                see(s.toLat, s.toLng)
            }
            areaRings?.forEach { r -> var k = 0; while (k + 1 < r.size) { see(r[k], r[k + 1]); k += 2 } }
            val proj = if (minLat <= maxLat) WorldProjection((minLat + maxLat) / 2, (minLng + maxLng) / 2) else WorldProjection(37.5, 127.0)

            val area =
                areaRings?.takeIf { it.isNotEmpty() }?.let { rings ->
                    var b: WBox? = null
                    val out = rings.map { r ->
                        val xy = FloatArray(r.size)
                        var k = 0
                        while (k + 1 < r.size) {
                            xy[k] = proj.x(r[k + 1])
                            xy[k + 1] = proj.y(r[k])
                            val p = WBox(xy[k], xy[k + 1], xy[k], xy[k + 1])
                            b = b?.union(p) ?: p
                            k += 2
                        }
                        xy
                    }
                    b?.let { ReplayArea(out, it) }
                }

            val regionCode = (mode as? ReplayMode.Region)?.code
            val visits = HashMap<Long, Int>()
            for (r in prior) for (s in r.segments) if (s.counted) visits[s.key] = (visits[s.key] ?: 0) + 1
            val drawnLevel = HashMap<Long, Int>()
            val seenRegions = HashSet<String>()
            val steps = ArrayList<ReplayStep>()
            for (d in drives) {
                val segs = replays.getValue(d.id).segments
                val xy = ArrayList<Float>(segs.size * 4)
                val draw = ArrayList<Byte>(segs.size)
                val cum = ArrayList<Float>(segs.size)
                val joined = ArrayList<Boolean>(segs.size)
                val entries = ArrayList<Pair<Int, String>>()
                var along = 0f
                var driven = 0.0
                var fresh = 0.0
                var b: WBox? = null
                var prevTo = -1
                var prevLevel = 0
                var lastRegion: String? = null
                val firsts = d.firstVisits.toSet()
                val dateText = longDate(d.startedAt)
                for (s in segs) {
                    val midLat = (s.fromLat + s.toLat) / 2
                    val midLng = (s.fromLng + s.toLng) / 2
                    val region = if (index != null && (regionCode != null || mode !is ReplayMode.Region)) index.regionAt(midLat, midLng) else null
                    if (regionCode != null && region?.code != regionCode) {
                        prevTo = -1
                        continue
                    }
                    val x0 = proj.x(s.fromLng)
                    val y0 = proj.y(s.fromLat)
                    val x1 = proj.x(s.toLng)
                    val y1 = proj.y(s.toLat)
                    val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                    // levels counted inside this replay (+ prior drives)
                    val before = visits[s.key] ?: 0
                    val after = if (s.counted) before + 1 else max(1, before)
                    visits[s.key] = after
                    val level = exploreLineLevel(max(1, after))
                    val had = drawnLevel[s.key] ?: 0
                    val lv = if (level > had) level else 0
                    if (lv > 0) drawnLevel[s.key] = lv
                    // first entry into a region (whole map: first time in the replay · one drive: its first visits)
                    if (region != null && region.code != lastRegion && mode !is ReplayMode.Region) {
                        lastRegion = region.code
                        val first = if (mode is ReplayMode.All) seenRegions.add(region.code) else region.code in firsts && seenRegions.add(region.code)
                        if (first) entries.add(draw.size to "${region.name} · $dateText")
                    }
                    joined.add(!s.lift && s.from == prevTo && lv == prevLevel && lv > 0)
                    xy.add(x0); xy.add(y0); xy.add(x1); xy.add(y1)
                    draw.add(lv.toByte())
                    along += len
                    cum.add(along)
                    driven += len
                    if (s.pioneered) fresh += len
                    val sb = WBox(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
                    b = b?.union(sb) ?: sb
                    prevTo = s.to
                    prevLevel = lv
                }
                if (draw.isEmpty()) continue
                val label =
                    when (mode) {
                        is ReplayMode.Drive -> "${com.odomap.app.ui.driveName(d.startedAt)} · $dateText"
                        is ReplayMode.Region -> "${index?.byCode(mode.code)?.name ?: ""} · $dateText"
                        ReplayMode.All -> dateText
                    }
                // numbers: a whole drive counts its stored numbers (the drive screen's), a region its length inside
                val scaleDriven = if (regionCode == null && driven > 0) d.distanceM / driven else 1.0
                val scaleFresh = if (regionCode == null && fresh > 0) d.pioneeredM / fresh else 1.0
                steps.add(
                    ReplayStep(
                        label, xy.toFloatArray(), draw.toByteArray(), cum.toFloatArray(), joined.toBooleanArray(),
                        driven * scaleDriven, fresh * scaleFresh, b, entries,
                    ),
                )
            }
            val tl = ReplayTimeline.of(steps)
            val camera = cameraOf(mode, steps, area, tl, boxW, boxH)
            val endMs = max(tl.totalMs, camera.endMs)
            return ReplayScene(mode, steps, tl, camera, proj, area, steps.sumOf { it.drivenM }, steps.sumOf { it.freshM }, endMs)
        }

        private fun fit(b: WBox, boxW: Double, boxH: Double): View {
            val spanX = max(b.w.toDouble(), ReplayRules.MIN_SPAN_M)
            val spanY = max(b.h.toDouble(), ReplayRules.MIN_SPAN_M)
            val s = min(boxW / spanX, boxH / spanY)
            return View(b.cx.toDouble(), b.cy.toDouble(), boxW / s)
        }

        /** b fits inside v (with the box aspect) */
        private fun contains(v: View, b: WBox, boxW: Double, boxH: Double): Boolean {
            val hw = v.w / 2
            val hh = v.w * boxH / boxW / 2
            return b.minX >= v.cx - hw && b.maxX <= v.cx + hw && b.minY >= v.cy - hh && b.maxY <= v.cy + hh
        }

        /**
         * 한 드라이브·한 지역은 한 자리. 내 지도 전체: 드라이브마다 그 드라이브 범위로 — 지금 화면 안에 들고 너무 작지 않으면(1/3 폭 이상)
         * 그대로, 아니면 그 자리로 날아간다. 끝에 전체로 물러난다(1400ms).
         */
        private fun cameraOf(mode: ReplayMode, steps: List<ReplayStep>, area: ReplayArea?, tl: ReplayTimeline, boxW: Double, boxH: Double): Camera {
            val all = (steps.mapNotNull { it.bounds } + listOfNotNull(area?.bounds)).reduceOrNull { a, b -> a.union(b) } ?: WBox(-500f, -500f, 500f, 500f)
            if (mode !is ReplayMode.All || steps.size < 2) {
                val b = if (mode is ReplayMode.Region && area != null) area.bounds else all
                return Camera(fit(b, boxW, boxH), emptyList())
            }
            val first = fit(steps[0].bounds ?: all, boxW, boxH)
            val flights = ArrayList<Flight>()
            var current = first
            fun goTo(at: Double, to: View, fixedMs: Double? = null) {
                val from = Camera(first, flights).at(at)
                flights.add(Flight(at, from, to, fixedMs))
                current = to
            }
            for (i in 1 until steps.size) {
                val b = steps[i].bounds ?: continue
                val want = fit(b, boxW, boxH)
                if (contains(current, b, boxW, boxH) && want.w >= current.w / 3) continue
                goTo(tl.starts[i], want)
            }
            val whole = fit(all, boxW, boxH)
            if (kotlin.math.abs(whole.w - current.w) > current.w * 0.02 || hypot(whole.cx - current.cx, whole.cy - current.cy) > current.w * 0.02) {
                goTo(tl.ends[steps.size - 1], whole, ReplayRules.PULLBACK_MS)
            }
            return Camera(first, flights)
        }

        /** "2026년 10월 31일" */
        fun longDate(ms: Long): String = com.odomap.app.ui.fullDate(ms)
    }
}
