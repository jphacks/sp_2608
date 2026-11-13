package com.theacar.auto

import com.theacar.android.navigation.displayName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.android.navigation.DriveSimulator
import com.theacar.cabin.CabinNotice
import com.theacar.cabin.CabinNoticeSource
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class TheaCarSession(
    private val runtime: AndroidNavigationRuntime,
    private val notices: CabinNoticeSource? = null,
) : Session() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var renderer: CarMapRenderer
    private var surface: SurfaceContainer? = null
    private var navigationStarted = false
    /**
     * 知らせた絶景ポイントと認定道路。再探索で景観が一時的に消えても忘れないよう、
     * 案内セッションと経路が変わるまで持ち越す。
     */
    private var announcedFor: Pair<Long, String>? = null
    private var autoDrive = false
    private var controls: CarMapControls? = null
    private val simulator by lazy { DriveSimulator(runtime) }
    private var announcedReveal: Double? = null
    private var announcedRoad: String? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            surface = surfaceContainer
            if (runtime.configuration.configured) renderer.attach(surfaceContainer)
        }
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            surface = null
            renderer.close()
        }
        override fun onScroll(distanceX: Float, distanceY: Float) { renderer.scroll(distanceX, distanceY) }
        override fun onVisibleAreaChanged(visibleArea: Rect) { renderer.setVisibleArea(visibleArea) }
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) { renderer.scale(focusX, focusY, scaleFactor) }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        if (::renderer.isInitialized) renderer.setDarkMode(carContext.isDarkMode)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val manager = carContext.getCarService(NavigationManager::class.java)
        val screens = carContext.getCarService(ScreenManager::class.java)
        renderer = CarMapRenderer(carContext, runtime, scope)
        // 地図のボタンから倍率と現在地を動かす。車載機によってはパンモードだけでは拡大できない
        val controls = CarMapControls({ renderer.zoom(CAR_ZOOM_STEP) }, { renderer.zoom(1 / CAR_ZOOM_STEP) }, renderer::recenter)
        this.controls = controls
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        manager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { runtime.session.stop() }
            // 審査で使うテストドライブ（NF-7）。これ以降の案内は、経路の上を走っていることにして進める
            override fun onAutoDriveEnabled() {
                autoDrive = true
                simulator.start(AUTO_DRIVE_KMH)
            }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // スマホの画面を開かずに車の画面だけで使うときも、案内の前に現在地を取る
            override fun onStart(owner: LifecycleOwner) { runtime.locations.acquire(this@TheaCarSession) }
            override fun onStop(owner: LifecycleOwner) { runtime.locations.release(this@TheaCarSession) }
            override fun onResume(owner: LifecycleOwner) { runtime.carScreenVisible.value = true }
            override fun onPause(owner: LifecycleOwner) { runtime.carScreenVisible.value = false }
            override fun onDestroy(owner: LifecycleOwner) {
                if (navigationStarted) manager.navigationEnded()
                manager.clearNavigationManagerCallback()
                simulator.stop()
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.close()
                scope.cancel()
            }
        })
        // Main (not immediate) so the first emission runs after the root screen has been pushed.
        scope.launch(Dispatchers.Main) {
            runtime.session.state.collect { navigation ->
                if (runtime.configuration.configured) surface?.let(renderer::attach)
                // 景観はルート線の色で見せ、変わり目だけ一瞬知らせる
                val scenery = carScenery(navigation)
                renderer.showScenery(scenery)
                announceScenery(navigation, scenery)
                val guiding = navigation.phase == NavigationPhase.GUIDING ||
                    (navigationStarted && navigation.phase == NavigationPhase.STOPPING)
                if (guiding && !navigationStarted) {
                    manager.navigationStarted(); navigationStarted = true
                    if (autoDrive) simulator.start(AUTO_DRIVE_KMH)
                    screens.popToRoot(); screens.push(GuidanceScreen(carContext, runtime, controls))
                }
                if (!guiding && navigationStarted) {
                    manager.navigationEnded(); navigationStarted = false
                    screens.popToRoot()
                }
                if (guiding) {
                    navigation.route?.let { route ->
                        val remainingMeters = navigation.progress?.remainingMeters ?: route.distanceMeters
                        val remainingSeconds = navigation.progress?.remainingSeconds ?: route.durationSeconds
                        val trip = Trip.Builder().addDestination(
                            androidx.car.app.navigation.model.Destination.Builder().setName(route.destination.displayName).build(),
                            travelEstimate(remainingMeters, remainingSeconds))
                        // メーターの表示（クラスタ）にも次の曲がり角を出す。曲がり角までの時間は残りの時間を距離で按分した目安
                        navigation.maneuver?.let { maneuver ->
                            trip.setCurrentRoad(maneuver.road)
                            val type = route.steps.getOrNull(maneuver.stepIndex)?.type ?: 0
                            val seconds = (remainingSeconds.toLong() * maneuver.distanceMeters / remainingMeters.coerceAtLeast(1)).toInt()
                            trip.addStep(ManeuverMapping.step(carContext, maneuver.instruction, maneuver.road, type),
                                travelEstimate(maneuver.distanceMeters, seconds))
                        }
                        manager.updateTrip(trip.setLoading(navigation.rerouting).build())
                    }
                }
            }
        }
        notices?.let { source ->
            scope.launch(Dispatchers.Main) { source.notices.collect(::show) }
        }
        // ルートの画面はホームの上に積むので、ホームが置かれてから開く
        scope.launch(Dispatchers.Main) { navigate(intent) }
        return HomeScreen(carContext, runtime, controls)
    }

    override fun onNewIntent(intent: Intent) = navigate(intent)

    /**
     * ほかのアプリや音声アシスタントから届いた行き先で、ルートの確認を開く（品質ガイドラインの NF-6、VC-1）。
     * 案内中は今の案内を止めずに、終わってからにしてもらう。
     */
    private fun navigate(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val request = intent.data?.let { navigateRequest(it.toString()) } ?: return
        val session = runtime.session
        if (session.state.value.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)) {
            CarToast.makeText(carContext, "案内を終えてから、もう一度お試しください", CarToast.LENGTH_LONG).show()
            return
        }
        scope.launch(Dispatchers.Main) {
            val destination = when (request) {
                is NavigateRequest.At -> Destination("geo:${request.coordinate.latitude},${request.coordinate.longitude}",
                    request.label ?: "指定された場所", request.coordinate)
                is NavigateRequest.Search -> {
                    session.search(request.text)
                    session.state.first { it.query == request.text && !it.searching }.destinations.firstOrNull() ?: run {
                        CarToast.makeText(carContext, "「${request.text}」が見つかりませんでした", CarToast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }
            session.preview(destination)
            val screens = carContext.getCarService(ScreenManager::class.java)
            screens.popToRoot()
            if (session.state.value.phase in setOf(NavigationPhase.PREVIEWING, NavigationPhase.PREVIEW)) {
                screens.push(RoutePreviewScreen(carContext, runtime, controls))
            }
        }
    }

    /**
     * 車内の知らせを車載画面へ出す。
     *
     * `Alert` は NavigationTemplate が出ている間しか表示されない。案内していないときや
     * 利用者が別のアプリを見ているときは出ないので、出せなかったら CarToast に落とす。
     * どちらも best-effort で、失敗しても案内は壊さない。音声は画面に関係なく鳴る。
     */
    private fun show(notice: CabinNotice) =
        announce((notice.id % Int.MAX_VALUE).toInt(), notice.spoken, notice.detail)

    private fun announce(id: Int, title: String, detail: String) {
        val alert = Alert.Builder(id, CarText.create(title), ALERT_MS).setSubtitle(CarText.create(detail)).build()
        val shown = runCatching {
            carContext.getCarService(AppManager::class.java).showAlert(alert)
        }.isSuccess
        if (!shown) {
            runCatching { CarToast.makeText(carContext, title, CarToast.LENGTH_LONG).show() }
        }
    }

    /** 景観の変わり目を一瞬だけ知らせる。同じ地点・同じ道で鳴らし直さない。 */
    private fun announceScenery(state: NavigationState, scenery: CarScenery?) {
        val key = state.route?.id?.let { state.sessionId to it }
        if (key != announcedFor) {
            announcedFor = key
            announcedReveal = null
            announcedRoad = null
        }
        if (scenery == null) return
        scenery.revealAtMeters?.takeIf { it != announcedReveal }?.let { reveal ->
            announcedReveal = reveal
            val meters = ((scenery.revealMeters ?: 0.0) / 50).toInt() * 50
            announce(REVEAL_ALERT_ID, "まもなく海が開けます", "あと${meters.coerceAtLeast(50)}m")
        }
        val road = scenery.road
        if (road?.name != announcedRoad) {
            announcedRoad = road?.name
            road?.let { announce(ROAD_ALERT_ID, it.name, it.rank.label) }
        }
    }

    private companion object {
        /** 運転中に読み終われて、かつ居座らない長さ。 */
        const val ALERT_MS = 6_000L
        /** 知らせの差し替えに使う番号。車内の知らせとぶつからない値にしている。 */
        const val REVEAL_ALERT_ID = Int.MAX_VALUE - 1
        const val ROAD_ALERT_ID = Int.MAX_VALUE - 2
        /** テストドライブの速さ。一般道を走る速さにしている。 */
        const val AUTO_DRIVE_KMH = 50.0
    }
}
