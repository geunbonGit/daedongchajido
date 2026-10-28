package com.odomap.app.ui.regions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.odomap.app.navi.Navi
import com.odomap.app.ui.AppData
import com.odomap.app.ui.SUB_SIDE
import com.odomap.app.ui.Screen
import com.odomap.app.ui.SubScreen
import com.odomap.app.ui.design.BigNumber
import com.odomap.app.ui.design.BlackPill
import com.odomap.app.ui.design.Card
import com.odomap.app.ui.design.Fit
import com.odomap.app.ui.design.HLine
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.LocalToast
import com.odomap.app.ui.design.SheetStat
import com.odomap.app.ui.design.StatRow
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.WIDE_MIN_PT
import com.odomap.app.ui.design.XYBox
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.drawSketch
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.ts
import com.odomap.app.ui.fullDate
import com.odomap.app.ui.kmText
import com.odomap.app.ui.map.HOME_LAT
import com.odomap.app.ui.map.HOME_LNG
import com.odomap.core.region.Region
import com.odomap.core.region.RegionDistance

const val REGION_CREDIT = "지역 경계 © 통계청 SGIS(CC BY 4.0)"

/** '경기 가평군' → '경기' */
fun sidoShort(r: Region): String = r.label.substringBefore(' ')

// ───────────────────────── 전국 지도

/**
 * 전국 지도(발자취 '동네' 묶음, design.md 4.6): 큰 숫자 23 /230곳 · 시·군·구 모양(가 본 곳 = 채움 + 내 길, 안 가 본 곳 = 테두리만) ·
 * 시·도별 줄(이름 · 6/25 · 가는 막대). 모양을 누르면 그 지역.
 */
@Composable
fun RegionsScreen(data: AppData, back: () -> Unit, open: (Screen) -> Unit) {
    SubScreen(title = "가 본 곳", onClose = back) { top, bottom ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth.value >= WIDE_MIN_PT
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 20f).dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    NationalMap(data, Modifier.weight(1f).fillMaxHeight()) { open(Screen.Region(it)) }
                    Column(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        RegionsHead(data)
                        Spacer(Modifier.height(16.dp))
                        com.odomap.app.ui.design.NavRow(Icons.share, "가 본 곳 공유하기", iconSize = 18f) { open(Screen.Share("regions")) }
                        Spacer(Modifier.height(18.dp))
                        SidoList(data)
                        Credit()
                    }
                }
            } else {
                val w = maxWidth.value - SUB_SIDE * 2
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 28f).dp)) {
                    RegionsHead(data)
                    Spacer(Modifier.height(16.dp))
                    NationalMap(data, Modifier.fillMaxWidth().height((w * 1.05f).dp)) { open(Screen.Region(it)) }
                    Spacer(Modifier.height(14.dp))
                    SidoList(data)
                    Spacer(Modifier.height(14.dp))
                    com.odomap.app.ui.design.NavRow(Icons.share, "가 본 곳 공유하기", iconSize = 18f) { open(Screen.Share("regions")) }
                    Credit()
                }
            }
        }
    }
}

@Composable
private fun RegionsHead(data: AppData) {
    val p = LocalPalette.current
    BigNumber("${data.visited.size}", "/230곳", Type.totalsBig, Type.totalsUnit, gap = 5f, animate = false)
    T("가 본 시·군·구", Type.totalsLabel, p.textSecondary, Modifier.padding(top = 2.dp))
}

@Composable
private fun Credit() {
    val p = LocalPalette.current
    T(REGION_CREDIT, Type.credit, p.textMuted, Modifier.fillMaxWidth().padding(top = 14.dp), align = TextAlign.Center)
}

