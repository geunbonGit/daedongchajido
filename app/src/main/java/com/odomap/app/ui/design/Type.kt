package com.odomap.app.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.odomap.app.R

/**
 * Pretendard — 굵기마다 파일 하나(FontFamily 하나)를 고르고 fontWeight는 늘 Normal(발자취 Android 함정: 굵기를 주면 합성 굵게가 겹친다).
 * 숫자는 모두 고정폭(tnum). 줄 높이는 RN과 같게 줄 가운데 정렬(LineHeightStyle Center, 글꼴 여백 없음) — 숫자·단위의 바닥선 셈(BASELINE_K)이 그대로 맞는다.
 */
enum class W(val family: FontFamily) {
    Regular(FontFamily(Font(R.font.pretendard_regular, FontWeight.Normal))),
    Medium(FontFamily(Font(R.font.pretendard_medium, FontWeight.Normal))),
    SemiBold(FontFamily(Font(R.font.pretendard_semibold, FontWeight.Normal))),
    Bold(FontFamily(Font(R.font.pretendard_bold, FontWeight.Normal))),
    ExtraBold(FontFamily(Font(R.font.pretendard_extrabold, FontWeight.Normal))),
}

/** 글자 최소(design.md 1: 차에서는 12pt 밑으로 내리지 않는다) */
const val MIN_TEXT = 12f

/** Pretendard 위 0.952em · 아래 0.241em: 줄 높이 L 가운데 놓인 크기 S 글자의 바닥선은 줄 아래 끝에서 L/2 − 0.3555·S */
const val BASELINE_K = (0.952f + 0.241f) / 2f - 0.241f

private val CENTER_LINES = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** size·line(pt) · 굵기 · 자간(pt). 줄 높이를 주지 않으면 글꼴 높이(1.193em)를 반올림한 값 */
fun ts(size: Float, w: W, line: Float? = null, tracking: Float = 0f): TextStyle {
    val s = maxOf(size, MIN_TEXT)
    return TextStyle(
        fontFamily = w.family,
        fontWeight = FontWeight.Normal,
        fontSize = s.sp,
        lineHeight = (line ?: Math.round(s * 1.25f).toFloat()).sp,
        letterSpacing = tracking.sp,
        fontFeatureSettings = "tnum",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = CENTER_LINES,
    )
}

/** 단위를 숫자와 같은 바닥선에 — 둘 다 아래 끝 정렬인 줄(높이 valueLine)에서 단위(크기 unitSize, 줄 unitLine)를 올릴 만큼(pt) */
fun unitLift(valueLine: Float, valueSize: Float, unitLine: Float, unitSize: Float): Float =
    maxOf(0f, Math.round(valueLine / 2f - BASELINE_K * valueSize - (unitLine / 2f - BASELINE_K * unitSize)).toFloat())

/** 발자취 글자 표(design-footprint-ref 3). 이름은 쓰이는 자리. */
object Type {
    val screenTitle = ts(28f, W.ExtraBold, 36f, -0.3f)
    val regionTitle = ts(28f, W.ExtraBold, 36f, -0.5f)
    val sectionTitle = ts(24f, W.ExtraBold, 32f, -0.4f)
    val monthTitle = ts(20f, W.Bold, 26f, -0.3f)
    val monthTotals = ts(13f, W.Medium, 18f)
    val headLabel = ts(20f, W.Bold, 26f, -0.3f)
    val headValue = ts(20f, W.SemiBold, 26f, -0.3f)

    val totalsBig = ts(42f, W.ExtraBold, 52f, -1f)
    val totalsUnit = ts(20f, W.SemiBold, 28f)
    val totalsLabel = ts(14f, W.Medium, 18f)

    val statsBig = ts(40f, W.SemiBold, 48f, -0.8f)
    val statsUnit = ts(17f, W.Medium, 22f)

    val resultBig = ts(38f, W.SemiBold, 46f, -0.6f)
    val resultUnit = ts(18f, W.Medium, 24f)
    val resultLabel = ts(14f, W.Medium, 18f)

    val gridLabel = ts(13f, W.Medium, 17f)
    val gridValue = ts(22f, W.Bold, 28f, -0.3f)
    val gridUnit = ts(14f, W.Medium, 18f)

    val pill = ts(17f, W.SemiBold, 22f, -0.2f)
    val compass = ts(14f, W.Medium, 18f)
    val lookTitle = ts(19f, W.SemiBold, 24f)
    val lookSection = ts(13f, W.Medium, 17f)
    val swatchLabel = ts(12f, W.Medium, 16f)

    val rowTitle = ts(16f, W.SemiBold, 21f)
    val rowMeta = ts(13f, W.Medium, 17f)
    val rowValue = ts(18f, W.SemiBold, 24f, -0.3f)
    val rowUnit = ts(12f, W.Medium, 16f)
    val navRow = ts(16f, W.Medium, 21f)
    val trailing = ts(14f, W.Medium, 18f)

    val topTitle = ts(17f, W.SemiBold, 22f)
    val topSub = ts(12f, W.Medium, 16f)

    val badgeName = ts(14f, W.SemiBold, 20f, -0.2f)
    val badgeKm = ts(13f, W.Medium, 18f)
    val credit = ts(12f, W.Medium, 16f)
    val body = ts(15f, W.Medium, 21f)
    val caption = ts(13f, W.Medium, 18f)
}

/** 한 줄 글(넘치면 자름) — Material Text 대신(바탕 글 모양·색을 끼우지 않는다) */
@Composable
fun T(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    size: TextUnit = TextUnit.Unspecified,
) {
    var s = style.copy(color = color)
    if (align != null) s = s.copy(textAlign = align)
    if (size != TextUnit.Unspecified) s = s.copy(fontSize = if (size.value < MIN_TEXT) MIN_TEXT.sp else size)
    BasicText(text = text, modifier = modifier, style = s, maxLines = maxLines, softWrap = maxLines > 1, overflow = overflow)
}

@Composable
fun T(text: AnnotatedString, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null) {
    var s = style.copy(color = color)
    if (align != null) s = s.copy(textAlign = align)
    BasicText(text = text, modifier = modifier, style = s, maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Clip)
}

/** 띄어쓰기를 줄이 나뉘지 않는 공백으로(한 줄 이름 — 발자취 keepOnOneLine) */
fun oneLine(text: String): String = text.replace(' ', ' ')
