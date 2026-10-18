package com.theacar.auto

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.AppManager
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
import com.theacar.android.navigation.NavigationCompanion
import com.theacar.cabin.CabinNotice
import com.theacar.cabin.CabinNoticeSource
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class TheaCarSession(
    private val runtime: AndroidNavigationRuntime,
    private val notices: CabinNoticeSource? = null,
    private val companion: NavigationCompanion? = null,
) : Session() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var renderer: CarMapRenderer
    private var surface: SurfaceContainer? = null
    private var navigationStarted = false
    /** 案内中に地図へ重ねる、同乗者のリクエストの QR コードを出しているか。案内が終わると隠す。 */
    private val requestQrShown = MutableStateFlow(false)
    /**
     * 知らせた絶景ポイントと認定道路。再探索で景観が一時的に消えても忘れないよう、
     * 案内セッションと経路が変わるまで持ち越す。
     */
    private var announcedFor: Pair<Long, String>? = null
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

    override fun onCreateScreen(intent: Intent): Screen {
        val manager = carContext.getCarService(NavigationManager::class.java)
        val screens = carContext.getCarService(ScreenManager::class.java)
        renderer = CarMapRenderer(carContext, runtime, scope)
        // 地図のボタンから倍率と現在地を動かす。車載機によってはパンモードだけでは拡大できない
        val controls = CarMapControls({ renderer.zoom(CAR_ZOOM_STEP) }, { renderer.zoom(1 / CAR_ZOOM_STEP) }, renderer::recenter)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        manager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { runtime.session.stop() }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (navigationStarted) manager.navigationEnded()
                manager.clearNavigationManagerCallback()
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.close()
                scope.cancel()
            }
        })
        companion?.let { share ->
            scope.launch {
                combine(requestQrShown, share.carShare) { shown, state -> state.takeIf { shown && it.available } }.collect { state ->
                    renderer.showRequestQr(state?.url, state?.label.orEmpty(), shown = state != null)
                }
            }
        }
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
                    screens.popToRoot(); screens.push(GuidanceScreen(carContext, runtime, controls, companion?.carShare, requestQrShown) {
                        val show = !requestQrShown.value
                        if (show) companion?.prepareCarShare()
                        requestQrShown.value = show
                    })
                }
                if (!guiding && navigationStarted) {
                    manager.navigationEnded(); navigationStarted = false
                    requestQrShown.value = false
                    screens.popToRoot()
                }
                if (guiding) {
                    navigation.route?.let { route ->
                        val estimate = travelEstimate(navigation.progress?.remainingMeters ?: route.distanceMeters,
                            navigation.progress?.remainingSeconds ?: route.durationSeconds)
                        val trip = Trip.Builder().addDestination(
                            androidx.car.app.navigation.model.Destination.Builder().setName(route.destination.title).build(), estimate)
                        navigation.maneuver?.let { trip.setCurrentRoad(it.road) }
                        manager.updateTrip(trip.setLoading(navigation.rerouting).build())
                    }
                }
            }
        }
        notices?.let { source ->
            scope.launch(Dispatchers.Main) { source.notices.collect(::show) }
        }
        return HomeScreen(carContext, runtime, controls)
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
    }
}
