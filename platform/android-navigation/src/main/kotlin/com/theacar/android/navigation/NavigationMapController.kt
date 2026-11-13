package com.theacar.android.navigation

import android.content.Context
import com.theacar.model.Coordinate
import com.theacar.location.LocationStatus
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.Destination
import com.theacar.route.ScenicRange
import com.theacar.route.RouteGeometryIndex
import com.theacar.route.RoutePlan
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
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

enum class MapDisplayState { LOADING, READY, ERROR, DISABLED }

/** 地図で選んだ、名前の無い場所の呼び方 */
internal const val PICKED_PLACE_TITLE = "指定した地点"

class NavigationMapController(context: Context, private val runtime: AndroidNavigationRuntime, private val scope: CoroutineScope) {
    val view: MapView
    var onLongClick: ((Coordinate) -> Unit)? = null
    var onDestinationSelected: ((Destination) -> Unit)? = null
    private val mutableDisplayState = MutableStateFlow(MapDisplayState.LOADING)
    val displayState = mutableDisplayState.asStateFlow()
    private var styleLoaded = false
    private var map: MapLibreMap? = null
    private var routeLine: Polyline? = null
    private var routeCasing: Polyline? = null
    private var traveledLine: Polyline? = null
    private var traveledKey: String? = null
    private var indexedRoute: RoutePlan? = null
    private var routeIndex: RouteGeometryIndex? = null
    private val alternativeLines = mutableListOf<Polyline>()
    private val evidenceLines = mutableListOf<Polyline>()
    private val sceneryLines = mutableListOf<Polyline>()
    private var sceneryRequest: List<Pair<ScenicRange, Int>> = emptyList()
    private var evidenceMarker: Marker? = null
    private var evidenceFocus = emptyList<LatLng>()
    private var alternativesKey: String? = null
    private var locationMarker: Marker? = null
    private var selectedMarker: Marker? = null
    private var selectedMarkerKey: String? = null
    private var selectedDestination: Destination? = null
    /** 経路の目的地のピン。候補を出している間から着くまで立てておく */
    private var routeDestinationMarker: Marker? = null
    private var routeDestinationKey: String? = null
    private val waypointMarkers = mutableListOf<Marker>()
    private var waypointPoints = emptyList<Coordinate>()
    private var routeKey: String? = null
    private var sceneryKey: String? = null
    private var follow = true
    private var closed = false
    private var previousPhase: NavigationPhase? = null
    private var contentPadding = intArrayOf(0, 0, 0, 0)
    private var loadTimeout: Job? = null
    private var dark = false
    /** 航空写真で見せるか。夜の表示より優先する */
    private var satellite = false
    private val locationIcon = locationIcon(context, android.graphics.Color.rgb(34, 112, 215))
    private val staleLocationIcon = locationIcon(context, android.graphics.Color.rgb(120, 130, 140))

