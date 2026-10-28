package com.odomap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odomap.app.record.DriveRecorder
import com.odomap.app.replay.REPLAY_SPEEDS
import com.odomap.app.replay.ReplaySource
import com.odomap.app.replay.ReplayState
import com.odomap.app.ui.design.BlackPill
import com.odomap.app.ui.design.ClosePill
import com.odomap.app.ui.design.HLine
import com.odomap.app.ui.design.Icon
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.NeutralPill
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.ts

/** 시연 패널(판 머리 '오늘'을 길게 누르거나 설정 › 시연 패널): 시연 드라이브 · 빠르기 · 주행 화면 제한 풀기 · 전체 삭제. 떠 있는 카드(꾸미기 시트와 같은 틀). */
@Composable
fun DemoPanel(
    recorder: DriveRecorder,
    replay: ReplayState,
    demoOverride: Boolean,
    onDemoOverride: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPlayed: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val demos = remember { runCatching { ReplaySource.index(context.assets) }.getOrDefault(emptyList()) }
    val speed by recorder.replaySpeed.collectAsStateWithLifecycle()
    val source by recorder.sourceName.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val insets = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val top = with(density) { insets.getTop(this).toDp() }
    val bottom = with(density) { insets.getBottom(this).toDp() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(top = top + 16.dp, bottom = bottom + 16.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .card(p.surface, 30f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(30.dp))
                .clickable(remember { MutableInteractionSource() }, null) {},
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("시연", Type.lookTitle, p.textPrimary)
                    T("입력: $source", Type.caption, p.textMuted)
                }
                ClosePill(onClick = onClose)
            }
            T("빠르기", Type.lookSection, p.textSecondary, Modifier.padding(start = 22.dp, top = 12.dp, bottom = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in REPLAY_SPEEDS) {
                    val on = s == speed
                    Box(
                        Modifier.height(40.dp).card(if (on) p.ink else p.surfaceStrong, 20f).press(0.95f) { recorder.replaySpeed.value = s }.padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { T("$s×", ts(15f, W.SemiBold, 20f), if (on) p.textInverse else p.textPrimary) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp).press(0.98f) { onDemoOverride(!demoOverride) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("시연 중 주행 화면 제한 풀기", ts(15f, W.Medium, 20f), p.textPrimary, Modifier.weight(1f))
                Toggle(demoOverride)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BlackPill("모두 차례로", Icons.play, Modifier.weight(1f)) {
                    recorder.playDemo(demos.map { it.id })
                    onPlayed()
                }
                if (replay.playing) NeutralPill(Modifier.weight(0.7f), onClick = { recorder.stopDemo() }) { T("멈춤", Type.pill, p.textPrimary) }
                NeutralPill(Modifier.weight(0.7f), onClick = { confirmReset = !confirmReset }) { T("전체 삭제", Type.pill, p.danger) }
            }
            if (confirmReset) {
                Row(Modifier.padding(start = 22.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("모든 드라이브와 지도를 지울까요?", ts(14f, W.Medium, 18f), p.textPrimary, Modifier.weight(1f))
                    Box(Modifier.height(36.dp).card(p.danger, 18f).press(0.95f) {
                        confirmReset = false
                        recorder.resetAll()
                    }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { T("지우기", ts(14f, W.SemiBold, 18f), Color.White) }
                }
            }
            Spacer(Modifier.height(12.dp))
            HLine(p.dividerSubtle, Modifier.padding(horizontal = 22.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(demos, key = { it.id }) { d ->
                    Row(
                        Modifier.fillMaxWidth().press(0.98f) {
                            recorder.playDemo(listOf(d.id))
                            onPlayed()
                        }.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(d.title, Type.rowTitle, p.textPrimary)
                            T(d.id, Type.rowMeta, p.textMuted)
                        }
                        T("${kmText(d.distanceM)}km · ${Math.round(d.minutes)}분", Type.rowMeta, p.textSecondary)
                        if (replay.playing && replay.demoId == d.id) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.play, 16f, p.blue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    val p = LocalPalette.current
    Box(Modifier.size(52.dp, 32.dp).card(if (on) p.ink else p.surfaceStrong, 16f), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(3.dp).size(26.dp).card(if (on) p.textInverse else p.surface, 13f))
    }
}
