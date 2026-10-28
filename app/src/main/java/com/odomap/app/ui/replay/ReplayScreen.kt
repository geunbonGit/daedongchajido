package com.odomap.app.ui.replay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.odomap.app.R
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.AppData
import com.odomap.app.ui.SUB_SIDE
import com.odomap.app.ui.Screen
import com.odomap.app.ui.SubScreen
import com.odomap.app.ui.design.Icon
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.Shadow
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.WIDE_MIN_PT
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.ts
import com.odomap.app.ui.driveName
import com.odomap.app.ui.map.xToLng
import com.odomap.app.ui.map.yToLat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 판 그림자(발자취 0 2 20 @0.1) · 모서리 */
private val BOARD_SHADOW = Shadow(2f, 10f, 0.1f)
private const val BOARD_CORNER = 20f
private const val BUTTON_H = 48f

/**
 * 다시 보기(design.md 4.9, 발자취 다시 보기 · growthDraw): 9:16 판(360×640pt를 창 높이에 맞춰)을 가운데, 넓은 창은 옆에 범위·단추.
 * 드라이브 하나 / 지역 하나(지역 모양 한 겹) / 내 지도 전체(날짜 순, 먼 곳은 카메라가 날아간다). 아래 '다시 재생' · '공유하기'.
 * 판을 누르면 멈춤·이어 재생(끝났으면 처음부터). 정차 중에만(여는 쪽이 막는다).
 */
