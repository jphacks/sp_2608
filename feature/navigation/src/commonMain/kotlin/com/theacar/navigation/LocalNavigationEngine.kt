package com.theacar.navigation

import com.theacar.location.LocationFix
import com.theacar.location.LocationSource
import com.theacar.location.LocationStatus
import com.theacar.location.LocationUse
import com.theacar.location.usableFix
import com.theacar.route.Destination
import com.theacar.route.RouteCalculator
import com.theacar.route.RouteAlternative
import com.theacar.route.RouteAlternativesCalculator
import com.theacar.route.RouteOptions
import com.theacar.route.RoutePlan
import com.theacar.route.RouteQuery
import com.theacar.route.RoutingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

class LocalNavigationEngine(
    private val calculator: RouteCalculator,
    private val locations: LocationSource,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val budget: RoutingBudget = RoutingBudget(nowMillis),
) : NavigationEngine {
    private val mutableState = MutableStateFlow(EngineState())
    override val state = mutableState.asStateFlow()
    private var prepared: RoutePlan? = null
    private var preparedAtMillis = 0L
    private val previewReceivedAt = mutableMapOf<String, Long>()
    override var alternatives: List<RouteAlternative> = emptyList()
        private set
    private var options = RouteOptions()
    private var tracker: RouteTracker? = null
    private var observation: Job? = null
    private var rerouteJob: Job? = null
    private var generation = 0L
    private var lastFixMillis = -1L
    private var offRouteCount = 0
    private var offRouteSince = 0L
    private var arrivalCount = 0
    private var lastRerouteMillis: Long? = null

    override suspend fun preview(destination: Destination, options: RouteOptions): RoutePlan = preview(destination, options) { _, _ -> }

    override suspend fun preview(destination: Destination, options: RouteOptions,
        onUpdate: suspend (RoutePlan, List<RouteAlternative>) -> Unit): RoutePlan {
        check(state.value.phase != EnginePhase.GUIDING)
        val expected = ++generation
        prepared = null
        alternatives = emptyList()
        previewReceivedAt.clear()
        val fix = previewFix()
        if (expected != generation) throw CancellationException("Obsolete preview")
        budget.consume()
        val query = RouteQuery(fix.coordinate, destination, options)
        this.options = options
        fun accept(choices: List<RouteAlternative>): RoutePlan {
            if (expected != generation) throw CancellationException("Obsolete preview")
            choices.forEach { previewReceivedAt.getOrPut(it.plan.id) { nowMillis() } }
            val selected = prepared
            val retained = alternatives.firstOrNull { it.plan.id == selected?.id }
            val route = selected ?: choices.firstOrNull()?.plan ?: throw NavigationException(NavigationFailure.NO_ROUTE)
            if (selected == null) {
                RouteTracker(route)
                preparedAtMillis = previewReceivedAt.getValue(route.id)
            }
            alternatives = if (retained != null && choices.none { it.plan.id == retained.plan.id }) choices + retained else choices
            previewReceivedAt.keys.retainAll(alternatives.map { it.plan.id }.toSet())
            prepared = route
            return route
        }
        val choices = if (options.scenic != null) {
            val scenicCalculator = calculator as? RouteAlternativesCalculator
                ?: throw NavigationException(NavigationFailure.SCENIC_UNAVAILABLE)
            scenicCalculator.alternatives(query) { update ->
                val route = accept(update)
                onUpdate(route, alternatives)
            }
        } else listOf(RouteAlternative(calculator.calculate(query)))
        return accept(choices)
    }

    override fun selectPreview(id: String): RoutePlan {
        check(state.value.phase != EnginePhase.GUIDING)
        val plan = alternatives.firstOrNull { it.plan.id == id }?.plan
            ?: throw NavigationException(NavigationFailure.NO_ROUTE)
        RouteTracker(plan)
        prepared = plan
        preparedAtMillis = previewReceivedAt.getValue(plan.id)
        return plan
    }

    override suspend fun start(plan: RoutePlan, sessionId: Long) {
        if (prepared?.id != plan.id) throw NavigationException(NavigationFailure.NO_ROUTE)
        if (options.scenic != null && nowMillis() - preparedAtMillis > 120_000) {
            throw NavigationException(NavigationFailure.PREVIEW_EXPIRED)
        }
        val joined = RouteTracker(plan)
        if (!joined.join(currentFix().coordinate, 150.0)) throw NavigationException(NavigationFailure.AWAY_FROM_ROUTE)
        observation?.cancel()
        rerouteJob?.cancel()
        generation++
        tracker = joined
        lastFixMillis = -1
        offRouteCount = 0
        arrivalCount = 0
        lastRerouteMillis = null
        mutableState.value = EngineState(sessionId, EnginePhase.GUIDING, route = plan)
        observation = scope.launch {
            locations.location.collect { location ->
                if (state.value.phase != EnginePhase.GUIDING) return@collect
                val current = location.fix
                if (location.status != LocationStatus.AVAILABLE || current == null || !current.isUsable()) {
                    offRouteCount = 0
                    arrivalCount = 0
                    mutableState.update { it.copy(maneuver = null, failure = NavigationFailure.LOCATION) }
                    return@collect
                }
                if (current.elapsedRealtimeMillis <= lastFixMillis) return@collect
                lastFixMillis = current.elapsedRealtimeMillis
                updatePosition(current)
            }
        }
    }

    override suspend fun stop() {
        generation++
        observation?.cancel()
        rerouteJob?.cancel()
        observation = null
        rerouteJob = null
        tracker = null
        prepared = null
        alternatives = emptyList()
        previewReceivedAt.clear()
        mutableState.value = EngineState()
    }

    private fun updatePosition(fix: LocationFix) {
        val route = state.value.route ?: return
        val currentTracker = tracker ?: return
        val position = currentTracker.locate(fix.coordinate)
        val offRoute = position.distanceFromRouteMeters > max(60.0, fix.accuracyMeters * 2.0)
        if (offRoute) {
            arrivalCount = 0
            if (offRouteCount++ == 0) offRouteSince = nowMillis()
            mutableState.update { it.copy(maneuver = null, failure = NavigationFailure.LOCATION) }
            if (offRouteCount >= 3 && nowMillis() - offRouteSince >= 5_000) requestReroute(fix, route, position)
            return
        }
        offRouteCount = 0
        val remaining = (currentTracker.totalMeters - position.traveledMeters).coerceAtLeast(0.0)
        val seconds = currentTracker.remainingSeconds(position.traveledMeters)
        mutableState.update { it.copy(progress = NavigationProgress(remaining.toInt(), seconds),
            maneuver = currentTracker.maneuver(position).takeUnless { state.value.rerouting },
            failure = it.failure?.takeIf { failure -> failure != NavigationFailure.LOCATION }) }
        val atDestination = remaining <= 40 && distanceMeters(fix.coordinate, route.destination.coordinate) <= 40
        arrivalCount = if (atDestination) arrivalCount + 1 else 0
        if (arrivalCount >= 3) {
            rerouteJob?.cancel()
            mutableState.update { it.copy(phase = EnginePhase.ARRIVED, progress = NavigationProgress(0, 0), maneuver = null, rerouting = false) }
            observation?.cancel()
        }
    }

    private fun requestReroute(fix: LocationFix, route: RoutePlan, position: RoutePosition) {
        if (rerouteJob?.isActive == true || lastRerouteMillis?.let { nowMillis() - it < 30_000 } == true) return
        lastRerouteMillis = nowMillis()
        val expected = generation
        val currentTracker = tracker ?: return
        val remaining = route.waypoints.filter { currentTracker.distances[it.geometryIndex] > position.traveledMeters }.map { it.coordinate }
        mutableState.update { it.copy(rerouting = true, maneuver = null, failure = null) }
        rerouteJob = scope.launch {
            try {
                budget.consume()
                val replacement = withTimeout(20_000) {
                    calculator.calculate(RouteQuery(fix.coordinate, route.destination, options.copy(viaPoints = remaining)))
                }
                if (generation != expected || state.value.phase != EnginePhase.GUIDING) return@launch
                val joined = RouteTracker(replacement)
                if (!joined.join(currentFix().coordinate, 150.0)) throw NavigationException(NavigationFailure.AWAY_FROM_ROUTE)
                tracker = joined
                prepared = replacement
                offRouteCount = 0
                arrivalCount = 0
                mutableState.update { it.copy(route = replacement, routeRevision = it.routeRevision + 1,
                    rerouting = false, progress = null, maneuver = null, failure = null) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation != expected) return@launch
                mutableState.update { it.copy(rerouting = false, failure = when (error) {
                    is NavigationException -> error.failure
                    is RoutingException -> error.navigationFailure()
                    is TimeoutCancellationException -> NavigationFailure.TIMEOUT
                    else -> NavigationFailure.NETWORK
                }) }
            }
        }
    }

    private fun currentFix(): LocationFix {
        val value = locations.location.value
        if (value.status == LocationStatus.PERMISSION_REQUIRED) throw NavigationException(NavigationFailure.PERMISSION)
        return value.usableFix(LocationUse.GUIDANCE_START, nowMillis())?.takeIf { value.status == LocationStatus.AVAILABLE }
            ?: throw NavigationException(NavigationFailure.LOCATION)
    }

    private suspend fun previewFix(): LocationFix {
        var value = locations.location.value
        if (value.status == LocationStatus.PERMISSION_REQUIRED) throw NavigationException(NavigationFailure.PERMISSION)
        if (value.status == LocationStatus.DISABLED) throw NavigationException(NavigationFailure.LOCATION)
        value.usableFix(LocationUse.ROUTE_PREVIEW, nowMillis())?.let { return it }
        value = withTimeoutOrNull(15_000) {
            locations.location.first {
                it.status in setOf(LocationStatus.PERMISSION_REQUIRED, LocationStatus.DISABLED) ||
                    it.usableFix(LocationUse.ROUTE_PREVIEW, nowMillis()) != null
            }
        } ?: throw NavigationException(NavigationFailure.LOCATION)
        if (value.status == LocationStatus.PERMISSION_REQUIRED) throw NavigationException(NavigationFailure.PERMISSION)
        return value.usableFix(LocationUse.ROUTE_PREVIEW, nowMillis())
            ?: throw NavigationException(NavigationFailure.LOCATION)
    }

    private fun LocationFix.isUsable(): Boolean = accuracyMeters <= LocationUse.GUIDANCE.maximumAccuracyMeters &&
        nowMillis() - elapsedRealtimeMillis in 0..LocationUse.GUIDANCE.maximumAgeMillis
}
