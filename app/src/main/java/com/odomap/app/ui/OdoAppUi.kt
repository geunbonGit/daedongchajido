package com.odomap.app.ui

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.design.LocalLook
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.LocalToast
import com.odomap.app.ui.design.LookStore
import com.odomap.app.ui.design.Palette
import com.odomap.app.ui.design.SheetStat
import com.odomap.app.ui.design.ToastSlot
import com.odomap.app.ui.design.ToastState
import com.odomap.app.ui.design.paperOf
import com.odomap.app.ui.home.HomeActions
import com.odomap.app.ui.home.HomeModel
import com.odomap.app.ui.home.HomeScreen
import com.odomap.app.ui.home.UpperMode
import com.odomap.app.ui.map.MapController
import com.odomap.app.ux.UxRestrictions

/** 하위 화면(홈 위에 아래에서 올라온다) */
sealed interface Screen {
    val key: String

    data object Home : Screen { override val key = "home" }

    data class Drive(val id: String) : Screen { override val key = "drive:$id" }

    data object Regions : Screen { override val key = "regions" }

    data class Region(val code: String) : Screen { override val key = "region:$code" }

    data object Unvisited : Screen { override val key = "unvisited" }

    data class Share(val what: String) : Screen { override val key = "share:$what" }

    data class Replay(val what: String) : Screen { override val key = "replay:$what" }

    data object Settings : Screen { override val key = "settings" }

    companion object {
        fun of(key: String): Screen =
            when {
                key == "home" -> Home
                key.startsWith("drive:") -> Drive(key.removePrefix("drive:"))
                key == "regions" -> Regions
                key.startsWith("region:") -> Region(key.removePrefix("region:"))
                key == "unvisited" -> Unvisited
                key.startsWith("share:") -> Share(key.removePrefix("share:"))
                key.startsWith("replay:") -> Replay(key.removePrefix("replay:"))
                key == "settings" -> Settings
                else -> Home
            }
    }
}

/** 하위 화면이 올라오고 내려가는 움직임(발자취 시트: 300ms out-cubic 들어옴 · 220ms in-cubic 나감) */
private val OUT_CUBIC = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private val IN_CUBIC = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

const val BLOCKED_TOAST = "정차 후에 볼 수 있어요"

/** 처음 모습(시험·미리 보기용): 열린 하위 화면 · 꾸미기 · 판 펼침 */
data class UiStart(val stack: List<String> = listOf("home"), val look: Boolean = false, val expanded: Boolean = false, val demoOverride: Boolean = true, val recordsIndex: Int = 0)

