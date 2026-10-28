package com.odomap.app.ui.share

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.odomap.app.record.DriveRecorder
import com.odomap.app.share.CardFonts
import com.odomap.app.share.CardLook
import com.odomap.app.share.CardOutline
import com.odomap.app.share.CardRatio
import com.odomap.app.share.QrArt
import com.odomap.app.share.ShareCardArt
import com.odomap.app.share.ShareData
import com.odomap.app.share.ShareInputs
import com.odomap.app.share.ShareKind
import com.odomap.app.share.cardScene
import com.odomap.app.ui.AppData
import com.odomap.app.ui.SUB_SIDE
import com.odomap.app.ui.SubScreen
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.Seg
import com.odomap.app.ui.design.Shadow
import com.odomap.app.ui.design.SwatchChip
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.Toggle
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.WIDE_MIN_PT
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.rgbColor
import com.odomap.app.ui.design.ts
import com.odomap.core.share.ShareQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 카드 미리보기 그림자(발자취 0 10 40 rgba(0,0,0,0.12)) */
private val STAGE_SHADOW = Shadow(10f, 20f, 0.12f)
private const val QR_MIN = 300f
private const val QR_COL = 380f
private val RATIO_EASE = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)

/** the screen's entry key → first card: "map" 내 지도 · "regions" 가 본 곳 · a drive id 이번 드라이브 */
fun shareKindOf(what: String): ShareKind =
    when (what) {
        "map" -> ShareKind.MAP
        "regions" -> ShareKind.REGIONS
        else -> ShareKind.DRIVE
    }

/**
 * 공유(design.md 4.8 · 발자취 카드 미리보기 + QR): 왼쪽(좁은 창은 위) 카드 — 휴대폰 뷰어(web/s)와 같은 그림을 이 자리에서 그린다(ShareCardArt,
 * QR에 담긴 그대로의 카드) · 카드 종류 · 비율 · 바탕. 오른쪽(좁은 창은 아래) QR(흰 바탕, 조용한 여백 4모듈, 한 변 ≥ 300pt) + 안내 + '출발·도착 가리기'
 * + 서버 안내. 정차 중에만(여는 쪽이 막는다).
 */
@Composable
fun ShareScreen(what: String, recorder: DriveRecorder, data: AppData, back: () -> Unit) {
    val context = LocalContext.current
    val driveId = what.takeIf { it != "map" && it != "regions" } ?: data.drives.maxByOrNull { it.startedAt }?.id
    var kind by rememberSaveable { mutableStateOf(shareKindOf(what)) }
    var ratio by rememberSaveable { mutableStateOf(CardRatio.SQUARE) }
    var look by rememberSaveable { mutableStateOf(CardLook.PAPER) }
    var hide by rememberSaveable { mutableStateOf(true) }
    var qr by remember { mutableStateOf<ShareQr?>(null) }
    var failed by remember { mutableStateOf(false) }
    var building by remember { mutableIntStateOf(0) }
    val fonts = remember { CardFonts.of(context) }
    var outline by remember { mutableStateOf<CardOutline?>(null) }
    LaunchedEffect(Unit) { outline = withContext(Dispatchers.IO) { CardOutline.load(context) } }

    val inputs = remember(data.drives, data.visited, data.index, data.net.netLengthM) { ShareInputs(data.drives, data.index, data.visited, data.net.netLengthM) }
    LaunchedEffect(kind, hide, inputs, driveId) {
        building += 1
        failed = false
        val made = runCatching { ShareData.build(recorder, inputs, kind, driveId, hide, System.currentTimeMillis()) }.getOrNull()
        if (made == null) failed = true
        qr = made
        building -= 1
    }
    val shown = qr?.takeIf { it.card.kind == kindNumber(kind) }
    val scene = remember(shown, ratio, look, outline) { shown?.let { cardScene(it.card, ratio, look, outline) } }
    val drive = data.drives.firstOrNull { it.id == driveId }

    SubScreen(title = "공유", sub = if (kind == ShareKind.DRIVE && drive != null) com.odomap.app.ui.driveName(drive.startedAt) else null, onClose = back) { top, bottom ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth.value >= WIDE_MIN_PT
            val kinds = ShareKind.entries
            val kindSeg: @Composable (Modifier) -> Unit = { m ->
                Seg(kinds.map { it.label }, kinds.indexOf(kind), m, enabled = { kinds[it] != ShareKind.DRIVE || driveId != null }) { kind = kinds[it] }
            }
            val styleRow: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Seg(CardRatio.entries.map { it.label }, CardRatio.entries.indexOf(ratio), Modifier.width(196.dp), height = 40f) { ratio = CardRatio.entries[it] }
                    Spacer(Modifier.width(6.dp))
                    for (l in CardLook.entries) SwatchChip(l.label, rgbColor(l.paper), rgbColor(l.ink), l == look) { look = l }
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(start = SUB_SIDE.dp, end = (SUB_SIDE + 4f).dp, top = top.dp, bottom = (bottom + 18f).dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        kindSeg(Modifier.width(420.dp))
                        Stage(scene, ratio, fonts, Modifier.weight(1f).fillMaxWidth(), building > 0 && scene == null)
                        styleRow()
                    }
                    QrPanel(shown, building > 0, failed, kind, hide, { hide = it }, Modifier.width(QR_COL.dp).fillMaxHeight())
                }
            } else {
                val w = maxWidth.value - SUB_SIDE * 2
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SUB_SIDE.dp, end = SUB_SIDE.dp, top = top.dp, bottom = (bottom + 28f).dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    kindSeg(Modifier.fillMaxWidth())
                    Stage(scene, ratio, fonts, Modifier.fillMaxWidth().height((if (ratio == CardRatio.STORY) w * 1.1f else w * 0.86f).dp), building > 0 && scene == null)
                    styleRow()
                    Spacer(Modifier.height(24.dp))
                    QrPanel(shown, building > 0, failed, kind, hide, { hide = it }, Modifier.widthIn(max = 460.dp).fillMaxWidth())
                }
            }
        }
    }
}