    private fun locationIcon(context: Context, color: Int): org.maplibre.android.annotations.Icon {
        val size = (36 * context.resources.displayMetrics.density).toInt().coerceAtLeast(36)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // 地図上で見失わないよう、外側に半透明のハローを付ける
        paint.color = android.graphics.Color.argb(70, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        canvas.drawCircle(size / 2f, size / 2f, size * .5f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size * .30f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size * .22f, paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }

    private val destinationPin = destinationPin(context)

    /**
     * Google マップのような赤いしずく形のピン。マーカーの絵は真ん中が地点に来るので、
     * 絵の高さをピンの2倍にして下半分を空け、ピンの先を真ん中に置く。
     */
    private fun destinationPin(context: Context): org.maplibre.android.annotations.Icon {
        val density = context.resources.displayMetrics.density
        val width = (30 * density).coerceAtLeast(30f)
        val height = (40 * density).coerceAtLeast(40f)
        val stroke = 1.5f * density
        val bitmap = android.graphics.Bitmap.createBitmap(width.toInt(), (height * 2).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val radius = width / 2 - stroke
        val cx = width / 2
        val cy = radius + stroke
        val tipY = height - stroke
        // 先から丸へ引いた接線の角度。丸の下側を開けて、先までを直線でつなぐ
        val spread = Math.toDegrees(kotlin.math.acos(radius / (tipY - cy).toDouble())).toFloat()
        val path = android.graphics.Path().apply {
            moveTo(cx, tipY)
            arcTo(android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius), 90 + spread, 360 - 2 * spread)
            close()
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(234, 67, 53)
        canvas.drawPath(path, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = android.graphics.Color.rgb(179, 20, 18)
        canvas.drawPath(path, paint)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.rgb(165, 14, 14)
        canvas.drawCircle(cx, cy, radius * .38f, paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }

    private val observation: Job

    init {
        MapLibre.getInstance(context)
        // 漢字も同梱の Noto Sans JP で描くため、端末の書体は使わない
        view = MapView(context, MapLibreMapOptions.createFromAttributes(context).localIdeographFontFamilyEnabled(false))
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
        routeCasing?.let(target::removePolyline)
        routeCasing = null
        clearTraveled(target)
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
        routeDestinationMarker?.let(target::removeMarker)
        waypointMarkers.forEach(target::removeMarker)
        waypointMarkers.clear()
        routeLine = null
        locationMarker = null
        selectedMarker = null
        selectedMarkerKey = null
        routeDestinationMarker = null
        routeDestinationKey = null
        routeKey = null
        styleLoaded = false
        mutableDisplayState.value = MapDisplayState.LOADING
        loadTimeout?.cancel()
        loadTimeout = scope.launch {
            delay(25_000)
            if (!closed && mutableDisplayState.value == MapDisplayState.LOADING) mutableDisplayState.value = MapDisplayState.ERROR
        }
        val url = runtime.configuration.mapStyleUrl
        if (url.isBlank()) {
            applyStyle(target, Style.Builder().fromJson("{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#e8eeea\"}}]}"))
        } else scope.launch {
            // 読めなければ、配信元の書体のまま出す
            val json = runCatching { tabiStyleJson(url) }.getOrNull()
            if (!closed) applyStyle(target, json?.let { Style.Builder().fromJson(it) } ?: Style.Builder().fromUri(url))
        }
    }

    private fun applyStyle(target: MapLibreMap, style: Style.Builder) {
        target.setStyle(style) {
            if (closed) return@setStyle
            styleLoaded = true
            applyTabiMapColors(it, dark, satellite)
            applySatellite(it, satellite)
            applyTabiMapLabels(it)
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
        onDestinationSelected?.invoke(Destination("map:${point.latitude},${point.longitude}", name ?: PICKED_PLACE_TITLE, Coordinate(point.latitude, point.longitude)))
    }

    fun showSelectedDestination(destination: Destination?) {
        selectedDestination = destination
        if (destination != null) evidenceFocus = emptyList()
        val target = map ?: return
        if (!styleLoaded) return
        syncDestinationPins(target)
    }

    /**
     * 目的地のピンを、選んだ地点と経路の目的地に立てる。
     * 経路の目的地は候補を出している間から着くまで残し、選んだ地点と同じ場所なら1本にまとめる。
     */
    private fun syncDestinationPins(target: MapLibreMap) {
        val state = runtime.session.state.value
        val routed = state.route?.destination?.takeIf { state.phase in ROUTED_PHASES }
        val routedKey = routed?.let(::pinKey)
        if (routedKey != routeDestinationKey) {
            routeDestinationMarker?.let(target::removeMarker)
            routeDestinationMarker = routed?.let { target.addMarker(pinOptions(it)) }
            routeDestinationKey = routedKey
        }
        val selected = selectedDestination?.takeIf { routed == null || !samePlace(it.coordinate, routed.coordinate) }
        val selectedKey = selected?.let(::pinKey)
        if (selectedKey != selectedMarkerKey) {
            selectedMarker?.let(target::removeMarker)
            selectedMarker = selected?.let { target.addMarker(pinOptions(it)) }
            selectedMarkerKey = selectedKey
        }
    }

    private fun pinOptions(destination: Destination) = MarkerOptions()
        .position(LatLng(destination.coordinate.latitude, destination.coordinate.longitude))
        .title(destination.title).icon(destinationPin)

    private fun pinKey(destination: Destination) =
        "${destination.coordinate.latitude},${destination.coordinate.longitude}:${destination.title}"

    /** 約 30m 以内なら同じ場所とみなす（地図で選んだ点と、経路の終点が少しずれることがある） */
    private fun samePlace(a: Coordinate, b: Coordinate) =
        kotlin.math.abs(a.latitude - b.latitude) < .0003 && kotlin.math.abs(a.longitude - b.longitude) < .0004

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
                .color(color).width(5f))
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
    /** 地図を夜の配色にする。スタイルを読み直さず、色だけを塗り替える。 */
    fun setDarkMode(enabled: Boolean) {
        if (dark == enabled) return
        dark = enabled
        if (styleLoaded) map?.style?.let { applyTabiMapColors(it, dark, satellite) }
        routeLine?.color = routeColor()
        traveledLine?.color = traveledColor()
    }

    /** 地図と航空写真を切り替える。地図を読み直さず、今のスタイルにその場でかける */
    fun setSatellite(enabled: Boolean) {
        if (satellite == enabled) return
        satellite = enabled
        if (!styleLoaded) return
        map?.style?.let {
            applyTabiMapColors(it, dark, satellite)
            applySatellite(it, satellite)
        }
    }

    // 夜の地図では、これから走る線を明るく、通過済みを暗くして、地との差を保つ
    private fun routeColor() = if (dark) android.graphics.Color.rgb(95, 179, 211) else android.graphics.Color.rgb(46, 140, 170)
    private fun traveledColor() = if (dark) android.graphics.Color.rgb(74, 85, 80) else android.graphics.Color.rgb(165, 170, 166)

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
        // 下の枠を広げると見える地図がわずかになり、経路を収めようとして引きすぎるので、下の余白は画面の半分までにする
        val bottom = minOf(contentPadding[3], view.height / 2) + margin
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
        val index = geometryIndex(route)
        sceneryRequest.forEach { (range, color) ->
            val points = index.slice(range)
            if (points.size < 2) return@forEach
            sceneryLines += target.addPolyline(PolylineOptions().addAll(points.map { LatLng(it.latitude, it.longitude) })
                .color(color).width(6f))
        }
        // 後から引いた線が上に乗るので、通過済みの線は塗り分けの後に引き直す
        clearTraveled(target)
        drawTraveled(target, state)
    }

    private fun geometryIndex(route: RoutePlan): RouteGeometryIndex {
        if (indexedRoute !== route) { indexedRoute = route; routeIndex = RouteGeometryIndex(route) }
        return routeIndex!!
    }

    private fun clearTraveled(target: MapLibreMap) {
        traveledLine?.let(target::removePolyline)
        traveledLine = null
        traveledKey = null
    }

    /** 通過済み区間はグレーにして、これから走る区間と区別する。25m ごとに引き直す。 */
    private fun drawTraveled(target: MapLibreMap, state: NavigationState) {
        val progress = state.progress
        val route = state.route
        if (state.phase != NavigationPhase.GUIDING || progress == null || route == null || route.geometry.size < 2) {
            clearTraveled(target)
            return
        }
        val index = geometryIndex(route)
        // 残り距離は経路の座標列の長さから数えているので、同じ長さを基準にする
        val traveled = (index.totalDistanceMeters - progress.remainingMeters).coerceIn(0.0, index.totalDistanceMeters)
        val key = "$routeKey:${(traveled / 25).toLong()}"
        if (key == traveledKey) return
        clearTraveled(target)
        traveledKey = key
        if (traveled < 25) return
        val points = index.slice(ScenicRange(0.0, traveled))
        if (points.size < 2) return
        traveledLine = target.addPolyline(PolylineOptions().addAll(points.map { LatLng(it.latitude, it.longitude) })
            .color(traveledColor()).width(6f))
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
            routeCasing?.let(target::removePolyline)
            routeLine?.let(target::removePolyline)
            clearTraveled(target)
            val routePoints = route?.takeIf { it.geometry.size >= 2 }?.geometry?.map { coordinate -> LatLng(coordinate.latitude, coordinate.longitude) }
            routeCasing = routePoints?.let {
                target.addPolyline(PolylineOptions().addAll(it).color(android.graphics.Color.argb(153, 30, 42, 36)).width(9f))
            }
            routeLine = routePoints?.let {
                target.addPolyline(PolylineOptions().addAll(it).color(routeColor()).width(6f))
            }
            routeKey = key
            if (route != null && state.phase == NavigationPhase.PREVIEW) {
                fitPreview()
                follow = false
            }
        }
        applyScenery()
        drawTraveled(target, state)
        syncDestinationPins(target)
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
        /** 経路の目的地にピンを立てておく段階。探している間（PREVIEWING）は、前の経路のピンを残さない */
        val ROUTED_PHASES = setOf(NavigationPhase.PREVIEW, NavigationPhase.STARTING, NavigationPhase.GUIDING, NavigationPhase.ARRIVED)
    }
}
