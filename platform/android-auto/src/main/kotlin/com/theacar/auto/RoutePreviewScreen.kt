@file:Suppress("DEPRECATION")

package com.theacar.auto

import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.android.navigation.NavigationService
import com.theacar.android.navigation.message
import com.theacar.navigation.NavigationFailure
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class RoutePreviewScreen(carContext: CarContext, runtime: AndroidNavigationRuntime,
    private val controls: CarMapControls? = null) : CarScreen(carContext, runtime) {
    private var startFailed = false
    private val screens = carContext.getCarService(ScreenManager::class.java)

    private var lastPhase = runtime.session.state.value.phase

    init {
        observe(runtime.session.state) { state ->
            if (state.phase != lastPhase) { lastPhase = state.phase; startFailed = false }
            if (state.phase !in previewPhases && state.failure.relevant() == null && screens.top === this) screens.pop()
        }
    }

    private fun RoutePreviewNavigationTemplate.Builder.withHeader(title: String) =
        if (carContext.carAppApiLevel >= 5) setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        else setTitle(title).setHeaderAction(Action.BACK)

    private fun header(title: String) = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()

    private fun sceneryFlow(state: NavigationState): String? =
        carSceneryBreakdownLines(carSceneryShares(state), lines = 1, perLine = SCENERY_PREVIEW_ITEMS)
            .firstOrNull()?.let { "$CAR_SCENERY_BREAKDOWN_LABEL $it" }

    override fun onGetTemplate(): Template {
        val state = runtime.session.state.value
        val route = state.route
        val failure = state.failure.relevant()
        if (failure != null && (route == null || state.phase != NavigationPhase.PREVIEW)) {
            return MessageTemplate.Builder(failure.message()).setHeader(header("ルート確認")).build()
        }
        val mapActions = carMapActionStrip(carContext, controls)
        if (route == null || state.phase != NavigationPhase.PREVIEW) {
            return RoutePreviewNavigationTemplate.Builder().withHeader("ルート確認").setLoading(true).setMapActionStrip(mapActions).build()
        }
        val arrival = ZonedDateTime.now().plusSeconds(route.durationSeconds.toLong()).format(DateTimeFormatter.ofPattern("H:mm"))
        // RoutePreviewNavigationTemplate の行には DistanceSpan か DurationSpan が必須
        val summary = SpannableString("D ・ T ・ $arrival 到着")
        summary.setSpan(DistanceSpan.create(distance(route.distanceMeters)), 0, 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        summary.setSpan(DurationSpan.create(route.durationSeconds.toLong()), 4, 5, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        // 行が持てる文字列は2つ。1つめは距離と時刻、2つめは知らせることがあればそれ、無ければ景観。
        val second = when {
            startFailed -> NavigationFailure.START.message()
            failure != null -> failure.message()
            else -> sceneryFlow(state)
        }
        val item = Row.Builder().setTitle(route.destination.title).addText(summary).apply {
            second?.let { addText(it) }
        }.build()
        val start = Action.Builder().setTitle("案内開始").setOnClickListener {
            startFailed = false
            try {
                carContext.startForegroundService(Intent(carContext, NavigationService::class.java))
            } catch (_: IllegalStateException) { startFailed = true; invalidate() }
            catch (_: SecurityException) { startFailed = true; invalidate() }
        }.build()
        return RoutePreviewNavigationTemplate.Builder().withHeader("ルート確認")
            .setItemList(ItemList.Builder().addItem(item).setOnSelectedListener {}.build())
            .setNavigateAction(start).setMapActionStrip(mapActions).build()
    }
}

private val previewPhases = setOf(NavigationPhase.PREVIEWING, NavigationPhase.PREVIEW, NavigationPhase.STARTING)

internal fun NavigationFailure?.relevant() = this?.takeUnless { it == NavigationFailure.SEARCH || it == NavigationFailure.NO_RESULTS }

private fun distance(meters: Int) = if (meters < 1000) Distance.create(meters.toDouble(), Distance.UNIT_METERS)
    else Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)

/** ルート確認の行へ並べる景観の件数。これ以上は1行に入らない。 */
private const val SCENERY_PREVIEW_ITEMS = 2
