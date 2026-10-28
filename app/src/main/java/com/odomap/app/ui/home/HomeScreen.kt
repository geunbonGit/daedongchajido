package com.odomap.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.odomap.app.engine.LiveDrive
import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.Fix
import com.odomap.app.record.NetState
import com.odomap.app.ui.design.CompassChip
import com.odomap.app.ui.design.HLine
import com.odomap.app.ui.design.Icons
import com.odomap.app.ui.design.LocalLook
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.PanelShadow
import com.odomap.app.ui.design.RoundButton
import com.odomap.app.ui.design.T
import com.odomap.app.ui.design.ToolItem
import com.odomap.app.ui.design.ToolPill
import com.odomap.app.ui.design.Type
import com.odomap.app.ui.design.W
import com.odomap.app.ui.design.WIDE_MIN_PT
import com.odomap.app.ui.design.card
import com.odomap.app.ui.design.circle
import com.odomap.app.ui.design.press
import com.odomap.app.ui.design.swallowTouches
import com.odomap.app.ui.design.ts
import com.odomap.app.ui.map.Insets
import com.odomap.app.ui.map.MapContent
import com.odomap.app.ui.map.MapController
import com.odomap.app.ui.map.MapFlag
import com.odomap.app.ui.map.NetTone
import com.odomap.app.ui.map.OdoMap
import com.odomap.app.ui.map.Puck
import com.odomap.app.ui.map.drawPuck
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 도구가 화면 가장자리에서 떨어진 거리 · 판과 그 위 현재 위치 사이 · 지도 위 끝 여백 */
private const val SIDE = 16f
private const val LOCATE_GAP = 12f
private const val TOP_SPACE = 16f
/** 넓은 창의 왼쪽 판 */
const val PANEL_W = 360f

/** 줌(구글): 주행 중 14.5, 고속(> 80km/h) 13.5, 정차 중 내 위치 15.5 */
private const val ZOOM_DRIVE = 14.5
private const val ZOOM_FAST = 13.5
private const val ZOOM_PARKED = 15.5

/** 홈이 받는 것(값이 바뀔 때만 다시 그린다) */
@Immutable
data class HomeModel(
    val net: NetState,
    val live: LiveDrive?,
    val tail: String?,
    val puck: Fix?,
    val arrival: DriveRecord?,
    val drives: List<DriveRecord>,
    val upper: UpperMode,
    val restricted: Boolean,
    val realMapAvailable: Boolean,
    /** 지금 지도에서 진하게 볼 드라이브(도착 직후) */
    val focusDrive: DriveRecord?,
    /** 그 드라이브의 선·경로(도착 직후 — 이 드라이브만 진하게 + 출발·도착 깃발) */
    val focusLines: String? = null,
    val focusRoute: List<Pair<Double, Double>> = emptyList(),
    /** 판을 펼친 채 시작(시험) */
    val startExpanded: Boolean = false,
    val startRecordsIndex: Int = 0,
)

@Immutable
data class HomeActions(
    val onUnvisited: () -> Unit,
    val onCloseArrival: () -> Unit,
    val onReplay: () -> Unit,
    val onShare: () -> Unit,
    /** 시연 패널 열기(판 머리 '오늘'·주행 중 이름을 길게 — 화면에 보이는 단추 없음) */
    val onDemoPanel: () -> Unit,
    val onBlocked: () -> Unit,
)

/**
 * 홈(design.md 4.1): 지도 전체 + 좁은 창은 아래 판(두 자리), 넓은 창은 왼쪽 판(폭 360, 위·아래·왼쪽 16 띄움). 오른쪽 위 도구 묶음, 현재 위치
 * (좁은 창은 판 위 오른쪽, 넓은 창은 오른쪽 아래), 화면 밖 내 길 길잡이. 왼쪽 위는 비운다(로고 없음). 판 머리 '오늘'을 길게 누르면 시연 패널.
 * records는 판을 크게 열었을 때(넓은 창은 판 아래) 보이는 기록.
 */
