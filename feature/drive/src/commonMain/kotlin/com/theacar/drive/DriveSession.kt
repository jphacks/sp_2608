package com.theacar.drive

import com.theacar.cabin.CabinPolicy
import com.theacar.cabin.CabinSuggestion
import com.theacar.environment.DriveContextSource
import com.theacar.model.DriveContext
import com.theacar.model.DriveMode
import com.theacar.music.ConversationVolumePolicy
import com.theacar.music.MusicRecommender
import com.theacar.music.PlaybackGateway
import com.theacar.route.NavigationGateway
import com.theacar.route.RouteRecommender
import com.theacar.route.RouteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DriveSession(
    private val source: DriveContextSource,
    private val routes: RouteRecommender,
    private val music: MusicRecommender,
    private val playback: PlaybackGateway,
    private val navigation: NavigationGateway,
    private val scope: CoroutineScope,
    private val cabin: CabinPolicy = CabinPolicy(),
    private val volumePolicy: ConversationVolumePolicy = ConversationVolumePolicy(),
) {
    private val mutex = Mutex()
    private var lastRouteRequest: RouteRequest? = null
    private var routeJob: Job? = null
    private var routeGeneration = 0L
    private var dismissedSuggestion: CabinSuggestion? = null
    private val initialContext = source.context.value
    private val mutableState = MutableStateFlow(
        DriveState(
            mode = DriveMode.GOALLESS,
            context = initialContext,
            suggestedRoute = null,
            selectedTrack = music.recommend(DriveMode.GOALLESS, initialContext),
            volume = volumePolicy.volume(initialContext),
            cabinSuggestion = cabin.suggest(initialContext),
        )
    )
    val state: StateFlow<DriveState> = mutableState.asStateFlow()

    private val observation = scope.launch {
        source.context.collect { context ->
            mutex.withLock {
                try {
                    refresh(context, state.value.mode)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableState.update { it.copy(failure = DriveFailure.ENVIRONMENT) }
                }
            }
        }
    }

    fun selectMode(mode: DriveMode): Job = command(DriveFailure.ROUTE_RECOMMENDATION) {
        refresh(source.context.value, mode)
    }

    fun retrySuggestions(): Job = command(DriveFailure.ROUTE_RECOMMENDATION) {
        lastRouteRequest = null
        refresh(source.context.value, state.value.mode)
    }

    fun togglePlayback(): Job = command(DriveFailure.PLAYBACK) {
        val current = state.value
        if (current.isPlaying) {
            playback.pause()
            mutableState.update { it.copy(isPlaying = false) }
        } else {
            playback.setVolume(current.volume)
            playback.play(current.selectedTrack)
            mutableState.update {
                it.copy(isPlaying = true, playingTrack = current.selectedTrack)
            }
        }
    }

    fun startRoute(): Job = command(DriveFailure.NAVIGATION) {
        val route = state.value.suggestedRoute ?: return@command
        if (route == state.value.activeRoute) return@command
        navigation.start(route)
        mutableState.update { it.copy(activeRoute = route) }
    }

    fun stopRoute(): Job = command(DriveFailure.NAVIGATION) {
        navigation.stop()
        mutableState.update { it.copy(activeRoute = null) }
    }

    fun dismissCabinSuggestion(): Job = command(DriveFailure.ENVIRONMENT) {
        dismissedSuggestion = state.value.cabinSuggestion
        mutableState.update { it.copy(cabinSuggestion = null) }
    }

    fun clearFailure() {
        mutableState.update { it.copy(failure = null) }
    }

    fun close() {
        observation.cancel()
        routeJob?.cancel()
    }

    private suspend fun refresh(context: DriveContext, mode: DriveMode) {
        val track = music.recommend(mode, context)
        val volume = volumePolicy.volume(context)
        val suggestion = cabin.suggest(context)
        if (suggestion != dismissedSuggestion) dismissedSuggestion = null
        mutableState.update {
            it.copy(
                mode = mode,
                context = context,
                selectedTrack = track,
                cabinSuggestion = suggestion.takeUnless { it == dismissedSuggestion },
            )
        }

        if (state.value.isPlaying) {
            try {
                val previous = state.value.playingTrack
                if (previous?.title != track.title || previous.artist != track.artist) {
                    playback.play(track)
                    mutableState.update { it.copy(playingTrack = track) }
                }
                playback.setVolume(volume)
                mutableState.update { it.copy(volume = volume) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(failure = DriveFailure.PLAYBACK) }
            }
        } else {
            mutableState.update { it.copy(volume = volume) }
        }

        val request = RouteRequest(
            mode, context.coordinate, context.weather, context.coastDistanceKm, context.localHour,
            context.scenery,
        )
        if (request == lastRouteRequest) return
        requestRoute(request)
    }

    private fun requestRoute(request: RouteRequest) {
        lastRouteRequest = request
        routeJob?.cancel()
        val generation = ++routeGeneration
        mutableState.update { it.copy(isRouteLoading = true, suggestedRoute = null) }
        routeJob = scope.launch {
            try {
                val route = routes.recommend(request)
                mutex.withLock {
                    if (generation == routeGeneration) {
                        mutableState.update {
                            it.copy(suggestedRoute = route, isRouteLoading = false)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutex.withLock {
                    if (generation == routeGeneration) {
                        mutableState.update {
                            it.copy(isRouteLoading = false, failure = DriveFailure.ROUTE_RECOMMENDATION)
                        }
                    }
                }
            }
        }
    }

    private fun command(failure: DriveFailure, action: suspend () -> Unit): Job = scope.launch {
        mutex.withLock {
            mutableState.update { it.copy(isActionRunning = true, failure = null) }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(failure = failure) }
            } finally {
                mutableState.update { it.copy(isActionRunning = false) }
            }
        }
    }
}
