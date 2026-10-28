package com.odomap.app.ui.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.model.Bounds

/**
 * true → [OdoMap] draws with a plain Compose Canvas instead of MapLibre (JVM screenshot tests, where the GL map cannot run).
 * Same layers and colours (paper, grid, boundaries, lines by level, puck), flat web-mercator projection, no tiles.
 */
val LocalCanvasMap = staticCompositionLocalOf { false }

/** 내 길 선 색: 잉크 · 기록 중 지난 선(mix 0.55) */
enum class NetTone { INK, PAST }

/** 지도에 그릴 것(GeoJSON 글은 엔진이 만든다 — 같은 글이면 다시 넣지 않는다) */
@Immutable
data class MapContent(
    /** 내 지도(속성 level 1..5) */
    val net: String = EMPTY_FEATURES,
    val netVersion: Int = 0,
    val netTone: NetTone = NetTone.INK,
    /** 한 드라이브만 진하게(드라이브 화면) — 잉크 + 종이 테두리 */
    val drive: String? = null,
    /** 지금 그리는 선(s 1.15 + 종이 테두리): 다듬어진 몫 · 아직 다듬지 않은 꼬리 */
    val live: String? = null,
    val tail: String? = null,
    /** 멀리 볼 때 점(속성 w = 이음 수) */
    val dots: String = EMPTY_FEATURES,
    /** 시·군·구 경계 머리카락 선(구글 줌 7~12) */
    val boundaries: Boolean = true,
    /** 출발·도착 깃발 */
    val flags: List<MapFlag> = emptyList(),
    /** 모눈 */
    val grid: Boolean = true,
)

@Immutable
data class MapFlag(val lat: Double, val lng: Double, val end: Boolean)

/** 내 점: 자리 · 진행 방향(°, 모르면 null) · 오차(m) */
@Immutable
data class Puck(val lat: Double, val lng: Double, val bearing: Float?, val accuracyM: Float?)

/**
 * 지도 카메라 명령(화면이 정한다) + 지금 보이는 범위. MapLibre 지도와 JVM 캔버스 지도가 같은 것을 쓴다.
 * 명령은 마지막 것만 기억했다가 지도가 준비되면 바로(움직임 없이) 한다.
 */
class MapController {
    sealed interface Command {
        /** 내 점을 보이는 칸의 focus(pt)에 — durationMs 동안 선형으로 */
        data class Follow(val lat: Double, val lng: Double, val googleZoom: Double, val focusX: Float, val focusY: Float, val durationMs: Int) : Command

        /** 범위가 padding(pt) 안쪽에 다 보이게 */
        data class Fit(val bounds: Bounds, val padding: Insets, val animate: Boolean, val minSpanM: Double = 400.0, val maxGoogleZoom: Double = 16.0) : Command

        data class MoveTo(val lat: Double, val lng: Double, val googleZoom: Double, val durationMs: Int) : Command
    }

    /** 바뀔 때마다 1씩(같은 명령을 다시 보내도 지도가 따른다) */
    var serial by mutableStateOf(0)
        private set
    var command: Command? = null
        private set

    /** 손·명령으로 멈춘 뒤의 보이는 범위(구글 줌) */
    var viewport by mutableStateOf<Viewport?>(null)
        internal set

    /** 지도 크기(pt) */
    var size by mutableStateOf(0f to 0f)
        internal set

    fun send(c: Command) {
        command = c
        serial += 1
    }

    fun follow(lat: Double, lng: Double, googleZoom: Double, focusX: Float, focusY: Float, durationMs: Int) =
        send(Command.Follow(lat, lng, googleZoom, focusX, focusY, durationMs))

    fun fit(bounds: Bounds, padding: Insets, animate: Boolean = true, minSpanM: Double = 400.0, maxGoogleZoom: Double = 16.0) =
        send(Command.Fit(bounds, padding, animate, minSpanM, maxGoogleZoom))

    fun moveTo(lat: Double, lng: Double, googleZoom: Double, durationMs: Int = 0) = send(Command.MoveTo(lat, lng, googleZoom, durationMs))
}