private fun kindNumber(k: ShareKind): Int =
    when (k) {
        ShareKind.DRIVE -> com.odomap.core.share.SHARE_KIND_DRIVE
        ShareKind.MAP -> com.odomap.core.share.SHARE_KIND_ATLAS
        ShareKind.REGIONS -> com.odomap.core.share.SHARE_KIND_REGIONS
    }

/** 카드 자리: 정사각은 폭 420까지, 스토리는 높이에 맞춰 — 비율을 바꾸면 340ms에 걸쳐 모양이 바뀐다 */
@Composable
private fun Stage(scene: com.odomap.app.share.CardScene?, ratio: CardRatio, fonts: CardFonts, modifier: Modifier, loading: Boolean) {
    val p = LocalPalette.current
    val aspect by animateFloatAsState(ratio.height / 360f, tween(340, easing = RATIO_EASE), label = "ratio")
    BoxWithConstraints(modifier.padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        val maxW = minOf(420f, maxWidth.value - 8f)
        val maxH = maxHeight.value
        val w = minOf(maxW, maxH / aspect)
        val h = w * aspect
        Box(Modifier.size(w.dp, h.dp).card(rgbColor(scene?.look?.paper ?: 0xF7F7F5), 0f, STAGE_SHADOW).semantics { contentDescription = "공유 카드 미리보기" }) {
            if (scene != null) {
                Canvas(Modifier.fillMaxSize()) {
                    // the card keeps its ratio; while the frame animates, draw it centered (cropped like the viewer's slot)
                    val k = size.width / com.odomap.app.share.CardTokens.DESIGN_W
                    val cardH = scene.layout.height * k
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save()
                        nc.clipRect(0f, 0f, size.width, size.height)
                        nc.translate(0f, (size.height - cardH) / 2f)
                        ShareCardArt.draw(nc, scene, k, fonts)
                        nc.restore()
                    }
                }
            } else if (loading) {
                T("카드를 그리고 있어요", ts(14f, W.Medium, 18f), p.textMuted, Modifier.align(Alignment.Center))
            }
        }
    }
}

/** QR 판: 흰 바탕 QR + 안내 + 출발·도착 가리기 + 서버 안내 */
@Composable
private fun QrPanel(qr: ShareQr?, building: Boolean, failed: Boolean, kind: ShareKind, hide: Boolean, onHide: (Boolean) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = maxOf(QR_MIN, minOf(maxWidth.value, 340f))
            Box(Modifier.size(side.dp).card(androidx.compose.ui.graphics.Color.White, 24f, Shadow(2f, 8f, 0.08f)), contentAlignment = Alignment.Center) {
                if (qr != null) {
                    Canvas(Modifier.fillMaxSize().alpha(if (building) 0.35f else 1f).semantics { contentDescription = "공유 QR 코드" }) {
                        drawIntoCanvas { c ->
                            // whole pixels per module, the 4-module quiet zone inside the white square (rounded corners stay white)
                            QrArt.draw(c.nativeCanvas, qr.size, qr::isDark, 0f, 0f, size.width, quiet = 4)
                        }
                    }
                } else {
                    T(if (failed && !building) "공유할 기록이 없어요" else "QR을 만들고 있어요", ts(15f, W.Medium, 20f), androidx.compose.ui.graphics.Color(0xFF8A8A8E))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        T("휴대폰 카메라로 찍으면\n인스타그램·카카오톡에 바로 올릴 수 있어요", ts(17f, W.SemiBold, 24f, -0.2f), p.textPrimary, Modifier.fillMaxWidth(), maxLines = 2, align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (kind != ShareKind.REGIONS) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).card(p.surface, 24f).press(0.98f) { onHide(!hide) }.padding(start = 20.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T("출발·도착 가리기", ts(16f, W.SemiBold, 21f), p.textPrimary)
                    T(if (kind == ShareKind.DRIVE) "출발·도착 300m 안의 길은 빼요" else "자주 가는 곳 500m 안의 길은 빼요", ts(13f, W.Medium, 17f), p.textSecondary)
                }
                Toggle(hide)
            }
            Spacer(Modifier.height(12.dp))
        }
        T("기록은 서버로 가지 않아요. QR 안에 담겨 휴대폰으로만 전해져요.", ts(13f, W.Medium, 18f), p.textMuted, Modifier.fillMaxWidth().padding(horizontal = 8.dp), maxLines = 2, align = TextAlign.Center)
    }
}
