package com.odomap.app.ui.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 크기 — 발자취 pt × 1.75 (design.md 1). 발자취의 pt 값을 그대로 쓰고 그리는 밀도를 시스템 밀도 × 1.75로 바꾼다(창 높이가 900dp 밑인
 * 작은 화면 L-12.9는 × 1.6). 아래의 dp는 모두 발자취 pt다. 지도(MapLibre pixelRatio)도 같은 배율.
 */
const val CAR_SCALE = 1.75f
const val CAR_SCALE_SMALL = 1.6f

/** 시스템 밀도 × 배율(px / pt) — 지도 pixelRatio */
val LocalPxPerPt = staticCompositionLocalOf { CAR_SCALE }

/** 창 높이(시스템 dp)로 고른 배율 */
fun carScaleFor(windowHeightDp: Float): Float = if (windowHeightDp < 900f) CAR_SCALE_SMALL else CAR_SCALE

/** 좁은 창(폭 < 600pt)은 발자취 탭 그대로, 넓은 창은 왼쪽 판 */
const val WIDE_MIN_PT = 600f

@Composable
fun CarScale(content: @Composable () -> Unit) {
    val system = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = carScaleFor(maxHeight.value)
        val density = Density(system.density * scale, fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides density, LocalPxPerPt provides density.density) { content() }
    }
}