/** 시·군·구 모양 지도(가 본 곳 둘레에 맞춘다 — 최소 3° 폭) */
@Composable
private fun NationalMap(data: AppData, modifier: Modifier, onRegion: (String) -> Unit) {
    val p = LocalPalette.current
    val shapes = data.shapes
    val view =
        remember(shapes, data.visited) {
            if (shapes == null) return@remember null
            val boxes = data.visited.mapNotNull { shapes.regions[it]?.box }
            if (boxes.isEmpty()) {
                shapes.all
            } else {
                val u = boxes.reduce { a, b -> a.union(b) }
                val span = (2.2 / 360.0)
                val grown = XYBox(u.minX - u.w * 0.2, u.minY - u.h * 0.2, u.maxX + u.w * 0.2, u.maxY + u.h * 0.2).atLeast(span)
                grown
            }
        }
    Box(
        modifier.card(p.paper, 24f).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).pointerInput(shapes, view) {
            detectTapGestures { at ->
                val s = shapes ?: return@detectTapGestures
                val v = view ?: return@detectTapGestures
                val pad = 16f * density
                val fit = Fit(v, Rect(pad, pad, size.width - pad, size.height - pad))
                val mx = (at.x - fit.x(0.0)) / fit.scale
                val my = (at.y - fit.y(0.0)) / fit.scale
                val hit = s.regions.entries.firstOrNull { it.value.contains(mx, my) }?.key
                if (hit != null) onRegion(hit)
            }
        },
    ) {
        Canvas(
            Modifier.fillMaxSize().drawWithCache {
                val s = shapes
                val v = view
                if (s == null || v == null) return@drawWithCache onDrawBehind {}
                val pad = 16f * density
                val fit = Fit(v, Rect(pad, pad, size.width - pad, size.height - pad))
                val visited = Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd }
                val others = Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd }
                for ((code, shape) in s.regions) {
                    val target = if (code in data.visited) visited else others
                    for (r in shape.rings) {
                        fit.path(r, target)
                        target.close()
                    }
                }
                val sido = Path()
                for (shape in s.sido.values) for (r in shape.rings) {
                    fit.path(r, sido)
                    sido.close()
                }
                onDrawBehind {
                    drawPath(others, p.ink.copy(alpha = 0.18f), style = Stroke(0.5f * density, join = StrokeJoin.Round))
                    drawPath(sido, p.ink.copy(alpha = 0.28f), style = Stroke(0.9f * density, join = StrokeJoin.Round))
                    drawPath(visited, p.surface)
                    drawPath(visited, p.ink.copy(alpha = 0.38f), style = Stroke(0.8f * density, join = StrokeJoin.Round))
                    drawSketch(data.sketch, fit, p.ink, 0.9f * density, levelStep = 0.15f)
                }
            },
        ) {}
    }
}

/** 시·도별 줄: 이름 · 가 본 수/전체 · 가는 막대 */
@Composable
private fun SidoList(data: AppData) {
    val p = LocalPalette.current
    val index = data.index ?: return
    val groups = remember(index, data.visited) {
        index.regions.groupBy { sidoShort(it) }.map { (name, list) -> Triple(name, list.count { it.code in data.visited }, list.size) }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { t -> index.regions.first { r -> sidoShort(r) == t.first }.sidoCode })
    }
    Card {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            groups.forEachIndexed { i, (name, seen, total) ->
                if (i > 0) HLine(p.dividerSubtle)
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(name, Type.rowTitle, if (seen > 0) p.textPrimary else p.textSecondary, Modifier.width(56.dp))
                    Box(Modifier.weight(1f).height(6.dp).card(p.surfaceStrong, 3f)) {
                        if (seen > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(seen.toFloat() / total).card(p.ink, 3f))
                    }
                    Spacer(Modifier.width(14.dp))
                    Row(Modifier.width(64.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                        T("$seen", ts(17f, W.Bold, 22f, -0.3f), if (seen > 0) p.textPrimary else p.textMuted)
                        T("/$total", ts(13f, W.Medium, 18f), p.textMuted, Modifier.padding(bottom = 1.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────── 지역 하나

/** 지역 하나(발자취 '동네 하나', design.md 4.6): 회색 시·도 + 큰 이름, 모양 안 내 길, 숫자 셋 카드, 검은 알약 '다시 보기' */
@Composable
fun RegionScreen(code: String, data: AppData, back: () -> Unit, open: (Screen) -> Unit) {
    val p = LocalPalette.current
    val region = data.region(code)
    SubScreen(onClose = back) { top, bottom ->
        if (region == null) return@SubScreen
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth.value >= WIDE_MIN_PT
            val context = LocalContext.current
            val rough = data.shapes?.detail(code)
            val fine by androidx.compose.runtime.produceState(initialValue = null as Shape?, code) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RegionShapes.precise(context, code) }
            }
            val shape = fine ?: rough
            val visited = code in data.visited
            val inside = remember(shape, data.sketch) { if (shape == null) emptyList() else data.sketch.filter { shape.box.meets(it) } }
            val drawing: @Composable (Modifier) -> Unit = { m ->
                Canvas(m) {
                    val s = shape ?: return@Canvas
                    val pad = 12f * density
                    drawRegionBadge(s, inside, p, p.surface, Rect(pad, pad, size.width - pad, size.height - pad), lineBase = 1.5f, borderWidth = 1.4f)
                }
            }
            val panelW = if (wide) 380f else maxWidth.value - SUB_SIDE * 2
            val info: @Composable () -> Unit = {
                T(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = p.textMuted)) { append(sidoShort(region) + " ") }
                        withStyle(SpanStyle(color = p.textPrimary)) { append(region.name) }
                    },
                    Type.regionTitle, p.textPrimary,
                )
                Spacer(Modifier.height(18.dp))
                Card {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        val first = data.regionFirst[code]
                        StatRow(
                            listOf(
                                SheetStat("km", "달린 길", kmText(data.net.regionM[code] ?: 0.0), "km"),
                                SheetStat("drives", "드라이브", "${data.regionDrives[code] ?: 0}", "번"),
                                SheetStat("first", "처음 간 날", first?.let { shortDate(it) } ?: "-", number = false),
                            ),
                            panelW - 40f,
                            animate = false,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (visited) {
                    BlackPill("다시 보기", Icons.play, Modifier.fillMaxWidth()) { open(Screen.Replay("region:$code")) }
                } else {
                    NaviPill(region, data, Modifier.fillMaxWidth())
                }
                Credit()
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 20f).dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        drawing(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight())
                    }
                    Column(Modifier.width(panelW.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { info() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 28f).dp)) {
                    drawing(Modifier.fillMaxWidth().height((panelW * 0.9f).dp))
                    Spacer(Modifier.height(16.dp))
                    info()
                }
            }
        }
    }
}

