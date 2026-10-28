package com.odomap.app.ui.design

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 배색 17종 — 발자취 `EXPLORE_PAPERS`(clientApp services/explore/exploreLook.ts) 그대로, 순서도 그대로. 색은 0xRRGGBB.
 * 배색은 지도 바탕·모눈·잉크와 그 위의 글자·단추·하위 화면까지 한 벌이다(앱 테마가 아니라 고른 종이를 따른다).
 */
@Immutable
data class Paper(
    val id: String,
    val label: String,
    val dark: Boolean,
    val paper: Int,
    val ink: Int,
    val fresh: Int,
    val text: Int,
    val textSoft: Int,
    val button: Int,
    val buttonIcon: Int,
    val halo: Int,
)

val PAPERS: List<Paper> =
    listOf(
        Paper("paper", "종이", false, 0xF7F7F5, 0x262626, 0x1A73E8, 0x1C1C1E, 0x8A8A8E, 0xFFFFFF, 0x1C1C1E, 0x4A90E2),
        Paper("hanji", "한지", false, 0xF4F0E6, 0x2A2622, 0x2D4B9A, 0x24201C, 0x8C847A, 0xFFFDF8, 0x24201C, 0x4A64A8),
        Paper("news", "신문", false, 0xECEBE7, 0x141414, 0x1F4FD1, 0x111111, 0x7C7A75, 0xFFFFFF, 0x111111, 0x3C64D6),
        Paper("sand", "모래", false, 0xF1EADC, 0x4B3F31, 0xB4651E, 0x2F271E, 0x9A8E7E, 0xFFFCF6, 0x2F271E, 0xC0884F),
        Paper("kraft", "크라프트", false, 0xE3D3B8, 0x2F2416, 0x2156A8, 0x2A2014, 0x7D6B52, 0xFBF4E8, 0x2A2014, 0x2F6DB5),
        Paper("lemon", "레몬", false, 0xFAF6E0, 0x3C3820, 0xB88A00, 0x2B2812, 0x8F8A6E, 0xFFFFFF, 0x2B2812, 0xD4A82A),
        Paper("apricot", "살구", false, 0xFBF0E8, 0x5B3A2B, 0xE0703A, 0x3F2418, 0xA08778, 0xFFFFFF, 0x3F2418, 0xE8875A),
        Paper("blossom", "벚꽃", false, 0xF8EFEF, 0x5C2C36, 0xD04F6B, 0x3D1E25, 0x9A8186, 0xFFFFFF, 0x3D1E25, 0xD96A82),
        Paper("lilac", "라일락", false, 0xF0EFF7, 0x34305B, 0x6C5CE7, 0x24213F, 0x8A87A3, 0xFFFFFF, 0x24213F, 0x7B6EE8),
        Paper("sky", "하늘", false, 0xEDF2F8, 0x1F3B57, 0x2E7BD6, 0x16293D, 0x7F8EA0, 0xFFFFFF, 0x16293D, 0x3D86D9),
        Paper("sea", "바다", false, 0xEAF4F3, 0x134E4A, 0x0E9F96, 0x0F3532, 0x75908D, 0xFFFFFF, 0x0F3532, 0x1BA39C),
        Paper("mint", "쑥", false, 0xEEF4EF, 0x21473A, 0x2E8B57, 0x17322A, 0x7B8F86, 0xFFFFFF, 0x17322A, 0x3C9A6A),
        Paper("night", "밤", true, 0x141416, 0xECEAE5, 0x8AB4F8, 0xF2F2F0, 0x8E8E93, 0x26262A, 0xF2F2F0, 0x8AB4F8),
        Paper("dawn", "새벽", true, 0x1C1A2E, 0xE8E4FF, 0xFF9EC4, 0xEEEAFF, 0x9C97B8, 0x2A2742, 0xEEEAFF, 0xB9A7FF),
        Paper("blueprint", "청사진", true, 0x173A63, 0xEAF2FB, 0x7FD1FF, 0xEEF4FB, 0x9DB3CC, 0x22497A, 0xEEF4FB, 0x7FD1FF),
        Paper("chalk", "칠판", true, 0x22302A, 0xECEBE2, 0xF4D35E, 0xEFEDE4, 0x9AA59D, 0x2F3F37, 0xEFEDE4, 0xF4D35E),
        Paper("cocoa", "코코아", true, 0x2A211C, 0xF3E9DD, 0xE8B26A, 0xF5EDE3, 0xA89A8C, 0x3A2E27, 0xF5EDE3, 0xE8B26A),
    )

const val AUTO_PAPER = "auto"

/** 발자취 선 모양 6(services/explore/exploreLineStyles.ts). */
enum class LineStyle(val id: String, val label: String) {
    SOLID("solid", "실선"),
    DOTTED("dotted", "점선"),
    DASHED("dashed", "긴 점선"),
    DOUBLE("double", "두 줄"),
    MARKER("marker", "형광펜"),
    BLEED("bleed", "번짐"),
}

/** 현재 위치 모양(토끼는 차 화면에 넣지 않는다 — design.md 4.10). */
enum class Marker(val id: String, val label: String) {
    DOT("dot", "점"),
    MOON("moon", "달"),
}

/** 꾸미기: 배색(자동 포함) · 선 · 현재 위치. */
@Immutable
data class Look(val paper: String = AUTO_PAPER, val line: LineStyle = LineStyle.SOLID, val marker: Marker = Marker.DOT)

