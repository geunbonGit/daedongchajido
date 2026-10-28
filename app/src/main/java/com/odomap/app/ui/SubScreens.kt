package com.odomap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.design.BlackPill
import com.odomap.app.ui.design.Card
import com.odomap.app.ui.design.CloseCircle
import com.odomap.app.ui.design.HLine
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.LocalToast
import com.odomap.app.ui.design.NavRow
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.swallowTouches
import com.odomap.app.ui.design.ts

/** 화면 위 여백(닫기 원 줄) */
const val SUB_TOP = 20f
const val SUB_SIDE = 20f

/**
 * 하위 화면 틀(발자취 산책 결과·걷기 통계와 같다): 바탕 app, 왼쪽 위 닫기 원 40, 가운데 제목 17/600 + 보조 12/500, 내용은 그 아래.
 */
@Composable
fun SubScreen(
    title: String? = null,
    sub: String? = null,
    onClose: () -> Unit,
    content: @Composable BoxScope.(top: Float, bottom: Float) -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val top = with(density) { insets.getTop(this).toDp().value }
    val bottom = with(density) { insets.getBottom(this).toDp().value }
    val dir = LocalLayoutDirection.current
    val left = with(density) { insets.getLeft(this, dir).toDp().value }
    Box(Modifier.fillMaxSize().background(p.app).swallowTouches()) {
        content(top + SUB_TOP + 40f + 16f, bottom)
        // 위 가장자리 흐림(닫기 원 밑으로 지나가는 내용)
        Box(
            Modifier.fillMaxWidth().height((top + SUB_TOP + 56f).dp)
                .background(Brush.verticalGradient(0.62f to p.app, 1f to p.app.copy(alpha = 0f))),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = (left + SUB_SIDE).dp, end = SUB_SIDE.dp, top = (top + SUB_TOP).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloseCircle(onClick = onClose)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (title != null) T(title, Type.topTitle, p.textPrimary)
                if (sub != null) T(sub, Type.topSub, p.textSecondary, Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.width(40.dp))
        }
    }
}

/** 설정: 오픈소스·데이터 출처 · 기록 모두 지우기 · 시연 패널 */
@Composable
fun SettingsScreen(recorder: DriveRecorder, onClose: () -> Unit, onDemoPanel: () -> Unit = {}) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    SubScreen(title = "설정", onClose = onClose) { top, bottom ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top.dp, bottom = (bottom + 32f).dp).padding(horizontal = SUB_SIDE.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("설정", Type.screenTitle, p.textPrimary, Modifier.padding(bottom = 8.dp))
                NavRow(Icons.gear, "오픈소스 · 데이터 출처") { licenses = !licenses }
                if (licenses) {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            T("Pretendard — SIL Open Font License 1.1 (© Kil Hyung-jin)", Type.caption, p.textSecondary, maxLines = 3)
                            HLine(p.dividerSubtle)
                            T("지도 — MapLibre(BSD) · 실제 지도 © OpenMapTiles © OpenStreetMap contributors (OpenFreeMap)", Type.caption, p.textSecondary, maxLines = 3)
                            HLine(p.dividerSubtle)
                            T(regionAttribution(context), Type.caption, p.textSecondary, maxLines = 8)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (!confirm) {
                    NavRow(null, "기록 모두 지우기") { confirm = true }
                } else {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            T("모든 드라이브와 내 지도를 지울까요?", Type.rowTitle, p.textPrimary)
                            T("지우면 되돌릴 수 없어요.", Type.rowMeta, p.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                com.odomap.app.ui.design.NeutralPill(Modifier.weight(1f), onClick = { confirm = false }) { T("취소", Type.pill, p.textPrimary) }
                                Box(Modifier.weight(1f)) {
                                    BlackPill("지우기", null, Modifier.fillMaxWidth()) {
                                        confirm = false
                                        recorder.resetAll()
                                        toast.show("기록을 모두 지웠어요")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                NavRow(null, "시연 패널", onClick = onDemoPanel)
                Spacer(Modifier.height(20.dp))
                T("대동차지도 0.1 · 달린 길이 지도가 된다", Type.caption, p.textMuted, Modifier.fillMaxWidth(), align = TextAlign.Center)
            }
        }
    }
}

private fun regionAttribution(context: android.content.Context): String =
    runCatching {
        context.assets.open("regions/regions-outline.json").bufferedReader().use { r ->
            val o = org.json.JSONObject(r.readText())
            o.getJSONObject("source").optString("attribution")
        }
    }.getOrNull()?.ifBlank { null }
        ?: "시·군·구 경계: 통계청 SGIS 행정동 경계(공공누리 제1유형)를 vuski/admdongkor가 가공, CC BY 4.0. 시·군·구 단위 병합·단순화: 대동차지도."

@Suppress("unused")
private val keepSize = Modifier.size(1.dp)