/** '7월 10일' */
private fun shortDate(ms: Long): String = fullDate(ms).substringAfter("년 ")

// ───────────────────────── 안 가 본 동네로

/** 안 가 본 동네로(design.md 4.7): 가까운 안 가 본 시·군·구 5곳 — 배지 · 이름 · '경기도 · 52km · 북동쪽' · '길 안내' */
@Composable
fun UnvisitedScreen(data: AppData, back: () -> Unit) {
    val p = LocalPalette.current
    val here = data.here() ?: (HOME_LAT to HOME_LNG)
    val list: List<RegionDistance> = remember(data.index, data.visited, here.first.toInt(), here.second.toInt()) {
        data.index?.nearestUnvisited(here.first, here.second, data.visited, limit = 5) ?: emptyList()
    }
    SubScreen(title = "안 가 본 동네로", onClose = back) { top, bottom ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 28f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                T("가까운 안 가 본 곳", Type.screenTitle, p.textPrimary)
                T("여기서 가까운 차례예요. 길 안내를 누르면 내비가 데려가요.", ts(15f, W.Medium, 21f), p.textSecondary, Modifier.padding(top = 6.dp), maxLines = 2)
                Spacer(Modifier.height(18.dp))
                if (list.isEmpty()) {
                    T(if (data.index == null) "지역 경계를 읽는 중이에요" else "가까운 곳은 모두 가 봤어요", Type.body, p.textSecondary)
                } else {
                    Card {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            list.forEachIndexed { i, rd ->
                                if (i > 0) HLine(p.dividerSubtle, Modifier.padding(start = 68.dp))
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RegionBadge(data.shapes?.detail(rd.region.code), emptyList(), Modifier.size(56.dp), fill = p.app, pad = 3f)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        T(rd.region.name, Type.rowTitle, p.textPrimary)
                                        T("${rd.region.sido} · ${Math.round(rd.distanceKm)}km · ${rd.direction}쪽", Type.rowMeta, p.textSecondary)
                                    }
                                    NaviPill(rd.region, data, Modifier, small = true)
                                }
                            }
                        }
                    }
                }
                Credit()
            }
        }
    }
}

/** '길 안내' — Pleos 내비에 지역 대표 지점을 보낸다 */
@Composable
private fun NaviPill(region: Region, data: AppData, modifier: Modifier, small: Boolean = false) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val navi = remember { Navi(context) }
    androidx.compose.runtime.DisposableEffect(navi) { onDispose { navi.release() } }
    val go = {
        navi.route(
            region.centerLat, region.centerLng, region.name, region.full,
            onResult = { ok -> toast.show(if (ok) "${region.name}(으)로 길 안내를 부탁했어요" else "내비게이션을 찾을 수 없어요") },
            onFail = { msg -> toast.show(msg) },
        )
        Unit
    }
    if (small) {
        Box(modifier.height(40.dp).card(p.ink, 20f).press(0.95f, onClick = go).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            T("길 안내", ts(15f, W.SemiBold, 20f, -0.2f), p.textInverse)
        }
    } else {
        BlackPill("길 안내", Icons.flag, modifier, onClick = go)
    }
    @Suppress("UNUSED_EXPRESSION") data
}
