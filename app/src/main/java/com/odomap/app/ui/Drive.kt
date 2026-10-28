package com.odomap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.odomap.app.record.DriveDetail
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.design.BigNumber
import com.odomap.app.ui.design.BlackPill
import com.odomap.app.ui.design.Card
import com.odomap.app.ui.design.GridStat
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.LocalToast
import com.odomap.app.ui.design.NavRow
import com.odomap.app.ui.design.NeutralPill
import com.odomap.app.ui.design.StatGrid
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.TextButton
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.WIDE_MIN_PT
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.ts
import com.odomap.app.ui.map.Insets
import com.odomap.app.ui.map.MapContent
import com.odomap.app.ui.map.MapController
import com.odomap.app.ui.map.MapFlag
import com.odomap.app.ui.map.NetTone
import com.odomap.app.ui.map.OdoMap

/** 넓은 창에서 오른쪽 숫자 칸 폭 */
private const val DETAIL_COL = 380f

/**
 * 드라이브 화면(발자취 '산책 결과', design.md 4.4): 지도 카드(둥근 24, 이 드라이브만 진하게 + 출발·도착 깃발) → 큰 숫자 '+12.4km 처음 달린 길'
 * → 2×2 칸 → 첫 방문 꼬리표 → 공유하기 → 다시 보기 → 기록 지우기. 넓은 창은 지도 왼쪽 · 숫자 오른쪽.
 */
@Composable
fun DriveScreen(id: String, recorder: DriveRecorder, data: AppData, back: () -> Unit, open: (Screen) -> Unit) {
    val record = data.drives.firstOrNull { it.id == id }
    val detail by produceState(initialValue = null as DriveDetail?, id, data.net.version) { value = recorder.detail(id) }
    SubScreen(title = record?.let { driveName(it.startedAt) }, sub = record?.let { dayLabel(it.startedAt) }, onClose = back) { top, bottom ->
        if (record == null) return@SubScreen
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth.value >= WIDE_MIN_PT
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 20f).dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    DriveMapCard(detail, data, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(DETAIL_COL.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        DriveNumbers(record, data, DETAIL_COL, recorder, back, open)
                    }
                }
            } else {
                val w = maxWidth.value - SUB_SIDE * 2
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 28f).dp)) {
                    DriveMapCard(detail, data, Modifier.fillMaxWidth().height((w * 0.78f).dp))
                    Spacer(Modifier.height(22.dp))
                    DriveNumbers(record, data, w, recorder, back, open)
                }
            }
        }
    }
}

/** 지도 카드: 종이 지도, 내 지도는 옅게 · 이 드라이브만 진하게, 출발·도착 깃발 */
@Composable
private fun DriveMapCard(detail: DriveDetail?, data: AppData, modifier: Modifier) {
    val p = LocalPalette.current
    val controller = remember { MapController() }
    val d = detail
    LaunchedEffect(d?.record?.id, d?.record?.bounds) {
        val b = d?.record?.bounds ?: return@LaunchedEffect
        controller.fit(b, Insets(36f, 48f, 36f, 36f), animate = false)
    }
    Box(modifier.card(p.paper, 24f).clip(RoundedCornerShape(24.dp))) {
        OdoMap(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            content =
                MapContent(
                    net = data.net.geoJson,
                    netVersion = data.net.version,
                    netTone = NetTone.PAST,
                    drive = d?.linesGeoJson,
                    dots = data.net.dotsGeoJson,
                    flags = d?.route?.let { r -> if (r.size >= 2) listOf(MapFlag(r.first().first, r.first().second, false), MapFlag(r.last().first, r.last().second, true)) else emptyList() } ?: emptyList(),
                ),
            initialGoogleZoom = 12.0,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveNumbers(record: com.odomap.app.model.DriveRecord, data: AppData, width: Float, recorder: DriveRecorder, back: () -> Unit, open: (Screen) -> Unit) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    var confirm by remember { mutableStateOf(false) }
    val fresh = record.pioneeredM >= 10
    Column(Modifier.fillMaxWidth()) {
        // 큰 숫자
        BigNumber(
            if (fresh) "+" + kmText(record.pioneeredM) else kmText(record.distanceM),
            "km", Type.resultBig, Type.resultUnit, gap = 4f, animate = false,
        )
        T(if (fresh) "처음 달린 길" else "달린 길", Type.resultLabel, p.textSecondary, Modifier.padding(top = 2.dp))
        if (record.regions.isNotEmpty()) {
            // 지난 지역: 출발 → 도착 · 지난 수(한 줄 — 이름 중간에서 줄이 나뉘지 않게)
            val a = data.regionName(record.regions.first())
            val b = data.regionName(record.regions.last())
            val passed = record.regions.toSet().size
            val route = if (a == b) a else "$a → $b"
            T(com.odomap.app.ui.design.oneLine(if (passed > 2) "$route · ${passed}곳 지남" else route), ts(15f, W.Medium, 21f), p.textPrimary, Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(18.dp))
        Card {
            val minutes = (record.endedAt - record.startedAt) / 60_000.0
            val avg = if (minutes > 0.5) record.distanceM / 1000 / (minutes / 60) else 0.0
            StatGrid(
                listOf(
                    GridStat("driven", "달린 길", kmText(record.distanceM), "km"),
                    GridStat("time", "시간", durationText(record.endedAt - record.startedAt), number = false),
                    GridStat("speed", "평균 속력", if (avg > 0) "${Math.round(avg)}" else "-", "km/h"),
                    GridStat("first", "첫 방문 지역", "${record.firstVisits.size}", "곳"),
                ),
                width,
            )
        }
        if (record.firstVisits.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = if (record.firstVisits.size > 4) record.firstVisits.take(3) else record.firstVisits
                val tag: @Composable (String) -> Unit = { text ->
                    Row(Modifier.height(34.dp).card(p.blue.copy(alpha = if (p.dark) 0.18f else 0.10f), 17f).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(text, ts(14f, W.SemiBold, 18f, -0.1f), p.blue)
                    }
                }
                for (code in shown) tag("${data.regionName(code)} 첫 방문")
                if (shown.size < record.firstVisits.size) tag("외 ${record.firstVisits.size - shown.size}곳")
            }
        }
        Spacer(Modifier.height(22.dp))
        BlackPill("공유하기", Icons.share, Modifier.fillMaxWidth()) { open(Screen.Share(record.id)) }
        Spacer(Modifier.height(10.dp))
        NavRow(Icons.play, "다시 보기", iconSize = 16f) { open(Screen.Replay(record.id)) }
        Spacer(Modifier.height(18.dp))
        if (!confirm) {
            TextButton("기록 지우기", p.danger, Modifier.align(Alignment.CenterHorizontally), style = ts(15f, W.Medium, 20f)) { confirm = true }
        } else {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("이 드라이브를 지울까요?", Type.rowTitle, p.textPrimary)
                    T("내 지도에서도 이 길이 빠지고, 다른 드라이브의 처음 달린 길을 다시 셈해요.", Type.rowMeta, p.textSecondary, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeutralPill(Modifier.weight(1f), onClick = { confirm = false }) { T("취소", Type.pill, p.textPrimary) }
                        NeutralPill(Modifier.weight(1f), onClick = {
                            confirm = false
                            recorder.deleteDrive(record.id) { toast.show("드라이브를 지웠어요") }
                            back()
                        }) { T("지우기", Type.pill, p.danger) }
                    }
                }
            }
        }
    }
}