@Composable
fun ReplayScreen(what: String, recorder: DriveRecorder, data: AppData, back: () -> Unit, open: (Screen) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val mode = remember(what) { ReplayMode.of(what) }
    var scene by remember { mutableStateOf<ReplayScene?>(null) }
    var empty by remember { mutableStateOf(false) }
    val fonts = remember {
        ReplayFonts(
            ResourcesCompat.getFont(context, R.font.pretendard_medium)!!,
            ResourcesCompat.getFont(context, R.font.pretendard_semibold)!!,
            ResourcesCompat.getFont(context, R.font.pretendard_bold)!!,
        )
    }
    LaunchedEffect(what, data.net.version) {
        val drives = data.drives.sortedBy { it.startedAt }
        val chosen =
            when (mode) {
                is ReplayMode.Drive -> drives.filter { it.id == mode.id }
                is ReplayMode.Region -> drives.filter { mode.code in it.regions }
                ReplayMode.All -> drives
            }
        val prior = if (mode is ReplayMode.Drive) drives.filter { d -> chosen.firstOrNull()?.let { d.startedAt < it.startedAt } == true } else emptyList()
        val replays = recorder.replays((prior + chosen).map { it.id })
        val rings =
            (mode as? ReplayMode.Region)?.let { m ->
                withContext(Dispatchers.Default) {
                    (data.shapes?.detail(m.code))?.rings?.map { r ->
                        // mercator → [lat, lng]
                        val out = DoubleArray(r.size)
                        var k = 0
                        while (k + 1 < r.size) {
                            out[k] = yToLat(r[k + 1])
                            out[k + 1] = xToLng(r[k])
                            k += 2
                        }
                        out
                    }
                }
            }
        val built =
            withContext(Dispatchers.Default) {
                ReplayScene.build(mode, chosen, replays, prior.mapNotNull { replays[it.id] }, data.index, rings, ReplayBoard.innerW, ReplayBoard.innerH)
            }
        scene = built
        empty = built.steps.isEmpty()
    }
    val paths = remember(scene) { scene?.let { ReplayPaths(it) } }
    var t by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(true) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(scene, run, playing) {
        val s = scene ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        if (t >= s.endMs) t = 0.0
        val startT = t
        var t0 = -1L
        while (t < s.endMs) {
            withFrameNanos { now ->
                if (t0 < 0) t0 = now
                t = minOf(s.endMs, startT + (now - t0) / 1e6)
            }
        }
        playing = false
    }
    val colors = ReplayColors(p.def.paper, p.def.ink, p.def.text, p.def.textSoft, p.dark)
    val sub =
        when (mode) {
            is ReplayMode.Drive -> data.drives.firstOrNull { it.id == mode.id }?.let { driveName(it.startedAt) }
            is ReplayMode.Region -> data.region(mode.code)?.label
            ReplayMode.All -> "내 지도 · 드라이브 ${data.drives.size}번"
        }
    val restart = {
        t = 0.0
        playing = true
        run += 1
    }
    val share = {
        open(
            when (mode) {
                is ReplayMode.Drive -> Screen.Share(mode.id)
                is ReplayMode.Region -> Screen.Share("regions")
                ReplayMode.All -> Screen.Share("map")
            },
        )
    }
    SubScreen(title = "다시 보기", sub = sub, onClose = back) { top, bottom ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth.value >= WIDE_MIN_PT
            val board: @Composable (Float) -> Unit = { h ->
                val w = h * ReplayBoard.W / ReplayBoard.H
                Box(
                    Modifier.size(w.dp, h.dp).card(p.paper, BOARD_CORNER, BOARD_SHADOW).clip(androidx.compose.foundation.shape.RoundedCornerShape(BOARD_CORNER.dp))
                        .semantics { contentDescription = "다시 보기 판" }
                        .press(0.99f) {
                            if (scene != null && t >= (scene?.endMs ?: 0.0)) restart() else playing = !playing
                        },
                ) {
                    val s = scene
                    val ps = paths
                    if (s != null && ps != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val k = size.width / ReplayBoard.W
                            drawIntoCanvas { ReplayArt.draw(it.nativeCanvas, s, ps, t, k, colors, fonts) }
                        }
                    } else {
                        T(if (empty) "다시 볼 드라이브가 없어요" else "길을 펼치고 있어요", ts(15f, W.Medium, 20f), p.textMuted, Modifier.align(Alignment.Center))
                    }
                    if (!playing && scene != null && t > 0 && t < (scene?.endMs ?: 0.0)) {
                        Box(Modifier.align(Alignment.Center).size(64.dp).card(p.ink.copy(alpha = 0.72f), 32f), contentAlignment = Alignment.Center) {
                            Icon(Icons.play, 26f, p.textInverse)
                        }
                    }
                }
            }
            val buttons: @Composable (Modifier, Boolean) -> Unit = { m, vertical ->
                val replayBtn: @Composable (Modifier) -> Unit = { bm ->
                    Row(bm.height(BUTTON_H.dp).card(p.surfaceStrong, BUTTON_H / 2).press(0.96f, onClick = restart).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.replay, 18f, p.textPrimary)
                        T("다시 재생", ts(16f, W.SemiBold, 21f, -0.2f), p.textPrimary)
                    }
                }
                val shareBtn: @Composable (Modifier) -> Unit = { bm ->
                    Row(bm.height(BUTTON_H.dp).card(p.textPrimary, BUTTON_H / 2).press(0.96f, onClick = share).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.share, 18f, p.textInverse)
                        T("공유하기", ts(16f, W.SemiBold, 21f, -0.2f), p.textInverse)
                    }
                }
                if (vertical) {
                    Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        replayBtn(Modifier.fillMaxWidth())
                        shareBtn(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        replayBtn(Modifier.weight(0.42f))
                        shareBtn(Modifier.weight(0.58f))
                    }
                }
            }
            if (wide) {
                val h = minOf(maxHeight.value - top - bottom - 20f, 680f)
                Row(Modifier.fillMaxSize().padding(top = top.dp, bottom = (bottom + 20f).dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    // left spacer as wide as the side column, so the board sits in the window's centre
                    Spacer(Modifier.width(SIDE_W.dp))
                    Spacer(Modifier.width(28.dp))
                    board(h)
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.width(SIDE_W.dp)) {
                        ScopePill(mode, data)
                        Spacer(Modifier.height(14.dp))
                        T(scopeText(mode, scene, data), ts(14f, W.Medium, 20f), p.textSecondary, maxLines = 3)
                        Spacer(Modifier.height(24.dp))
                        buttons(Modifier.fillMaxWidth(), true)
                    }
                }
            } else {
                val h = minOf(maxHeight.value - top - bottom - BUTTON_H - 44f, (maxWidth.value - SUB_SIDE * 2) * ReplayBoard.H / ReplayBoard.W)
                Column(Modifier.fillMaxSize().padding(top = top.dp, bottom = (bottom + 20f).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    board(h)
                    Spacer(Modifier.height(18.dp))
                    buttons(Modifier.width((h * ReplayBoard.W / ReplayBoard.H).coerceAtLeast(300f).dp), false)
                }
            }
        }
    }
}

private const val SIDE_W = 260f

/** 범위 알약(발자취 기간 알약 40, 모서리 20) — 무엇을 다시 보는지 */
@Composable
private fun ScopePill(mode: ReplayMode, data: AppData) {
    val p = LocalPalette.current
    val text =
        when (mode) {
            is ReplayMode.Drive -> "드라이브 하나"
            is ReplayMode.Region -> data.regionName(mode.code)
            ReplayMode.All -> "모든 날"
        }
    Row(Modifier.height(40.dp).card(p.surface, 20f).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (mode is ReplayMode.All) Icons.calendar else Icons.play, 16f, p.textPrimary)
        T(text, ts(15f, W.SemiBold, 20f), p.textPrimary)
    }
}

private fun scopeText(mode: ReplayMode, scene: ReplayScene?, data: AppData): String {
    val n = scene?.steps?.size ?: 0
    return when (mode) {
        is ReplayMode.Drive -> "달린 차례대로 다시 그려요. 여러 번 지난 길은 지날 때마다 굵어져요."
        is ReplayMode.Region -> "이 지역을 지난 드라이브 ${n}번을 날짜 순으로 그려요. 지역 안의 길만 그려요."
        ReplayMode.All -> "드라이브 ${n}번을 날짜 순으로 그려요. 처음 간 지역에 들어서면 이름이 떠요."
    }
}
