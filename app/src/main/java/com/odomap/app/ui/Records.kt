package com.odomap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.odomap.app.model.DriveRecord
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.design.BigNumber
import com.odomap.app.ui.design.Fit
import com.odomap.app.ui.design.GridStat
import com.odomap.app.ui.design.HLine
import com.odomap.app.ui.design.Icon
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.RouteThumb
import com.odomap.app.ui.design.SectionTitle
import com.odomap.app.ui.design.StatGrid
import com.odomap.app.ui.design.ToolShadow
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.XYBox
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.circle
import com.odomap.app.ui.design.drawSketch
import com.odomap.app.ui.design.oneLine
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.sketchOfRoute
import com.odomap.app.ui.design.ts
import com.odomap.app.ui.regions.RegionBadge

/** 기록의 누름 */
class RecordsActions(
    val onOpenDrive: (String) -> Unit,
    val onLook: () -> Unit,
    val onShare: () -> Unit,
    val onSettings: () -> Unit,
    val onRegions: () -> Unit,
    val onRegion: (String) -> Unit,
    val onReplayAll: () -> Unit,
    val onMyMap: () -> Unit,
)

private const val SIDE = 20f
private const val SECTION_GAP = 34f

/**
 * 기록(판을 크게 열었을 때 · 넓은 창 왼쪽 판 아래, design.md 4.3) — 판 바탕(surface) 위에 바로 놓는다(박스 안에 박스 금지):
 * 꾸미기·공유·설정 알약 → 내 지도(큰 숫자 · 작은 지도 + ▶ · 2×2) → 이번 주(막대 7) → 지역(배지 선반) → 지난 드라이브(달마다).
 */
@Composable
fun RecordsList(modifier: Modifier, listState: LazyListState, width: Float, data: AppData, recorder: DriveRecorder, actions: RecordsActions) {
    val p = LocalPalette.current
    val inner = width - SIDE * 2
    val months = remember(data.drives, data.now) { data.drives.groupBy { monthKey(it.startedAt) }.toSortedMap(compareByDescending { it }) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = SIDE.dp, end = SIDE.dp, top = 6.dp, bottom = 40.dp)) {
        item(key = "chips") {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(Icons.palette, "꾸미기", Modifier.weight(1f), actions.onLook)
                Chip(Icons.share, "공유", Modifier.weight(1f), actions.onShare)
                Chip(Icons.gear, "설정", Modifier.weight(1f), actions.onSettings)
            }
        }
        item(key = "mymap") { MyMapSection(data, inner, actions) }
        item(key = "week") { WeekSection(data, inner) }
        item(key = "regions") { RegionShelf(data, actions) }
        item(key = "drives-title") {
            Column(Modifier.padding(top = SECTION_GAP.dp)) { SectionTitle("지난 드라이브") }
        }
        if (data.drives.isEmpty()) {
            item(key = "drives-empty") {
                T("시동을 걸고 달리면 여기에 드라이브가 쌓여요.", Type.body, p.textSecondary, Modifier.padding(top = 14.dp), maxLines = 2)
            }
        }
        for ((key, list) in months) {
            item(key = "m$key") {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    T(monthLabel(list.first().startedAt, data.now), Type.monthTitle, p.textPrimary, Modifier.weight(1f))
                    T("${kmLabel(list.sumOf { it.distanceM })} · ${list.size}번", Type.monthTotals, p.textSecondary)
                }
            }
            items(list, key = { it.id }) { d ->
                DriveRow(d, data, recorder, last = d === list.last()) { actions.onOpenDrive(d.id) }
            }
        }
    }
}

/** 판 위 알약(아이콘 + 이름): 높이 44, 무채색 */
@Composable
private fun Chip(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.height(44.dp).card(p.neutralPill, 22f).press(0.96f, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, 18f, p.textPrimary)
        T(label, ts(15f, W.SemiBold, 20f, -0.2f), p.textPrimary)
    }
}

/** 이름 + › (누를 수 있는 머리) */
@Composable
private fun LabelLink(text: String, onClick: (() -> Unit)?) {
    val p = LocalPalette.current
    val m = if (onClick != null) Modifier.press(0.97f, onClick = onClick) else Modifier
    Row(m.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        T(text, ts(15f, W.SemiBold, 20f, -0.2f), p.textSecondary)
        if (onClick != null) Icon(Icons.chevron, 9f, p.textMuted)
    }
}

