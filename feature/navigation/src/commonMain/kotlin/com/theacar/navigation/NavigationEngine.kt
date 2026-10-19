package com.theacar.navigation

import com.theacar.route.Destination
import com.theacar.route.RouteOptions
import com.theacar.route.RoutePlan
import com.theacar.route.RouteAlternative
import kotlinx.coroutines.flow.StateFlow

enum class EnginePhase { STOPPED, GUIDING, ARRIVED }

data class NavigationProgress(val remainingMeters: Int, val remainingSeconds: Int) {
    init { require(remainingMeters >= 0 && remainingSeconds >= 0) }
}

data class Maneuver(val instruction: String, val road: String, val distanceMeters: Int,
    val voiceInstruction: String = instruction, val stepIndex: Int = 0)

data class EngineState(
    val sessionId: Long = 0,
    val phase: EnginePhase = EnginePhase.STOPPED,
    val progress: NavigationProgress? = null,
    val maneuver: Maneuver? = null,
    val rerouting: Boolean = false,
    val routeRevision: Long = 0,
    val route: RoutePlan? = null,
    val failure: NavigationFailure? = null,
)

enum class NavigationFailure {
    SEARCH, NO_RESULTS, NETWORK, NO_ROUTE, LOCATION, PERMISSION, AUTHORIZATION,
    TERMS, NOT_READY, TIMEOUT, START, STOP, LIMIT_REACHED, SCENIC_UNAVAILABLE, PREVIEW_EXPIRED, AWAY_FROM_ROUTE,
}

class NavigationException(val failure: NavigationFailure) : Exception(failure.name)

interface NavigationEngine {
    val state: StateFlow<EngineState>
    val alternatives: List<RouteAlternative> get() = emptyList()
    suspend fun preview(destination: Destination, options: RouteOptions): RoutePlan
    suspend fun preview(destination: Destination, options: RouteOptions,
        onUpdate: suspend (RoutePlan, List<RouteAlternative>) -> Unit): RoutePlan = preview(destination, options)
    fun selectPreview(id: String): RoutePlan = throw NavigationException(NavigationFailure.NO_ROUTE)
    suspend fun start(plan: RoutePlan, sessionId: Long)
    suspend fun stop()
}

interface NavigationSessionOwner {
    val navigationSession: NavigationSession
}
