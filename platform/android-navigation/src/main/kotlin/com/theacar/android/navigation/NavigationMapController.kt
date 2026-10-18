package com.theacar.android.navigation

import android.content.Context
import com.theacar.model.Coordinate
import com.theacar.location.LocationStatus
import com.theacar.navigation.NavigationPhase
import com.theacar.route.Destination
import com.theacar.route.ScenicRange
import com.theacar.route.RouteGeometryIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

enum class MapDisplayState { LOADING, READY, ERROR, DISABLED }

class NavigationMapController(context: Context, private val runtime: AndroidNavigationRuntime, private val scope: CoroutineScope) {
    val view: MapView
    var onLongClick: ((Coordinate) -> Unit)? = null
    var onDestinationSelected: ((Destination) -> Unit)? = null
    private val mutableDisplayState = MutableStateFlow(MapDisplayState.LOADING)
    val displayState = mutableDisplayState.asStateFlow()
    private var styleLoaded = false
    private var map: MapLibreMap? = null
    private var routeLine: Polyline? = null
    private val alternativeLines = mutableListOf<Polyline>()
    private val evidenceLines = mutableListOf<Polyline>()
    private val sceneryLines = mutableListOf<Polyline>()
    private var sceneryRequest: List<Pair<ScenicRange, Int>> = emptyList()
    private var evidenceMarker: Marker? = null
    private var evidenceFocus = emptyList<LatLng>()
    private var alternativesKey: String? = null
    private var locationMarker: Marker? = null
    private var selectedMarker: Marker? = null
    private var selectedDestination: Destination? = null
    private val waypointMarkers = mutableListOf<Marker>()
    private var waypointPoints = emptyList<Coordinate>()
    private var routeKey: String? = null
    private var sceneryKey: String? = null
    private var follow = true
    private var closed = false
    private var previousPhase: NavigationPhase? = null
    private var contentPadding = intArrayOf(0, 0, 0, 0)
    private var loadTimeout: Job? = null
    private val locationIcon = locationIcon(context, android.graphics.Color.rgb(34, 112, 215))
    private val staleLocationIcon = locationIcon(context, android.graphics.Color.rgb(120, 130, 140))