/** 내 지도: 큰 숫자 42/52/800 · 한 줄 · 작은 지도 + ▶ · 2×2 */
@Composable
private fun MyMapSection(data: AppData, inner: Float, actions: RecordsActions) {
    val p = LocalPalette.current
    val net = data.net
    val drives = data.drives
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        LabelLink("내 지도", actions.onMyMap)
        Spacer(Modifier.height(4.dp))
        BigNumber(kmText(net.netLengthM), "km", Type.totalsBig, Type.totalsUnit, gap = 5f)
        T("드라이브 ${drives.size}번 · 달린 거리 ${kmLabel(drives.sumOf { it.distanceM })}", ts(14f, W.Medium, 19f), p.textSecondary, Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(14.dp))
        // 작은 지도
        val h = (inner * 0.62f).coerceIn(150f, 240f)
        Box(Modifier.fillMaxWidth().height(h.dp).card(p.app, 20f)) {
            val lines = data.sketch
            val box = remember(lines) { XYBox.of(lines)?.atLeast(2e-6) }
            Canvas(Modifier.fillMaxSize()) {
                val b = box ?: return@Canvas
                val pad = 18f * density
                val fit = Fit(b, Rect(pad, pad, size.width - pad, size.height - pad))
                drawSketch(lines, fit, p.ink, 1.1f * density) { level -> 0.85f + 0.03f * level }
            }
            if (lines.isEmpty()) {
                T("달린 길이 여기에 그려져요", ts(14f, W.Medium, 19f), p.textMuted, Modifier.align(Alignment.Center))
            } else {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp).size(40.dp).circle(p.surface, ToolShadow).press(1.08f, onClick = actions.onReplayAll),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.play, 18f, p.textPrimary) }
            }
        }
        Spacer(Modifier.height(6.dp))
        val monthStart = monthKey(data.now)
        val monthFresh = drives.filter { monthKey(it.startedAt) == monthStart }.sumOf { it.pioneeredM }
        val far = drives.maxByOrNull { it.distanceM }
        StatGrid(
            listOf(
                GridStat("fresh", "이번 달 처음 달린 길", kmText(monthFresh), "km"),
                GridStat("visited", "가 본 곳", "${data.visited.size}", "/230"),
                GridStat("time", "달린 시간", durationText(drives.sumOf { it.endedAt - it.startedAt }).takeIf { drives.isNotEmpty() } ?: "-", number = false),
                GridStat("far", "가장 먼 드라이브", far?.let { kmText(it.distanceM) } ?: "-", "km"),
            ),
            inner,
            inset = 0f,
        )
    }
}

