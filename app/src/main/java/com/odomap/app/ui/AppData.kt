package com.odomap.app.ui

import androidx.compose.runtime.Immutable
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.Fix
import com.odomap.app.record.NetState
import com.odomap.app.ui.design.SketchLine
import com.odomap.app.ui.regions.RegionShapes
import com.odomap.core.region.Region
import com.odomap.core.region.RegionIndex

/** 하위 화면·기록이 함께 읽는 것(값이 바뀔 때만 다시 그린다) */
@Immutable
class AppData(
    val drives: List<DriveRecord>,
    val net: NetState,
    val now: Long,
    val index: RegionIndex?,
    /** 가 본 시·군·구 코드(처음 간 차례) */
    val visited: Set<String>,
    val shapes: RegionShapes?,
    /** 내 지도 줄(지도 없이 그리는 곳) */
    val sketch: List<SketchLine>,
    val puck: Fix?,
) {
    fun region(code: String): Region? = index?.byCode(code)

    fun regionName(code: String): String = index?.byCode(code)?.name ?: code

    /** 지역마다 처음 간 날(ms)·드라이브 수 */
    val regionFirst: Map<String, Long> by lazy {
        val m = HashMap<String, Long>()
        for (d in drives) for (c in d.regions) m[c] = minOf(m[c] ?: Long.MAX_VALUE, d.startedAt)
        m
    }
    val regionDrives: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (d in drives) for (c in d.regions.toSet()) m[c] = (m[c] ?: 0) + 1
        m
    }

    /** 처음 간 차례(드라이브 기록 순) — 기록이 없고 내 길만 지나는 곳은 뒤에 */
    val visitedOrdered: List<String> by lazy {
        val out = LinkedHashSet<String>()
        for (d in drives.sortedBy { it.startedAt }) out.addAll(d.regions)
        out.addAll(visited)
        out.filter { it in visited }
    }

    /** 지금(또는 마지막) 자리: 내 점 → 마지막 드라이브 끝 → null */
    fun here(): Pair<Double, Double>? =
        listOfNotNull(
            puck?.let { it.lat to it.lng },
            drives.firstOrNull()?.bounds?.let { (it.minLat + it.maxLat) / 2 to (it.minLng + it.maxLng) / 2 },
        ).firstOrNull { (lat, lng) -> inKorea(lat, lng) }

    /** 남한 둘레(지역 경계가 있는 곳) — 밖이면(에뮬레이터 기본 위치 등) 다음 후보로 */
    private fun inKorea(lat: Double, lng: Double): Boolean = lat in 33.0..38.7 && lng in 124.5..131.0
}
