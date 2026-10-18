package com.theacar.navigation

import com.theacar.route.Destination
import com.theacar.route.DestinationSearch
import com.theacar.route.RouteOptions
import com.theacar.route.RoutePlan
import com.theacar.route.RouteAlternative
import com.theacar.route.RoutingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class NavigationPhase { IDLE, PREVIEWING, PREVIEW, STARTING, GUIDING, ARRIVED, STOPPING }

data class NavigationState(
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val query: String = "",
    val searching: Boolean = false,
    val destinations: List<Destination> = emptyList(),
    val route: RoutePlan? = null,
    val sessionId: Long = 0,
    val progress: NavigationProgress? = null,
    val maneuver: Maneuver? = null,
    val rerouting: Boolean = false,
    val routeRevision: Long = 0,
    val failure: NavigationFailure? = null,
    val alternatives: List<RouteAlternative> = emptyList(),
    val previewDestination: Destination? = null,
    val previewOptions: RouteOptions? = null,
    val previewNeedsRefresh: Boolean = false,
    val previewImproving: Boolean = false,
)

/** Commands and engine updates run on the owner's single-threaded dispatcher. */
class NavigationSession(
    private val search: DestinationSearch,
    private val engine: NavigationEngine,
    private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long = 30_000,
) {
    init { require(requestTimeoutMillis > 0) }
    private val mutableState = MutableStateFlow(NavigationState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var operation: Job? = null
    private var searchGeneration = 0L
    private var operationGeneration = 0L
    private var nextSessionId = 0L
    private var closed = false
    private val observation = scope.launch {
        engine.state.collect { update ->
            val current = state.value
            if (closed || update.sessionId == 0L || update.sessionId != current.sessionId ||
                current.phase !in setOf(NavigationPhase.STARTING, NavigationPhase.GUIDING)) return@collect
            mutableState.update {
                it.copy(
                    phase = when (update.phase) {
                        EnginePhase.STOPPED -> NavigationPhase.IDLE
                        EnginePhase.GUIDING -> NavigationPhase.GUIDING
                        EnginePhase.ARRIVED -> NavigationPhase.ARRIVED
                    },
                    progress = update.progress,
                    maneuver = update.maneuver,
                    rerouting = update.rerouting,
                    routeRevision = update.routeRevision,
                    route = (update.route ?: it.route).takeUnless { update.phase == EnginePhase.STOPPED },
                    sessionId = if (update.phase == EnginePhase.STOPPED) 0 else it.sessionId,
                    failure = update.failure,
                )
            }
        }
    }

    fun search(query: String) {
        if (closed) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        val text = query.trim()
        mutableState.update { it.copy(query = text, destinations = emptyList(), searching = text.isNotEmpty(), failure = null) }
        if (text.isEmpty()) return
        searchJob = scope.launch {
            try {
                val results = withTimeout(requestTimeoutMillis) { search.search(text) }
                if (generation != searchGeneration || closed) return@launch
                mutableState.update { it.copy(destinations = results, searching = false,
                    failure = NavigationFailure.NO_RESULTS.takeIf { results.isEmpty() }) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation == searchGeneration && !closed) mutableState.update {
                    it.copy(searching = false, failure = error.failure(NavigationFailure.SEARCH))
                }
            }
        }
    }

    fun preview(destination: Destination, options: RouteOptions = RouteOptions()) {
        if (closed || state.value.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)) return
        operation?.cancel()
        val generation = ++operationGeneration
        mutableState.update { it.copy(phase = NavigationPhase.PREVIEWING, route = null, alternatives = emptyList(), failure = null,
            previewDestination = destination, previewOptions = options, previewNeedsRefresh = false, previewImproving = options.scenic != null) }
        operation = scope.launch {
            try {
                val timeout = if (options.scenic == null) requestTimeoutMillis else maxOf(requestTimeoutMillis, 145_000)
                val route = withTimeout(timeout) {
                    engine.preview(destination, options) { provisional, choices ->
                        if (generation == operationGeneration && !closed) mutableState.update {
                            it.copy(phase = NavigationPhase.PREVIEW, route = provisional, alternatives = choices, previewImproving = true)
                        }
                    }
                }
                if (generation != operationGeneration || closed) return@launch
                mutableState.update { it.copy(phase = NavigationPhase.PREVIEW, route = route, alternatives = engine.alternatives, previewImproving = false) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation == operationGeneration && !closed) mutableState.update {
                    if (it.route != null) it.copy(phase = NavigationPhase.PREVIEW, previewImproving = false,
                        failure = error.failure(NavigationFailure.NETWORK))
                    else it.copy(phase = NavigationPhase.IDLE, previewImproving = false, failure = error.failure(NavigationFailure.NO_ROUTE))
                }
            }
        }
    }

    fun selectAlternative(id: String) {
        if (closed || state.value.phase != NavigationPhase.PREVIEW) return
        try {
            val plan = engine.selectPreview(id)
            mutableState.update { it.copy(route = plan, failure = null) }
        } catch (error: NavigationException) {
            mutableState.update { it.copy(failure = error.failure) }
        }
    }

    fun invalidatePreview() {
        if (closed || state.value.phase !in setOf(NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)) return
        operationGeneration++
        operation?.cancel()
        mutableState.update { it.copy(phase = NavigationPhase.IDLE, route = null, alternatives = emptyList(),
            progress = null, maneuver = null, failure = null, previewNeedsRefresh = true, previewImproving = false) }
    }

    fun start() {
        if (closed || state.value.phase != NavigationPhase.PREVIEW) return
        val route = state.value.route ?: return
        operation?.cancel()
        val generation = ++operationGeneration
        val sessionId = ++nextSessionId
        mutableState.update { it.copy(phase = NavigationPhase.STARTING, sessionId = sessionId, failure = null, previewImproving = false) }
        operation = scope.launch {
            try {
                withTimeout(requestTimeoutMillis) { engine.start(route, sessionId) }
                if (generation == operationGeneration && !closed && state.value.phase == NavigationPhase.STARTING) {
                    mutableState.update { it.copy(phase = NavigationPhase.GUIDING) }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation == operationGeneration && !closed) mutableState.update {
                    val running = engine.state.value.takeIf { update -> update.sessionId == sessionId && update.phase == EnginePhase.GUIDING }
                    it.copy(phase = if (running != null) NavigationPhase.GUIDING else NavigationPhase.PREVIEW,
                        sessionId = if (running != null) sessionId else 0, failure = error.failure(NavigationFailure.START))
                }
            }
        }
    }

    fun stop() {
        if (closed || state.value.phase == NavigationPhase.STOPPING) return
        operation?.cancel()
        val previous = state.value
        val generation = ++operationGeneration
        mutableState.update { it.copy(phase = NavigationPhase.STOPPING, failure = null, previewImproving = false) }
        operation = scope.launch {
            try {
                withTimeout(requestTimeoutMillis) { engine.stop() }
                if (generation == operationGeneration && !closed) mutableState.update {
                    it.copy(phase = NavigationPhase.IDLE, route = null, alternatives = emptyList(), sessionId = 0,
                        progress = null, maneuver = null, rerouting = false, routeRevision = 0)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation == operationGeneration && !closed) mutableState.update {
                    val engineState = engine.state.value
                    val restored = when {
                        engineState.sessionId == previous.sessionId && engineState.phase == EnginePhase.GUIDING -> NavigationPhase.GUIDING
                        engineState.sessionId == previous.sessionId && engineState.phase == EnginePhase.ARRIVED -> NavigationPhase.ARRIVED
                        previous.phase == NavigationPhase.PREVIEW -> NavigationPhase.PREVIEW
                        else -> NavigationPhase.IDLE
                    }
                    it.copy(phase = restored, failure = error.failure(NavigationFailure.STOP))
                }
            }
        }
    }

    fun clearFailure() { mutableState.update { it.copy(failure = null) } }

    fun close() {
        closed = true
        searchGeneration++
        operationGeneration++
        searchJob?.cancel()
        operation?.cancel()
        observation.cancel()
    }
}

private fun Exception.failure(fallback: NavigationFailure) = when (this) {
    is NavigationException -> failure
    is RoutingException -> navigationFailure()
    is TimeoutCancellationException -> NavigationFailure.TIMEOUT
    else -> fallback
}
