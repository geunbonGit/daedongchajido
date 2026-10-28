package com.odomap.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.odomap.app.ui.design.BlackPill
import com.odomap.app.ui.design.Icon
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.NeutralPill
import com.odomap.app.ui.design.PILL_H
import com.odomap.app.ui.design.STATS_H
import com.odomap.app.ui.design.SheetStat
import com.odomap.app.ui.design.SmallPill
import com.odomap.app.ui.design.StatRow
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.TextButton
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.odomap.app.ui.design.circle
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.ts

/** 판 안 양옆 */
const val SHEET_SIDE = 20f
private const val UPPER_TOP = 6f
private const val HEAD_ROW = 32f
private const val HEAD_GAP = 10f
private const val STATS_GAP = 12f
private const val UPPER_BOTTOM = 12f
/** 좁은 창 판 아래 여백(발자취는 탭 막대가 있던 자리 — 차에는 없다) */
const val SHEET_BOTTOM = 10f

/** 윗부분 높이(머리 줄 + 숫자 셋 + 알약 줄) */
const val UPPER_H = UPPER_TOP + HEAD_ROW + HEAD_GAP + STATS_H + STATS_GAP + PILL_H + UPPER_BOTTOM

/** 판 윗부분의 상태: 평소 · 주행 중 · 도착 */
@Immutable
sealed interface UpperMode {
    /** 오늘: 머리 '오늘' + 오른쪽 '내 지도 415.0km ›', 숫자 셋, 검은 알약 '안 가 본 동네로' */
    data class Today(val mapKm: String, val stats: List<SheetStat>) : UpperMode

    /** 주행 중: 머리 = 드라이브 이름, 숫자 셋(이번), 무채색 알약 '기록 중 · 0:42 · 12.4km'(누르지 않음) */
    data class Driving(val title: String, val stats: List<SheetStat>, val clock: String, val km: String) : UpperMode

    /** 도착: 머리 '도착 · 오후 6:42' + 오른쪽 '닫기', 숫자 셋, 검은 알약 '다시 보기' + 공유 동그라미 */
    data class Arrived(val time: String, val stats: List<SheetStat>) : UpperMode
}

private fun UpperMode.kind(): Int =
    when (this) {
        is UpperMode.Today -> 0
        is UpperMode.Driving -> 1
        is UpperMode.Arrived -> 2
    }

/**
 * 판 윗부분(발자취 ExploreSheetHandleView의 upper): 손잡이 아래 6 · 머리 줄 32(+10) · 숫자 셋 42(+12) · 알약 56 · 아래 12. 판의 상태가 바뀌면
 * 나가는 글이 흐려진 뒤 들어온다(나감 120ms · 들어옴 180ms, 100ms 늦게).
 */
@Composable
fun SheetUpper(
    mode: UpperMode,
    width: Float,
    animate: Boolean,
    onMyMap: () -> Unit,
    onUnvisited: () -> Unit,
    onCloseArrival: () -> Unit,
    onReplay: () -> Unit,
    onShare: () -> Unit,
    /** 머리 제목('오늘' · 주행 중 드라이브 이름)을 길게 — 시연 패널(보이는 단추 없이; 시연 중 멈춤도 여기서) */
    onHeadLongPress: () -> Unit = {},
) {
    val p = LocalPalette.current
    val inner = width - SHEET_SIDE * 2
    Column(Modifier.fillMaxWidth().padding(horizontal = SHEET_SIDE.dp).padding(top = UPPER_TOP.dp, bottom = UPPER_BOTTOM.dp)) {
        AnimatedContent(
            targetState = mode,
            contentKey = { it.kind() },
            transitionSpec = { fadeIn(tween(180, 100)).togetherWith(fadeOut(tween(120))) },
            label = "upper",
        ) { m ->
            Column {
                // 머리 줄
                Row(Modifier.fillMaxWidth().height(HEAD_ROW.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (m) {
                        is UpperMode.Today -> {
                            T("오늘", Type.headLabel, p.textPrimary, Modifier.weight(1f).pointerInput(onHeadLongPress) { detectTapGestures(onLongPress = { onHeadLongPress() }) })
                            SmallPill("내 지도", m.mapKm, onClick = onMyMap)
                        }
                        is UpperMode.Driving -> {
                            Box(Modifier.size(10.dp).circle(p.ink))
                            Spacer(Modifier.width(10.dp))
                            T(m.title, Type.headLabel, p.textPrimary, Modifier.weight(1f).pointerInput(onHeadLongPress) { detectTapGestures(onLongPress = { onHeadLongPress() }) })
                        }
                        is UpperMode.Arrived -> {
                            T(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = p.blue)) { append("도착") }
                                    withStyle(SpanStyle(color = p.textPrimary)) { append(" · ${m.time}") }
                                },
                                Type.headLabel,
                                p.textPrimary,
                                Modifier.weight(1f),
                            )
                            TextButton("닫기", p.textSecondary, onClick = onCloseArrival, style = ts(16f, W.Medium, 21f))
                        }
                    }
                }
                Spacer(Modifier.height(HEAD_GAP.dp))
                val stats =
                    when (m) {
                        is UpperMode.Today -> m.stats
                        is UpperMode.Driving -> m.stats
                        is UpperMode.Arrived -> m.stats
                    }
                StatRow(stats, inner, animate = animate)
                Spacer(Modifier.height(STATS_GAP.dp))
                when (m) {
                    is UpperMode.Today -> BlackPill("안 가 본 동네로", Icons.flag, Modifier.fillMaxWidth(), onClick = onUnvisited)
                    is UpperMode.Driving ->
                        NeutralPill(Modifier.fillMaxWidth()) {
                            Icon(Icons.footprints, 18f, p.textPrimary)
                            T("기록 중", ts(16f, W.SemiBold, 21f, -0.2f), p.textPrimary)
                            T("· ${m.clock}", ts(16f, W.SemiBold, 21f, -0.3f), p.textPrimary)
                            T("· ${m.km}", ts(16f, W.Medium, 21f, -0.2f), p.textSecondary)
                        }
                    is UpperMode.Arrived ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BlackPill("다시 보기", Icons.play, Modifier.weight(1f), onClick = onReplay)
                            Box(Modifier.size(PILL_H.dp).circle(p.neutralPill).press(0.94f, onClick = onShare), contentAlignment = Alignment.Center) {
                                Icon(Icons.share, 20f, p.textPrimary)
                            }
                        }
                }
            }
        }
    }
}