@Composable
fun HomeScreen(
    model: HomeModel,
    actions: HomeActions,
    controller: MapController,
    records: @Composable (Modifier, androidx.compose.ui.input.nestedscroll.NestedScrollConnection?, LazyListState, Float, () -> Unit) -> Unit,
    animateNumbers: Boolean,
) {
    val p = LocalPalette.current
    val look = LocalLook.current
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val dir = LocalLayoutDirection.current
    val top = with(density) { insets.getTop(this).toDp().value }
    val bottomInset = with(density) { insets.getBottom(this).toDp().value }
    val leftInset = with(density) { insets.getLeft(this, dir).toDp().value }
    val rightInset = with(density) { insets.getRight(this, dir).toDp().value }

    var realMap by rememberSaveable { mutableStateOf(false) }
    var following by rememberSaveable { mutableStateOf(model.live != null) }
    var fittedOnce by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberSheetState(model.startExpanded)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = model.startRecordsIndex)
    val driving = model.live != null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val wide = w >= WIDE_MIN_PT
        // 판 자리(좁은 창): 작게 = 손잡이 + 윗부분 + 아래 여백, 크게 = 창 − 위 − 64
        val small = HANDLE_H + UPPER_H + SHEET_BOTTOM + bottomInset
        val large = h - top - 64f
        LaunchedEffect(small, large, wide) { sheet.setPoints(small, large) }
        sheet.locked = model.restricted
        LaunchedEffect(model.restricted) { if (model.restricted) sheet.collapse() }

        // 지도에서 보이는 칸(카메라의 62% 자리 · 맞추기 여백)
        val panelRight = if (wide) leftInset + SIDE + PANEL_W else 0f
        val visibleTop = top + TOP_SPACE
        val visibleBottom = if (wide) h - bottomInset - SIDE else h - small - LOCATE_GAP
        val visibleLeft = panelRight
        val focusX = visibleLeft + (w - visibleLeft) / 2
        val focusY = visibleTop + (visibleBottom - visibleTop) * 0.62f

        val puck = model.puck?.let { Puck(it.lat, it.lng, it.bearing, it.acc) }
        val puckFix by rememberUpdatedState(model.puck)

        // ── 카메라
        // 기록이 시작되면 따라간다
        LaunchedEffect(driving) { if (driving) following = true }
        // 따라가기: 위치마다(실차 1초, 시연은 더 자주) 그 사이 시간만큼 선형으로
        var lastFollowAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(following, model.puck, focusX, focusY, driving) {
            val fix = puckFix ?: return@LaunchedEffect
            if (!following) return@LaunchedEffect
            val now = System.nanoTime() / 1_000_000
            val gap = (now - lastFollowAt).coerceIn(80L, 1000L)
            val first = lastFollowAt == 0L || now - lastFollowAt > 3000
            lastFollowAt = now
            val speedKmh = (fix.speed ?: 0f) * 3.6f
            val zoom = if (!driving) ZOOM_PARKED else if (speedKmh > 80f) ZOOM_FAST else ZOOM_DRIVE
            controller.follow(fix.lat, fix.lng, zoom, focusX, focusY, if (first) 0 else gap.toInt())
        }
        // 처음: 내 지도 전체(따라가지 않을 때)
        val fitPadding = Insets(left = visibleLeft + 68f, top = visibleTop + 24f, right = 68f + rightInset, bottom = (h - visibleBottom) + 24f)
        LaunchedEffect(model.net.loaded, model.net.bounds, wide) {
            if (fittedOnce || following) return@LaunchedEffect
            val b = model.net.bounds ?: return@LaunchedEffect
            controller.fit(b, fitPadding, animate = false)
            fittedOnce = true
        }
        // 도착하면 그 드라이브 전체
        LaunchedEffect(model.focusDrive?.id) {
            val b = model.focusDrive?.bounds ?: return@LaunchedEffect
            following = false
            // 깃발(위로 30pt)이 잘리지 않게 위 여백을 더
            controller.fit(b, fitPadding.copy(top = fitPadding.top + 30f), animate = true)
        }
        val fitAll: () -> Unit = {
            following = false
            model.net.bounds?.let { controller.fit(it, fitPadding, animate = true) }
        }

        val content =
            MapContent(
                net = model.net.geoJson,
                netVersion = model.net.version,
                netTone = if (driving || model.focusLines != null) NetTone.PAST else NetTone.INK,
                drive = if (driving) null else model.focusLines,
                live = model.live?.let { live -> listOf(live.inkGeoJson, live.newGeoJson).joinGeo() },
                tail = model.tail,
                dots = model.net.dotsGeoJson,
                flags =
                    if (driving || model.focusLines == null || model.focusRoute.size < 2) {
                        emptyList()
                    } else {
                        listOf(MapFlag(model.focusRoute.first().first, model.focusRoute.first().second, false), MapFlag(model.focusRoute.last().first, model.focusRoute.last().second, true))
                    },
            )
        OdoMap(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            content = content,
            realMap = realMap,
            puck = puck,
            puckScreenFixed = following && puck != null,
            initialGoogleZoom = 12.0,
            onUserGesture = {
                following = false
                if (!wide) sheet.collapse()
            },
        )

        // 따라가는 동안의 내 점(화면 고정 — 카메라 가운데 자리)
        if (following && puck != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawPuck(Offset(focusX * density.density, focusY * density.density), puck.bearing, p, look.marker)
            }
        }

        val expandedFade = if (wide) 1f else (1f - sheet.progress * 4f).coerceIn(0f, 1f)

        // ── 화면 밖 내 길 길잡이(보이는 칸에 내 길이 하나도 없을 때)
        val viewport = controller.viewport
        val guide = remember(viewport, model.net.tiles) { viewport?.let { nearestOffscreen(model.net.tiles.map { it.lat to it.lng }, it.bounds) } }
        if (guide != null && !following) {
            val rect = floatArrayOf(visibleLeft + SIDE, top + 12f, w - SIDE - 44f - 8f, visibleBottom - 44f - 8f)
            val screenDeg = guide.bearing.toFloat()
            val pos = edgePoint(rect, screenDeg)
            Box(Modifier.offset { IntOffset(((pos.x - 46f) * density.density).roundToInt(), ((pos.y - 17f) * density.density).roundToInt()) }.graphicsLayer { alpha = expandedFade }) {
                CompassChip(guide.label, screenDeg) {
                    following = false
                    controller.fit(guide.bounds, fitPadding, animate = true)
                }
            }
        }

        // ── 오른쪽 위 도구 묶음: 공유 · 실제 지도 · 전체 보기
        ToolPill(
            listOf(
                ToolItem(Icons.share, "공유", dimmed = model.restricted) { if (model.restricted) actions.onBlocked() else actions.onShare() },
                ToolItem(Icons.foldedMap, "실제 지도", selected = realMap, dimmed = !model.realMapAvailable && !realMap) { realMap = !realMap },
                ToolItem(Icons.fitAll, "전체 보기", dimmed = model.net.bounds == null) { fitAll() },
            ),
            Modifier.align(Alignment.TopEnd).padding(top = (top + 12f).dp, end = (SIDE + rightInset).dp).graphicsLayer { alpha = expandedFade },
        )

        // 실제 지도 출처(켰을 때만)
        AnimatedVisibility(
            visible = realMap,
            enter = fadeIn(tween(700)),
            exit = fadeOut(tween(650)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = (visibleLeft + SIDE).dp, bottom = (h - visibleBottom + 6f).dp),
        ) {
            T("© OpenMapTiles © OpenStreetMap", Type.credit, p.textSecondary)
        }

        if (wide) {
            // 현재 위치: 오른쪽 아래
            RoundButton(
                Icons.crosshair, 22f,
                Modifier.align(Alignment.BottomEnd).padding(end = (SIDE + rightInset).dp, bottom = (SIDE + bottomInset).dp),
                selected = following,
                dimmed = model.puck == null,
            ) { if (model.puck != null) following = true }
            // 왼쪽 판: 윗부분(머리·숫자·알약)은 위에 고정, 아래로 기록이 스크롤. 주행 중엔 윗부분만(작게 고정)
            val panel =
                Modifier
                    .padding(start = (leftInset + SIDE).dp, top = (top + SIDE).dp, bottom = (bottomInset + SIDE).dp)
                    .width(PANEL_W.dp)
                    .let { if (model.restricted) it else it.fillMaxHeight() }
                    .card(p.surface, 28f, PanelShadow)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    .swallowTouches()
            Column(panel) {
                Spacer(Modifier.height(16.dp))
                SheetUpper(
                    model.upper, PANEL_W, animateNumbers,
                    onMyMap = fitAll,
                    onUnvisited = actions.onUnvisited,
                    onCloseArrival = actions.onCloseArrival,
                    onReplay = actions.onReplay,
                    onShare = actions.onShare,
                    onHeadLongPress = actions.onDemoPanel,
                )
                if (model.restricted) {
                    DrivingNote(Modifier)
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        records(Modifier.fillMaxSize(), null, listState, PANEL_W, fitAll)
                        Box(Modifier.fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(p.surface, p.surface.copy(alpha = 0f)))))
                    }
                }
            }
        } else {
            // 현재 위치: 판 위 오른쪽(판을 따라 움직이고, 펼치면 흐려진다)
            RoundButton(
                Icons.crosshair, 22f,
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset { IntOffset(0, -((sheet.shown + LOCATE_GAP) * density.density).roundToInt()) }
                    .padding(end = (SIDE + rightInset).dp)
                    .graphicsLayer { alpha = expandedFade },
                selected = following,
                dimmed = model.puck == null,
            ) { if (model.puck != null) following = true }
            BottomSheet(
                sheet,
                Modifier.align(Alignment.BottomCenter),
                upper = {
                    SheetUpper(
                        model.upper, w, animateNumbers,
                        onMyMap = { if (model.restricted) actions.onBlocked() else sheet.expand() },
                        onUnvisited = actions.onUnvisited,
                        onCloseArrival = actions.onCloseArrival,
                        onReplay = actions.onReplay,
                        onShare = actions.onShare,
                        onHeadLongPress = actions.onDemoPanel,
                    )
                },
                body = { m, connection ->
                    records(m.weightless(), connection, listState, w) {
                        sheet.collapse()
                        fitAll()
                    }
                },
            )
        }
    }
}

