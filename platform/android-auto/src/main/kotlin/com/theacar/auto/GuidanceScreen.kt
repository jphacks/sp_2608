package com.theacar.auto

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.android.navigation.CarShareState
import com.theacar.android.navigation.message
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.StateFlow

internal class GuidanceScreen(
    carContext: CarContext,
    runtime: AndroidNavigationRuntime,
    private val controls: CarMapControls? = null,
    private val share: StateFlow<CarShareState>? = null,
    private val shareShown: StateFlow<Boolean>? = null,
    private val onToggleShare: () -> Unit = {},
) : CarScreen(carContext, runtime) {
    init {
        observe(runtime.session.state)
        share?.let { observe(it) }
        shareShown?.let { observe(it) }
    }

    override fun onGetTemplate(): Template {
        val state = runtime.session.state.value
        // 地図のボタン帯は拡大・縮小と現在地に使うので、同乗者のリクエストはこちらへ置く
        val actions = ActionStrip.Builder().addAction(stopAction(carContext.carAppApiLevel) { runtime.session.stop() })
        if (share?.value?.available == true) {
            actions.addAction(Action.Builder().setIcon(qrIcon()).setOnClickListener(onToggleShare).build())
        }
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actions.build())
            .setMapActionStrip(carMapActionStrip(carContext, controls))
        state.route?.let { route ->
            builder.setDestinationTravelEstimate(travelEstimate(state.progress?.remainingMeters ?: route.distanceMeters,
                state.progress?.remainingSeconds ?: route.durationSeconds))
        }
        state.maneuver?.let { maneuver ->
            val steps = state.route?.steps.orEmpty()
            val current = steps.getOrNull(maneuver.stepIndex)
            val info = RoutingInfo.Builder().setCurrentStep(
                ManeuverMapping.step(carContext, maneuver.instruction, maneuver.road, current?.type ?: 0),
                Distance.create(maneuver.distanceMeters.toDouble(), Distance.UNIT_METERS))
            steps.getOrNull(maneuver.stepIndex + 1)?.let { next ->
                info.setNextStep(ManeuverMapping.step(carContext, next.instruction, next.road, next.type))
            }
            builder.setNavigationInfo(info.build())
        }
        state.failure?.takeIf { !state.rerouting }?.let { builder.setNavigationInfo(MessageInfo.Builder(it.message()).build()) }
        if (state.rerouting) builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
        return builder.build()
    }

}

private fun GuidanceScreen.qrIcon() = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_request_qr)).build()

// 地図系テンプレートのボタン帯は10秒操作がないと隠れ、最初のタップは再表示だけに使われるため、「終了」は常に表示する
internal fun stopAction(carApiLevel: Int, onStop: () -> Unit): Action = Action.Builder().setTitle("終了")
    .apply { if (carApiLevel >= 5) setFlags(Action.FLAG_IS_PERSISTENT) }
    .setOnClickListener(onStop).build()

internal fun travelEstimate(meters: Int, seconds: Int): TravelEstimate = TravelEstimate.Builder(
    Distance.create(meters.toDouble(), Distance.UNIT_METERS), ZonedDateTime.now().plusSeconds(seconds.toLong()))
    .setRemainingTimeSeconds(seconds.toLong()).build()