fun paperOf(id: String, nightMode: Boolean): Paper {
    val wanted = if (id == AUTO_PAPER) (if (nightMode) "night" else "paper") else id
    return PAPERS.firstOrNull { it.id == wanted } ?: PAPERS[0]
}

/** 두 0xRRGGBB 색을 채널마다 섞는다(t는 b 쪽 비율) — 발자취 mixHex. */
fun mixRgb(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int) = ((a shr shift and 255) * (1 - t) + (b shr shift and 255) * t).roundToInt().coerceIn(0, 255)
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun rgbColor(rgb: Int, alpha: Float = 1f): Color = Color(0xFF000000.toInt() or rgb).copy(alpha = alpha)

/** "#RRGGBB" (MapLibre) */
fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

/** "rgba(r, g, b, a)" (MapLibre) */
fun rgba(rgb: Int, alpha: Float): String = "rgba(${rgb shr 16 and 255}, ${rgb shr 8 and 255}, ${rgb and 255}, $alpha)"

/**
 * 한 배색에서 나오는 모든 색 — 발자취 `explorePaletteOf` + `exploreUiColorsOf` + 화면들의 섞은 색.
 */
@Immutable
class Palette(val def: Paper) {
    val dark: Boolean = def.dark
    val id: String get() = def.id

    // ── 지도·배색 그대로
    val paper = rgbColor(def.paper)
    val ink = rgbColor(def.ink)
    val inkSoft = rgbColor(def.ink, 0.34f)
    val fresh = rgbColor(def.fresh)
    val text = rgbColor(def.text)
    val textSoft = rgbColor(def.textSoft)
    val button = rgbColor(def.button)
    val buttonIcon = rgbColor(def.buttonIcon)
    val halo = rgbColor(def.halo)

    // ── 하위 화면·판(exploreUiColorsOf)
    /** 화면 바탕: 종이보다 한 톤 짙게 */
    val app = rgbColor(mixRgb(def.paper, def.ink, if (dark) 0.03f else 0.035f))
    val surface = button
    val surfaceStrong = if (dark) Color.White.copy(alpha = 0.10f) else rgbColor(def.ink, 0.07f)
    val textPrimary = text
    val textSecondary = textSoft
    val textMuted = rgbColor(def.textSoft, if (dark) 0.75f else 0.8f)
    /** ink 바탕 위 글자 */
    val textInverse = if (dark) paper else Color.White
    val dividerSubtle = if (dark) Color.White.copy(alpha = 0.08f) else rgbColor(def.ink, 0.08f)
    /** 강조(누를 수 있는 값·깃발·'도착'·첫 방문 꼬리표에만) */
    val blue = fresh
    val danger = if (dark) Color(0xFFFF9A93) else Color(0xFFB42318)

    // ── 섞은 색
    /** 기록 중 지난 선(불투명하게 섞음 — 겹친 곳이 진해지지 않게) */
    val pastLineRgb: Int = mixRgb(def.ink, def.paper, 0.55f)
    val pastLine = rgbColor(pastLineRgb)
    /** 무채색 알약 */
    val neutralPill = rgbColor(mixRgb(def.button, def.ink, if (dark) 0.14f else 0.08f))
    /** 꺼진 알약 바탕·글자 */
    val busyFillRgb: Int = mixRgb(def.button, def.ink, 0.32f)
    val busyFill = rgbColor(busyFillRgb)
    val busyText = rgbColor(mixRgb(busyFillRgb, if (dark) def.paper else 0xFFFFFF, 0.32f))
    /** 지도 위 글자 받침(실제 지도를 겹칠 때) */
    val scrim = rgbColor(def.paper, 0.94f)

    // ── 지도 스타일 글
    val paperHex: String = hex(def.paper)
    val inkHex: String = hex(def.ink)
    val pastLineHex: String = hex(pastLineRgb)
    val haloHex: String = hex(def.halo)
    val gridColor: String = if (dark) "rgba(255, 255, 255, 0.05)" else "rgba(0, 0, 0, 0.063)"
    /** 내 점 테: 밝은 종이는 흰색, 어두운 종이는 종이색 */
    val puckRingRgb: Int = if (dark) def.paper else 0xFFFFFF

    override fun equals(other: Any?): Boolean = other is Palette && other.def == def

    override fun hashCode(): Int = def.hashCode()
}

val LocalPalette = staticCompositionLocalOf { Palette(PAPERS[0]) }
val LocalLook = staticCompositionLocalOf { Look() }

/** 꾸미기를 기기에 기억한다(SharedPreferences). */
class LookStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("odomap.look", Context.MODE_PRIVATE)
    private val _look = MutableStateFlow(read())
    val look: StateFlow<Look> = _look.asStateFlow()

    private fun read(): Look {
        val paper = prefs.getString("paper", AUTO_PAPER)?.takeIf { id -> id == AUTO_PAPER || PAPERS.any { it.id == id } } ?: AUTO_PAPER
        val line = LineStyle.entries.firstOrNull { it.id == prefs.getString("line", null) } ?: LineStyle.SOLID
        val marker = Marker.entries.firstOrNull { it.id == prefs.getString("marker", null) } ?: Marker.DOT
        return Look(paper, line, marker)
    }

    fun set(next: Look) {
        _look.value = next
        prefs.edit().putString("paper", next.paper).putString("line", next.line.id).putString("marker", next.marker.id).apply()
    }

    fun update(change: (Look) -> Look) = set(change(_look.value))
}