private fun Modifier.weightless(): Modifier = this.fillMaxWidth().fillMaxHeight()

/** 주행 중 왼쪽 판 아래(목록 대신) — 한 줄 */
@Composable
private fun DrivingNote(modifier: Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        T("기록·꾸미기는 정차 후에 볼 수 있어요", ts(14f, W.Medium, 19f), p.textMuted)
    }
}

// ───────────────────────── 화면 밖 내 길 길잡이(발자취 FootprintsCompass)

class Guide(val bearing: Double, val distanceM: Double, val bounds: Bounds, val label: String)

private const val RAD = PI / 180
private const val M_PER_DEG = 6371008.8 * PI / 180

private fun distM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val kx = M_PER_DEG * cos((aLat + bLat) / 2 * RAD)
    return hypot((bLng - aLng) * kx, (bLat - aLat) * M_PER_DEG)
}

private fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val kx = cos((aLat + bLat) / 2 * RAD)
    return (atan2((bLng - aLng) * kx, bLat - aLat) / RAD + 360) % 360
}

/** 거리 글: 1km 밑은 10m 단위, 10km 밑은 소수 한 자리, 넘으면 정수 km */
fun guideLabel(m: Double): String {
    val tens = maxOf(1L, Math.round(m / 10))
    if (tens < 100) return "${tens * 10}m"
    val tenths = Math.round(m / 100)
    if (tenths < 100) return "${tenths / 10}.${tenths % 10}km"
    return String.format(java.util.Locale.US, "%,dkm", Math.round(m / 1000))
}