/** Root: 배색·꾸미기·알림을 내려보내고, 홈 위에 하위 화면·꾸미기·시연 패널을 띄운다. 주행 중엔 하위 화면을 막는다. */
@Composable
fun OdoAppUi(recorder: DriveRecorder, looks: LookStore, hasLocation: Boolean, onAskPermission: () -> Unit, start: UiStart = UiStart()) {
    val context = LocalContext.current
    val look by looks.look.collectAsStateWithLifecycle()
    val night = isSystemInDarkTheme()
    val palette = remember(look.paper, night) { Palette(paperOf(look.paper, night)) }
    val toast = remember { ToastState() }

    val net by recorder.net.collectAsStateWithLifecycle()
    val live by recorder.live.collectAsStateWithLifecycle()
    val tail by recorder.tail.collectAsStateWithLifecycle()
    val puck by recorder.puck.collectAsStateWithLifecycle()
    val arrival by recorder.arrival.collectAsStateWithLifecycle()
    val replay by recorder.replayState.collectAsStateWithLifecycle()
    val drives by recorder.drives.collectAsStateWithLifecycle()
    val regionIndex by recorder.regions.collectAsStateWithLifecycle()
    val region by recorder.region.collectAsStateWithLifecycle()
    val visited by recorder.visited.collectAsStateWithLifecycle()
    val shapes by com.odomap.app.ui.regions.rememberRegionShapes()
    val sketch by com.odomap.app.ui.design.rememberSketch(net.geoJson, net.version)
    val uxFlow = remember { UxRestrictions.flow(context) }
    val uxRestricted by uxFlow.collectAsStateWithLifecycle(initialValue = null)

    val stack = rememberSaveable(saver = listSaver<MutableList<String>, String>({ it.toList() }, { it.toMutableStateList() })) { start.stack.toMutableStateList() }
    var showDemo by rememberSaveable { mutableStateOf(false) }
    var showLook by rememberSaveable { mutableStateOf(start.look) }
    var demoOverride by rememberSaveable { mutableStateOf(start.demoOverride) }
    val controller = remember { MapController() }

    val driving = live != null
    // 시연 중엔 시연 패널의 '주행 화면 제한 풀기'를 따른다. 그 밖엔 차의 주행 규제(CarUxRestrictions) — 차 API가 없으면 우리 주행 상태
    val restricted = if (replay.playing) driving && !demoOverride else (uxRestricted ?: driving)

    // 주행 규제가 걸리면 하위 화면·꾸미기를 닫는다
    LaunchedEffect(restricted) {
        if (restricted) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            showLook = false
        }
    }
    val open: (Screen) -> Unit = { s ->
        if (restricted) toast.show(BLOCKED_TOAST) else stack.add(s.key)
    }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    CompositionLocalProvider(LocalPalette provides palette, LocalLook provides look, LocalToast provides toast) {
        SystemBars(palette)
        Box(Modifier.fillMaxSize().background(palette.paper)) {
            val now = appNow(drives, live)
            val focus = if (driving) null else arrival
            val liveNumbers = rememberThrottled(live, 1000L)
            val focusDetail by androidx.compose.runtime.produceState(initialValue = null as com.odomap.app.record.DriveDetail?, focus?.id, net.version) {
                value = focus?.let { recorder.detail(it.id) }
            }
            val data = AppData(drives, net, now, regionIndex, visited, shapes, sketch, puck)
            val model =
                HomeModel(
                    net = net,
                    live = live,
                    tail = tail,
                    puck = puck,
                    arrival = if (driving) null else arrival,
                    drives = drives,
                    upper = upperMode(net, liveNumbers, puck, if (driving) null else arrival, drives, now, region?.name, visited.size),
                    startExpanded = start.expanded,
                    startRecordsIndex = start.recordsIndex,
                    restricted = restricted,
                    realMapAvailable = remember(context) { hasInternet(context) },
                    focusDrive = focus,
                    focusLines = focusDetail?.takeIf { it.record.id == focus?.id }?.linesGeoJson,
                    focusRoute = focusDetail?.takeIf { it.record.id == focus?.id }?.route ?: emptyList(),
                )
            HomeScreen(
                model = model,
                actions =
                    HomeActions(
                        onUnvisited = { open(Screen.Unvisited) },
                        onCloseArrival = { recorder.dismissArrival() },
                        onReplay = { arrival?.let { open(Screen.Replay(it.id)) } },
                        onShare = { open(Screen.Share(arrival?.id ?: "map")) },
                        onDemoPanel = { showDemo = true },
                        onBlocked = { toast.show(BLOCKED_TOAST) },
                    ),
                controller = controller,
                records = { modifier, _, listState, width, onMyMap ->
                    RecordsList(
                        modifier = modifier,
                        listState = listState,
                        width = width,
                        data = data,
                        recorder = recorder,
                        actions =
                            RecordsActions(
                                onOpenDrive = { open(Screen.Drive(it)) },
                                onLook = { if (restricted) toast.show(BLOCKED_TOAST) else showLook = true },
                                onShare = { open(Screen.Share("map")) },
                                onSettings = { open(Screen.Settings) },
                                onRegions = { open(Screen.Regions) },
                                onRegion = { open(Screen.Region(it)) },
                                onReplayAll = { open(Screen.Replay("all")) },
                                onMyMap = onMyMap,
                            ),
                    )
                },
                animateNumbers = true,
            )

            // 하위 화면
            for ((i, key) in stack.withIndex()) {
                if (i == 0) continue
                val screen = Screen.of(key)
                SubScreenHost(screen, recorder, data, back = back, open = open, onDemoPanel = { showDemo = true })
            }
            AnimatedVisibility(
                visible = showLook,
                enter = fadeIn(tween(300, easing = OUT_CUBIC)) + slideInVertically(tween(300, easing = OUT_CUBIC)) { it / 24 },
                exit = fadeOut(tween(220, easing = IN_CUBIC)),
            ) {
                LookSheet(looks, night, onClose = { showLook = false })
            }
            if (showDemo) {
                DemoPanel(
                    recorder = recorder,
                    replay = replay,
                    demoOverride = demoOverride,
                    onDemoOverride = { demoOverride = it },
                    onClose = { showDemo = false },
                    onPlayed = {
                        showDemo = false
                        while (stack.size > 1) stack.removeAt(stack.lastIndex)
                    },
                )
            }
            val top = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }
            // 알림: 지도 칸 가운데(넓은 창은 왼쪽 판을 뺀 칸), 도구 줄 아래
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val wideWindow = maxWidth.value >= com.odomap.app.ui.design.WIDE_MIN_PT && stack.size == 1
                Box(Modifier.fillMaxSize().padding(start = if (wideWindow) androidx.compose.ui.unit.Dp(16f + com.odomap.app.ui.home.PANEL_W) else androidx.compose.ui.unit.Dp(0f))) {
                    ToastSlot(top + androidx.compose.ui.unit.Dp(64f))
                }
            }
        }
    }
    BackHandler(enabled = stack.size > 1 || showLook || showDemo) {
        when {
            showDemo -> showDemo = false
            showLook -> showLook = false
            else -> back()
        }
    }
}

