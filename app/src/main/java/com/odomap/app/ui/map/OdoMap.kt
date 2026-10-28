package com.odomap.app.ui.map

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.odomap.app.model.Bounds
import com.odomap.app.ui.design.LocalLook
import com.odomap.app.ui.design.LocalPalette
import com.odomap.app.ui.design.LocalPxPerPt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.floor

/** 서울시청 둘레 — 지도가 비었을 때 처음 카메라 */
val HOME_LAT = 37.5665
val HOME_LNG = 126.978

/**
 * 종이 지도(MapLibre). 층은 [MapLayers], 카메라 명령은 [MapController]. 데이터는 GeoJSON 글(엔진이 만든다).
 * @param realMap 실제 지도를 겹친다(켤 때 700ms · 끌 때 650ms, 끈 뒤 20초면 감춤)
 * @param puck 내 점. puckScreenFixed면 지도 위 점은 숨긴다(따라가는 동안 화면이 고정한 점을 그린다)
 * @param onUserGesture 손으로 지도를 움직이기 시작했다(따라가기를 멈춘다)
 */
@Composable
fun OdoMap(
    modifier: Modifier,
    controller: MapController,
    content: MapContent,
    realMap: Boolean = false,
    puck: Puck? = null,
    puckScreenFixed: Boolean = false,
    gestures: Boolean = true,
    initialGoogleZoom: Double = 12.0,
    onUserGesture: () -> Unit = {},
) {
    if (LocalCanvasMap.current) {
        CanvasMap(modifier, controller, content, puck, puckScreenFixed)
        return
    }
    val context = LocalContext.current
    val pxPerPt = LocalPxPerPt.current
    val palette = LocalPalette.current
    val look = LocalLook.current
    val mapView =
        remember(pxPerPt) {
            val options =
                MapLibreMapOptions.createFromAttributes(context)
                    .pixelRatio(pxPerPt)
                    .textureMode(true)
                    .logoEnabled(false)
                    .compassEnabled(false)
                    .attributionEnabled(false)
                    .attributionGravity(Gravity.BOTTOM or Gravity.START)
                    .localIdeographFontFamily("sans-serif")
                    .camera(CameraPosition.Builder().target(LatLng(HOME_LAT, HOME_LNG)).zoom(initialGoogleZoom - ZOOM_OFFSET).build())
            MapView(context, options)
        }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var layers by remember(mapView) { mutableStateOf<MapLayers?>(null) }
    var grid by remember(mapView) { mutableStateOf<PaperGrid?>(null) }
    val gestureCallback by rememberUpdatedState(onUserGesture)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var created = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {
                        mapView.onCreate(null)
                        created = true
                    }
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gestureCallback()
            }
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (created) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    // 스타일: 한 번만 읽는다(배색·선이 바뀌어도 다시 읽지 않는다 — 층만 바꾼다)
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        val json = withContext(Dispatchers.IO) { MapStyleJson.blank(context, palette.dark) }
        val boundary = withContext(Dispatchers.IO) { BoundaryAsset.load(context) }
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            val l = MapLayers(context, style, pxPerPt)
            l.install(palette, look.line, look.marker, boundary)
            layers = l
        }
    }
    // 보이는 범위: 카메라가 멈출 때마다(손·명령) — 모눈을 다시 만들고 화면에 알린다
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val idle =
            MapLibreMap.OnCameraIdleListener {
                val cam = m.cameraPosition
                val target = cam.target ?: return@OnCameraIdleListener
                val region = m.projection.visibleRegion.latLngBounds
                val b = Bounds(region.latitudeSouth, region.longitudeWest, region.latitudeNorth, region.longitudeEast)
                controller.viewport = Viewport(target.latitude, target.longitude, cam.zoom + ZOOM_OFFSET, b, cam.bearing)
                val built = grid
                if (built == null || built.zoomFloor != floor(cam.zoom).toInt() || !boxInside(b.toGridBox(), built.box)) grid = buildPaperGrid(b.toGridBox(), cam.zoom)
            }
        m.addOnCameraIdleListener(idle)
        onDispose { m.removeOnCameraIdleListener(idle) }
    }

    LaunchedEffect(layers, palette, look.marker) { layers?.setPalette(palette, look.marker) }
    LaunchedEffect(layers, look.line) { layers?.setLineStyle(look.line) }
    LaunchedEffect(layers, grid, content.grid) { layers?.setGrid(grid, content.grid) }
    LaunchedEffect(layers, content.boundaries) { layers?.setBoundaries(content.boundaries) }
    LaunchedEffect(layers, content.netVersion, content.net) { layers?.setNet(content.net) }
    LaunchedEffect(layers, content.netTone) { layers?.setNetTone(content.netTone) }
    LaunchedEffect(layers, content.drive) { layers?.setDrive(content.drive) }
    LaunchedEffect(layers, content.live, content.tail) { layers?.setLive(content.live, content.tail) }
    LaunchedEffect(layers, content.dots) { layers?.setDots(content.dots) }
    LaunchedEffect(layers, content.flags) { layers?.setFlags(content.flags) }
    LaunchedEffect(layers, puck, puckScreenFixed) { layers?.setPuck(puck, puckScreenFixed) }

    // 실제 지도: off → hidden(진하기 0으로 붙임) → shown, 끄면 hidden → 20초 뒤 parked
    var realPhase by remember(mapView) { mutableStateOf("off") }
    LaunchedEffect(layers, realMap, palette.dark) {
        val l = layers ?: return@LaunchedEffect
        if (realMap) {
            if (realPhase == "off" || realPhase == "parked") {
                l.setRealMap("hidden", palette.dark)
                delay(60)
            }
            l.setRealMap("shown", palette.dark)
            realPhase = "shown"
        } else if (realPhase == "shown" || realPhase == "hidden") {
            l.setRealMap("hidden", palette.dark)
            realPhase = "hidden"
            delay(650 + 20_000)
            l.setRealMap("parked", palette.dark)
            realPhase = "parked"
        }
    }

    // 카메라 명령
    LaunchedEffect(map, controller.serial) {
        val m = map ?: return@LaunchedEffect
        val cmd = controller.command ?: return@LaunchedEffect
        while (mapView.width == 0 || mapView.height == 0) delay(30)
        val wPt = mapView.width / pxPerPt
        val hPt = mapView.height / pxPerPt
        when (cmd) {
            is MapController.Command.Follow -> {
                val pad = focusPadding(cmd.focusX, cmd.focusY, wPt, hPt)
                val pos = CameraPosition.Builder()
                    .target(LatLng(cmd.lat, cmd.lng))
                    .zoom(cmd.googleZoom - ZOOM_OFFSET)
                    .bearing(0.0)
                    .tilt(0.0)
                    .padding((pad.left * pxPerPt).toDouble(), (pad.top * pxPerPt).toDouble(), (pad.right * pxPerPt).toDouble(), (pad.bottom * pxPerPt).toDouble())
                    .build()
                val update = CameraUpdateFactory.newCameraPosition(pos)
                if (cmd.durationMs > 0) m.easeCamera(update, cmd.durationMs, false) else m.moveCamera(update)
                // 따라가는 동안은 카메라가 쉬지 않아 멈춤 알림이 드물다 — 가는 자리 둘레가 만든 모눈 밖이 되기 전에 다시 만든다
                val view = approxViewBox(cmd.lat, cmd.lng, cmd.googleZoom - ZOOM_OFFSET, wPt, hPt)
                val built = grid
                if (built == null || built.zoomFloor != floor(cmd.googleZoom - ZOOM_OFFSET).toInt() || !boxInside(view, built.box)) {
                    grid = buildPaperGrid(view, cmd.googleZoom - ZOOM_OFFSET)
                }
            }
            is MapController.Command.Fit -> {
                val cam = fitCamera(cmd.bounds, wPt, hPt, cmd.padding, cmd.minSpanM, cmd.maxGoogleZoom)
                val pos = CameraPosition.Builder()
                    .target(LatLng(cam.lat, cam.lng))
                    .zoom(cam.zoom)
                    .bearing(0.0)
                    .tilt(0.0)
                    .padding((cam.padding.left * pxPerPt).toDouble(), (cam.padding.top * pxPerPt).toDouble(), (cam.padding.right * pxPerPt).toDouble(), (cam.padding.bottom * pxPerPt).toDouble())
                    .build()
                val update = CameraUpdateFactory.newCameraPosition(pos)
                if (cmd.animate) m.easeCamera(update, 450) else m.moveCamera(update)
            }
            is MapController.Command.MoveTo -> {
                val pos = CameraPosition.Builder().target(LatLng(cmd.lat, cmd.lng)).zoom(cmd.googleZoom - ZOOM_OFFSET).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
                val update = CameraUpdateFactory.newCameraPosition(pos)
                if (cmd.durationMs > 0) m.easeCamera(update, cmd.durationMs) else m.moveCamera(update)
            }
        }
    }

    AndroidView(
        factory = {
            mapView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> controller.size = (v.width / pxPerPt) to (v.height / pxPerPt) }
            mapView
        },
        modifier = modifier,
        update = { v -> v.isEnabled = gestures },
    )
    LaunchedEffect(map, gestures) {
        map?.uiSettings?.apply {
            isScrollGesturesEnabled = gestures
            isZoomGesturesEnabled = gestures
            isDoubleTapGesturesEnabled = gestures
        }
    }
}