    private fun locationIcon(context: Context, color: Int): org.maplibre.android.annotations.Icon {
        val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size * .48f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size * .33f, paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }
    private val observation: Job

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener {
            if (!closed) { loadTimeout?.cancel(); mutableDisplayState.value = MapDisplayState.ERROR }
        }
        view.addOnDidFinishRenderingMapListener { fullyRendered ->
            if (!closed && styleLoaded && fullyRendered && runtime.configuration.mapStyleUrl.isNotBlank()) {
                loadTimeout?.cancel()
                mutableDisplayState.value = MapDisplayState.READY
            }
        }
        view.getMapAsync { result ->
            if (closed) return@getMapAsync
            map = result
            result.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(43.061, 141.354), 12.0))
            reloadMap()
            result.addOnMapClickListener { point ->
                selectPoint(point)
                true
            }
            result.addOnMapLongClickListener { point ->
                if (onDestinationSelected != null) selectPoint(point)
                else onLongClick?.invoke(Coordinate(point.latitude, point.longitude))
                true
            }
            result.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    follow = false
                    evidenceFocus = emptyList()
                }
            }
        }
        observation = scope.launch {
            combine(runtime.session.state, runtime.locations.location) { _, _ -> Unit }.collect { render() }
        }
    }

    fun reloadMap() {
        evidenceFocus = emptyList()
        val target = map ?: return
        evidenceMarker?.let(target::removeMarker)
        evidenceMarker = null
        routeLine?.let(target::removePolyline)
        alternativeLines.forEach(target::removePolyline)
        alternativeLines.clear()
        evidenceLines.forEach(target::removePolyline)
        evidenceLines.clear()
        sceneryLines.forEach(target::removePolyline)
        sceneryLines.clear()
        sceneryKey = null
        alternativesKey = null
        locationMarker?.let(target::removeMarker)
        selectedMarker?.let(target::removeMarker)
        waypointMarkers.forEach(target::removeMarker)
        waypointMarkers.clear()
        routeLine = null
        locationMarker = null
        selectedMarker = null
        routeKey = null
        styleLoaded = false
        mutableDisplayState.value = MapDisplayState.LOADING
        loadTimeout?.cancel()
        loadTimeout = scope.launch {
            delay(25_000)
            if (!closed && mutableDisplayState.value == MapDisplayState.LOADING) mutableDisplayState.value = MapDisplayState.ERROR
        }
        val style = if (runtime.configuration.mapStyleUrl.isBlank()) {
            Style.Builder().fromJson("{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#e8eeea\"}}]}")
        } else Style.Builder().fromUri(runtime.configuration.mapStyleUrl)
        target.setStyle(style) {
            if (closed) return@setStyle
            styleLoaded = true
            applyTabiMapColors(it)
            if (runtime.configuration.mapStyleUrl.isBlank()) {
                loadTimeout?.cancel()
                mutableDisplayState.value = MapDisplayState.DISABLED
            }
            applyMapPadding(target)
            showWaypoints(waypointPoints)
            showSelectedDestination(selectedDestination)
            selectedDestination?.let(::focusDestination)
            render()
        }
    }

    private fun selectPoint(point: LatLng) {
        val target = map ?: return
        if (!styleLoaded) return
        if (runtime.session.state.value.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW)) return
        val layers = target.style?.layers?.filterIsInstance<SymbolLayer>()?.map { it.id }.orEmpty()
        val name = if (layers.isEmpty()) null else {
            target.queryRenderedFeatures(target.projection.toScreenLocation(point), *layers.toTypedArray())
                .firstNotNullOfOrNull { feature ->
                    listOf("name:ja", "name:nonlatin", "name", "name:latin").firstNotNullOfOrNull { property ->
                        runCatching { feature.getStringProperty(property) }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
        }
        follow = false
        onDestinationSelected?.invoke(Destination("map:${point.latitude},${point.longitude}", name ?: "地図で選択した場所", Coordinate(point.latitude, point.longitude)))
    }

    fun showSelectedDestination(destination: Destination?) {
        selectedDestination = destination
        if (destination != null) evidenceFocus = emptyList()
        val target = map ?: return
        if (!styleLoaded) return
        selectedMarker?.let(target::removeMarker)
        selectedMarker = destination?.let {
            target.addMarker(MarkerOptions().position(LatLng(it.coordinate.latitude, it.coordinate.longitude)).title(it.title))
        }
    }

    fun focusDestination(destination: Destination) {
        follow = false
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(destination.coordinate.latitude, destination.coordinate.longitude), 16.0))
    }

    fun recenter() { evidenceFocus = emptyList(); follow = true; render() }
    fun highlightEvidence(ranges: List<ScenicRange>, title: String? = null,
        color: Int = android.graphics.Color.rgb(235, 107, 64)) {
        render()
        val target = map ?: return
        val route = runtime.session.state.value.route ?: return
        if (!styleLoaded || route.geometry.size < 2) return
        evidenceLines.forEach(target::removePolyline)
        evidenceLines.clear()
        evidenceMarker?.let(target::removeMarker)
        evidenceMarker = null
        val index = RouteGeometryIndex(route)
        val highlighted = ranges.map(index::slice).filter { it.isNotEmpty() }
        highlighted.filter { it.size >= 2 }.forEach { points ->
            evidenceLines += target.addPolyline(PolylineOptions().addAll(points.map { LatLng(it.latitude, it.longitude) })
                .color(color).width(8f))
        }
        evidenceFocus = highlighted.flatten().map { LatLng(it.latitude, it.longitude) }
        if (title != null && evidenceFocus.isNotEmpty()) {
            evidenceMarker = target.addMarker(MarkerOptions().position(evidenceFocus.first()).title(title))
            target.selectMarker(evidenceMarker!!)
        }
        fitEvidence()
    }

    private fun fitEvidence() {
        val target = map ?: return
        val points = evidenceFocus
        if (points.isEmpty()) return
        follow = false
        if (points.distinct().size == 1) target.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 15.0))
        else {
            val margin = (24 * view.resources.displayMetrics.density).toInt()
            if (view.width > contentPadding[0] + contentPadding[2] + margin * 2 && view.height > contentPadding[1] + contentPadding[3] + margin * 2) {
                target.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(),
                    contentPadding[0] + margin, contentPadding[1] + margin, contentPadding[2] + margin, contentPadding[3] + margin))
            }
        }
    }
    fun scroll(x: Float, y: Float) { evidenceFocus = emptyList(); follow = false; map?.scrollBy(x, y) }
    fun scale(factor: Float) {
        evidenceFocus = emptyList()
        // 追従したままだと、次の位置で速度に合う倍率へ戻る。「現在地」で追従へ返す
        follow = false
        if (factor > 0 && factor.isFinite()) map?.animateCamera(CameraUpdateFactory.zoomBy(kotlin.math.ln(factor.toDouble()) / kotlin.math.ln(2.0)))
    }
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        contentPadding = intArrayOf(left, top, right, bottom)
        map?.let(::applyMapPadding)
        if (evidenceFocus.isNotEmpty()) fitEvidence() else fitPreview()
    }

    private fun applyMapPadding(target: MapLibreMap) {
        target.setPadding(contentPadding[0], contentPadding[1], contentPadding[2], contentPadding[3])
        val spacing = (8 * view.resources.displayMetrics.density).toInt()
        target.uiSettings.setLogoMargins(contentPadding[0] + spacing, 0, 0, contentPadding[3] + spacing)
        target.uiSettings.setAttributionMargins(contentPadding[0] + spacing * 12, 0, 0, contentPadding[3] + spacing)
    }

    private fun fitPreview() {
        val target = map ?: return
        if (!styleLoaded || selectedDestination != null) return
        val state = runtime.session.state.value
        val route = state.route ?: return
        if (state.phase != NavigationPhase.PREVIEW || route.geometry.size < 2) return
        if (route.distanceMeters < 150) {
            val center = route.geometry[route.geometry.size / 2]
            target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), 16.0))
            return
        }
        val margin = (24 * view.resources.displayMetrics.density).toInt()
        val left = contentPadding[0] + margin
        val top = contentPadding[1] + margin
        val right = contentPadding[2] + margin
        val bottom = contentPadding[3] + margin
        if (view.width <= left + right || view.height <= top + bottom) return
        val bounds = LatLngBounds.Builder().includes(route.geometry.map { LatLng(it.latitude, it.longitude) }).build()
        target.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, left, top, right, bottom))
    }

    fun showWaypoints(points: List<Coordinate>) {
        waypointPoints = points
        val target = map ?: return
        if (!styleLoaded) return
        waypointMarkers.forEach(target::removeMarker)
        waypointMarkers.clear()
        points.forEachIndexed { index, point ->
            waypointMarkers += target.addMarker(MarkerOptions().position(LatLng(point.latitude, point.longitude)).title("通過点 ${index + 1}"))
        }
    }

    /** 経路の線を景観の区間ごとに塗り分ける。カメラにも他の強調表示にも触らない。 */
    fun paintScenery(sections: List<Pair<ScenicRange, Int>>) {
        sceneryRequest = sections
        applyScenery()
    }

    /**
     * 頼まれた塗り分けを引く。地図とスタイルの用意は非同期なので、整う前の依頼は覚えておき、
     * 整ってから描画のたびにここへ戻る。同じ区間を渡されたときは引き直さない。
     */
    private fun applyScenery() {
        val target = map ?: return
        if (!styleLoaded) return
        val state = runtime.session.state.value
        val route = state.route
        val key = "${route?.id}:${state.routeRevision}:" +
            sceneryRequest.joinToString { "${it.first.startMeters}-${it.first.endMeters}-${it.second}" }
        if (key == sceneryKey) return
        sceneryKey = key
        sceneryLines.forEach(target::removePolyline)
        sceneryLines.clear()
        if (route == null || route.geometry.size < 2 || sceneryRequest.isEmpty()) return
        val index = RouteGeometryIndex(route)
        sceneryRequest.forEach { (range, color) ->
            val points = index.slice(range)
            if (points.size < 2) return@forEach
            sceneryLines += target.addPolyline(PolylineOptions().addAll(points.map { LatLng(it.latitude, it.longitude) })
                .color(color).width(6f))
        }
    }

    private fun render() {
        val target = map ?: return
        if (!styleLoaded) return
        val state = runtime.session.state.value
        if (state.phase == NavigationPhase.GUIDING && previousPhase != NavigationPhase.GUIDING) follow = true
        previousPhase = state.phase
        val route = state.route
        val key = route?.let { "${it.id}:${state.routeRevision}" }
        val portfolioKey = state.alternatives.joinToString { it.plan.id } + ":" + key + ":" + state.phase
        if (portfolioKey != alternativesKey) {
            alternativeLines.forEach(target::removePolyline)
            alternativeLines.clear()
            if (state.phase == NavigationPhase.PREVIEW) state.alternatives.filter { it.plan.id != route?.id }.forEach { alternative ->
                alternativeLines += target.addPolyline(PolylineOptions().addAll(alternative.plan.geometry.map { LatLng(it.latitude, it.longitude) })
                    .color(android.graphics.Color.rgb(92, 107, 99)).width(3f))
            }
            alternativesKey = portfolioKey
        }
        if (key != routeKey) {
            evidenceMarker?.let(target::removeMarker)
            evidenceMarker = null
            evidenceFocus = emptyList()
            evidenceLines.forEach(target::removePolyline)
            evidenceLines.clear()
            sceneryLines.forEach(target::removePolyline)
            sceneryLines.clear()
            sceneryKey = null
            sceneryRequest = emptyList()
            routeLine?.let(target::removePolyline)
            routeLine = route?.takeIf { it.geometry.size >= 2 }?.let {
                target.addPolyline(PolylineOptions().addAll(it.geometry.map { coordinate -> LatLng(coordinate.latitude, coordinate.longitude) })
                    .color(android.graphics.Color.rgb(79, 100, 87)).width(6f))
            }
            routeKey = key
            if (route != null && state.phase == NavigationPhase.PREVIEW) {
                fitPreview()
                follow = false
            }
        }
        applyScenery()
        val location = runtime.locations.location.value
        val fix = location.fix
        if (fix == null || location.status in setOf(LocationStatus.PERMISSION_REQUIRED, LocationStatus.DISABLED)) {
            locationMarker?.let(target::removeMarker)
            locationMarker = null
            return
        }
        val point = LatLng(fix.coordinate.latitude, fix.coordinate.longitude)
        val fresh = location.status == LocationStatus.AVAILABLE
        val icon = if (fresh) locationIcon else staleLocationIcon
        val title = if (fresh) "現在地" else "前回の位置"
        val marker = locationMarker
        if (marker == null) locationMarker = target.addMarker(MarkerOptions().position(point).title(title).icon(icon))
        else { marker.position = point; marker.icon = icon; marker.title = title }
        if (follow) {
            // 位置は1秒に1度ほどしか来ないので、間を繋いで動かす
            val camera = CameraPosition.Builder().target(point)
                .zoom(FollowCamera.zoom(fix.speedMetersPerSecond))
                .tilt(FollowCamera.tilt(fix.speedMetersPerSecond))
                .bearing(fix.bearingDegrees?.toDouble() ?: 0.0).build()
            target.animateCamera(CameraUpdateFactory.newCameraPosition(camera), FOLLOW_ANIMATION_MS)
        }
    }

    fun close() { closed = true; loadTimeout?.cancel(); observation.cancel(); view.onDestroy(); map = null }

    private companion object {
        /** 次の位置が来るまでに追いつく長さ。これより長いと地図が遅れて見える。 */
        const val FOLLOW_ANIMATION_MS = 900
    }
}