/**
 * 값이 자주 바뀌어도 화면에는 ms마다 한 번만(주행 중 숫자: 기록기는 0.25초마다 내지만 자리 넘김 300ms가 끝나게). null은 바로.
 */
@Composable
fun <T> rememberThrottled(value: T, ms: Long): T {
    var shown by remember { mutableStateOf(value) }
    var last by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val latest by androidx.compose.runtime.rememberUpdatedState(value)
    LaunchedEffect(value) {
        if (value == null || shown == null) {
            shown = value
            last = android.os.SystemClock.uptimeMillis()
            return@LaunchedEffect
        }
        val wait = last + ms - android.os.SystemClock.uptimeMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        shown = latest
        last = android.os.SystemClock.uptimeMillis()
    }
    return shown
}

private fun <T> List<T>.toMutableStateList() = mutableStateListOf<T>().also { it.addAll(this) }

/** 하위 화면 하나(아래에서 올라온다) */
@Composable
private fun SubScreenHost(
    screen: Screen,
    recorder: DriveRecorder,
    data: AppData,
    back: () -> Unit,
    open: (Screen) -> Unit,
    onDemoPanel: () -> Unit,
) {
    val visible = remember(screen.key) { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = slideInVertically(tween(300, easing = OUT_CUBIC)) { it / 10 } + fadeIn(tween(240)),
        exit = slideOutVertically(tween(220, easing = IN_CUBIC)) { it / 10 } + fadeOut(tween(200)),
    ) {
        when (screen) {
            is Screen.Drive -> DriveScreen(screen.id, recorder, data, back = back, open = open)
            is Screen.Share -> com.odomap.app.ui.share.ShareScreen(screen.what, recorder, data, back)
            is Screen.Replay -> com.odomap.app.ui.replay.ReplayScreen(screen.what, recorder, data, back, open)
            is Screen.Settings -> SettingsScreen(recorder, back, onDemoPanel)
            is Screen.Regions -> com.odomap.app.ui.regions.RegionsScreen(data, back, open)
            is Screen.Region -> com.odomap.app.ui.regions.RegionScreen(screen.code, data, back, open)
            is Screen.Unvisited -> com.odomap.app.ui.regions.UnvisitedScreen(data, back)
            Screen.Home -> Unit
        }
    }
}

/** 판 윗부분의 상태와 숫자 */
internal fun upperMode(
    net: com.odomap.app.record.NetState,
    live: com.odomap.app.engine.LiveDrive?,
    puck: com.odomap.app.model.Fix?,
    arrival: com.odomap.app.model.DriveRecord?,
    drives: List<com.odomap.app.model.DriveRecord>,
    now: Long,
    regionName: String?,
    visitedCount: Int,
): UpperMode {
    if (live != null) {
        val elapsed = ((puck?.t ?: now) - live.startedAt).coerceAtLeast(0L)
        return UpperMode.Driving(
            title = live.title ?: driveName(live.startedAt),
            stats =
                listOf(
                    SheetStat("driven", "이번 달린 길", kmText(live.distanceM), "km"),
                    SheetStat("fresh", "이번 처음 달린 길", kmText(live.pioneeredM), "km"),
                    SheetStat("region", "지금 지역", regionName ?: "-", number = false),
                ),
            clock = clockText(elapsed),
            km = kmLabel(live.distanceM),
        )
    }
    if (arrival != null) {
        return UpperMode.Arrived(
            time = timeOfDay(arrival.endedAt),
            stats =
                listOf(
                    SheetStat("driven", "이번 달린 길", kmText(arrival.distanceM), "km"),
                    SheetStat("fresh", "이번 처음 달린 길", kmText(arrival.pioneeredM), "km"),
                    SheetStat("first", "첫 방문", "${arrival.firstVisits.size}", "곳"),
                ),
        )
    }
    val today = seoulDay(now)
    val todays = drives.filter { seoulDay(it.startedAt) == today }
    return UpperMode.Today(
        mapKm = kmLabel(net.netLengthM),
        stats =
            listOf(
                SheetStat("driven", "오늘 달린 길", kmText(todays.sumOf { it.distanceM }), "km"),
                SheetStat("fresh", "오늘 처음 달린 길", kmText(todays.sumOf { it.pioneeredM }), "km"),
                SheetStat("visited", "가 본 곳", "$visitedCount", "/230"),
            ),
    )
}

/** 상태 바·내비 바 글자를 고른 종이 밝기에 맞춘다 */
@Composable
private fun SystemBars(palette: Palette) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !palette.dark
        c.isAppearanceLightNavigationBars = !palette.dark
    }
}

private fun hasInternet(context: Context): Boolean =
    runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