/** 보이는 범위 안에 내 길(z14 조각)이 하나도 없으면 가장 가까운 쪽 */
fun nearestOffscreen(tiles: List<Pair<Double, Double>>, view: Bounds): Guide? {
    if (tiles.isEmpty()) return null
    val padLat = 0.009
    val padLng = 0.011
    if (tiles.any { (lat, lng) -> lat >= view.minLat - padLat && lat <= view.maxLat + padLat && lng >= view.minLng - padLng && lng <= view.maxLng + padLng }) return null
    val cLat = (view.minLat + view.maxLat) / 2
    val cLng = (view.minLng + view.maxLng) / 2
    var best = Double.MAX_VALUE
    var nearest: Pair<Double, Double>? = null
    for (t in tiles) {
        val d = distM(cLat, cLng, t.first, t.second)
        if (d < best) {
            best = d
            nearest = t
        }
    }
    val n = nearest ?: return null
    var minLat = n.first
    var maxLat = n.first
    var minLng = n.second
    var maxLng = n.second
    for (t in tiles) {
        if (distM(n.first, n.second, t.first, t.second) > 2500) continue
        minLat = minOf(minLat, t.first)
        maxLat = maxOf(maxLat, t.first)
        minLng = minOf(minLng, t.second)
        maxLng = maxOf(maxLng, t.second)
    }
    return Guide(bearing(cLat, cLng, n.first, n.second), best, Bounds(minLat - 0.006, minLng - 0.008, maxLat + 0.006, maxLng + 0.008), guideLabel(best))
}

/** 보이는 칸 가운데에서 방향(화면 기준 °, 위 = 0)으로 뻗은 선이 칸 가장자리(알약 반만큼 안쪽)와 만나는 자리 */
fun edgePoint(rect: FloatArray, deg: Float): Offset {
    val (l, t, r, b) = rect.toList()
    val cx = (l + r) / 2
    val cy = (t + b) / 2
    val halfW = maxOf(1f, (r - l) / 2 - 46f)
    val halfH = maxOf(1f, (b - t) / 2 - 17f)
    val dx = sin(deg * RAD).toFloat()
    val dy = -cos(deg * RAD).toFloat()
    val k = minOf(if (abs(dx) < 1e-6f) Float.MAX_VALUE else halfW / abs(dx), if (abs(dy) < 1e-6f) Float.MAX_VALUE else halfH / abs(dy))
    return Offset(cx + dx * k, cy + dy * k)
}

/** 같은 FeatureCollection 둘을 하나로(지금 선의 아는 길 + 처음 길 — 새 길을 다른 색으로 칠하지 않는다) */
fun List<String>.joinGeo(): String {
    val parts = mapNotNull { s ->
        val i = s.indexOf("\"features\":[")
        if (i < 0) return@mapNotNull null
        val body = s.substring(i + 12, s.lastIndexOf(']'))
        body.ifBlank { null }
    }
    return """{"type":"FeatureCollection","features":[${parts.joinToString(",")}]}"""
}

@Suppress("unused")
private val unusedRow: @Composable () -> Unit = { Row {} }