/** 이번 주: 큰 숫자(40/48/600) + 막대 7(높이 84, 사이 6, 모서리 4) */
@Composable
private fun WeekSection(data: AppData, inner: Float) {
    val p = LocalPalette.current
    val start = weekStartDay(data.now)
    val today = seoulDay(data.now)
    val week = data.drives.filter { seoulDay(it.startedAt) in start until start + 7 }
    val perDay = DoubleArray(7)
    for (d in week) perDay[(seoulDay(d.startedAt) - start).toInt()] += d.distanceM
    val max = perDay.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.fillMaxWidth().padding(top = SECTION_GAP.dp)) {
        HLine(p.dividerSubtle, Modifier.padding(bottom = SECTION_GAP.dp - 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                LabelLink("이번 주", null)
                Spacer(Modifier.height(4.dp))
                BigNumber(kmText(week.sumOf { it.distanceM }), "km", Type.statsBig, Type.statsUnit, gap = 4f)
                T(oneLine("드라이브 ${week.size}번 · 처음 ${kmLabel(week.sumOf { it.pioneeredM })}"), ts(14f, W.Medium, 19f), p.textSecondary, Modifier.padding(top = 2.dp))
            }
            val barW = 11f
            val gap = 6f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.width((barW * 7 + gap * 6).dp).height(84.dp)) {
                    val bw = barW * density
                    val g = gap * density
                    val r = CornerRadius(bw / 2)
                    for (i in 0 until 7) {
                        val x = i * (bw + g)
                        drawRoundRect(p.surfaceStrong, Offset(x, 0f), Size(bw, size.height), r)
                        if (perDay[i] > 0) {
                            val h = maxOf(bw, (perDay[i] / max * size.height).toFloat())
                            val day = start + i
                            val c = if (day == today) p.textPrimary else p.textMuted
                            drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), r)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                    listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, s ->
                        val isToday = start + i == today
                        T(s, ts(12f, if (isToday) W.Bold else W.Medium, 16f), if (isToday) p.textPrimary else p.textMuted, Modifier.width(barW.dp), align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** 지역 선반(발자취 '동네'): 배지 84 · 칸 96 · 사이 12, 처음 간 차례 */
@Composable
private fun RegionShelf(data: AppData, actions: RecordsActions) {
    val p = LocalPalette.current
    val codes = data.visitedOrdered
    Column(Modifier.fillMaxWidth().padding(top = SECTION_GAP.dp)) {
        HLine(p.dividerSubtle, Modifier.padding(bottom = SECTION_GAP.dp - 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("가 본 곳", Modifier.weight(1f), count = "${codes.size}")
            Row(Modifier.press(0.97f, onClick = actions.onRegions).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                T("전국 지도", ts(15f, W.SemiBold, 20f, -0.2f), p.textSecondary)
                Icon(Icons.chevron, 9f, p.textMuted)
            }
        }
        if (codes.isEmpty()) {
            T("새 시·군·구에 들어서면 여기에 모양이 생겨요.", Type.body, p.textSecondary, Modifier.padding(top = 12.dp), maxLines = 2)
        } else {
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(codes, key = { it }) { code ->
                    Column(Modifier.width(96.dp).press(0.96f) { actions.onRegion(code) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        RegionBadge(data.shapes?.detail(code), data.sketch, Modifier.size(84.dp), fill = p.app)
                        Spacer(Modifier.height(6.dp))
                        T(data.regionName(code), Type.badgeName, p.textPrimary)
                        T(kmLabel(data.net.regionM[code] ?: 0.0), Type.badgeKm, p.textSecondary)
                    }
                }
            }
        }
    }
}

/** 지난 드라이브 한 줄: 썸네일 44 · 이름 16/600 · 날짜·시간·지역 13/500 · 오른쪽 거리 18/600 + 처음 달린 길 */
@Composable
private fun DriveRow(d: DriveRecord, data: AppData, recorder: DriveRecorder, last: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val route by produceState(initialValue = null as com.odomap.app.ui.design.SketchLine?, d.id) { value = sketchOfRoute(recorder.route(d.id)) }
    Column(Modifier.fillMaxWidth().press(0.985f, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteThumb(route, Modifier.size(44.dp).card(p.surfaceStrong, 12f), p.textPrimary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                T(driveName(d.startedAt), Type.rowTitle, p.textPrimary)
                FittingText(driveMetaOptions(d, data), Type.rowMeta, p.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BigNumber(kmText(d.distanceM), "km", Type.rowValue, Type.rowUnit, gap = 2f, animate = false)
                if (d.pioneeredM >= 100) T("+${kmLabel(d.pioneeredM)}", ts(12f, W.SemiBold, 16f), p.textSecondary)
            }
        }
        if (!last) HLine(p.dividerSubtle, Modifier.padding(start = 56.dp))
    }
}

/** 칸에 들어가는 첫 글(넘치면 다음 것으로) — 한 줄, 자르지 않는다 */
@Composable
fun FittingText(options: List<String>, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val text = options.firstOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= maxPx } ?: options.last()
        T(oneLine(text), style, color)
    }
}

/** 메타 후보: 날짜 · 시간 · 출발 → 도착 / 날짜 · 출발 → 도착 / 날짜 · 도착 / 날짜 · 시간 */
fun driveMetaOptions(d: DriveRecord, data: AppData): List<String> {
    val date = dayLabel(d.startedAt)
    val time = durationText(d.endedAt - d.startedAt)
    if (d.regions.isEmpty()) return listOf("$date · $time", date)
    val a = data.regionName(d.regions.first())
    val b = data.regionName(d.regions.last())
    val route = if (a == b) a else "$a → $b"
    return listOf("$date · $time · $route", "$date · $route", "$date · $b", "$date · $time", date)
}

/** '10월 26일 (월) · 38분 · 마포구 → 강남구' */
fun driveMeta(d: DriveRecord, data: AppData): String {
    val parts = arrayListOf(dayLabel(d.startedAt), durationText(d.endedAt - d.startedAt))
    if (d.regions.isNotEmpty()) {
        val a = data.regionName(d.regions.first())
        val b = data.regionName(d.regions.last())
        parts.add(if (a == b) a else "$a → $b")
    }
    return parts.joinToString(" · ")
}

@Suppress("unused")
private val keepColor = Color.Unspecified
