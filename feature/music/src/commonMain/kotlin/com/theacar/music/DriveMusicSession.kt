package com.theacar.music

import com.theacar.environment.LocalWeather
import com.theacar.environment.WeatherSource
import com.theacar.model.Coordinate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.*

data class DriveMusicState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val waitingForLocation: Boolean = false,
    val weather: LocalWeather? = null,
    val coordinate: Coordinate? = null,
    val theme: DriveMusicTheme? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val failure: MusicFailure? = null,
    val needsPlayback: Boolean = false,
    val journey: MusicJourney? = null,
    val schedule: List<ScheduledMusicTrack> = emptyList(),
    val plannedAtMillis: Long = 0,
)

class DriveMusicSession(
    private val weatherSource: WeatherSource,
    private val music: MusicService,
    private val playMix: suspend (List<MusicTrack>, String) -> Boolean,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val policy: DriveMusicPolicy = DriveMusicPolicy(),
    private val planner: RouteMusicPlanner = RouteMusicPlanner(policy),
) {
    private val mutableState = MutableStateFlow(DriveMusicState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var lastRequestAt: Long? = null
    private var generation = 0L
    private var startPending = false
    private var latestJourneyKey: String? = null
    private val searchCache = mutableMapOf<String, Pair<Long, List<MusicTrack>>>()

    fun start() {
        stop()
        startPending = true
        mutableState.update { it.copy(enabled = true, waitingForLocation = true, failure = null) }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        startPending = false
        mutableState.update { it.copy(enabled = false, loading = false, waitingForLocation = false) }
    }

    fun reset() { stop(); lastRequestAt = null; searchCache.clear(); mutableState.value = DriveMusicState() }

    fun update(coordinate: Coordinate?, deviceId: String?, playbackBusy: Boolean, journey: MusicJourney? = null) {
        latestJourneyKey = journey?.key
        if (!state.value.enabled || job?.isActive == true) return
        mutableState.update { it.copy(waitingForLocation = coordinate == null) }
        if (coordinate == null || playbackBusy) return
        if (deviceId == null) { fail(MusicFailure.NO_DEVICE); return }
        val now = nowMillis()
        val previous = state.value
        val age = lastRequestAt?.let { now - it }
        val stale = previous.weather == null || now - previous.weather.observedAtMillis > 3_600_000
        val moved = previous.coordinate?.let { distanceKm(it, coordinate) >= 10 } ?: true
        val due = age == null || age >= 900_000 || (age >= 120_000 && moved)
        val routeChanged = journey?.key != previous.journey?.key
        val etaChanged = journey != null && previous.journey != null &&
            abs(journey.remainingSeconds - (previous.journey.remainingSeconds - (now - previous.plannedAtMillis) / 1_000)) >= 120
        if (!startPending && !due && !routeChanged && !etaChanged) return
        val currentGeneration = generation
        mutableState.update { it.copy(loading = true, failure = null) }
        job = scope.launch {
            try {
                val weather = if (due || stale) {
                    lastRequestAt = now
                    try { weatherSource.current(coordinate) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        throw MusicException(MusicFailure.WEATHER_UNAVAILABLE)
                    }
                } else requireNotNull(previous.weather)
                val schedule = planner.plan(weather, journey) { query ->
                    val cached = searchCache[query]?.takeIf { now - it.first in 0..900_000 }
                    cached?.second ?: music.search(query).also {
                        if (searchCache.size >= 8) searchCache.remove(searchCache.keys.first())
                        searchCache[query] = now to it
                    }
                }
                val tracks = schedule.map { it.track }
                if (tracks.isEmpty()) throw MusicException(MusicFailure.NO_RESULTS)
                if (currentGeneration != generation || latestJourneyKey != journey?.key) return@launch
                mutableState.update { it.copy(weather = weather, coordinate = if (due) coordinate else previous.coordinate,
                    theme = schedule.first().theme, tracks = tracks, needsPlayback = it.needsPlayback || tracks != previous.tracks,
                    journey = journey, schedule = schedule, plannedAtMillis = now) }
                if (startPending) {
                    if (!playMix(tracks, deviceId)) throw MusicException(MusicFailure.UNKNOWN)
                    if (currentGeneration != generation) return@launch
                    startPending = false
                    mutableState.update { it.copy(needsPlayback = false) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentGeneration == generation) fail((error as? MusicException)?.failure ?: MusicFailure.NETWORK)
            } finally {
                if (currentGeneration == generation) mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private fun fail(failure: MusicFailure) {
        startPending = false
        mutableState.update { it.copy(enabled = false, failure = failure, waitingForLocation = false) }
    }

    private fun distanceKm(a: Coordinate, b: Coordinate): Double {
        val lat = (b.latitude - a.latitude) * PI / 180
        val lon = (b.longitude - a.longitude) * PI / 180
        val h = sin(lat / 2).pow(2) + cos(a.latitude * PI / 180) * cos(b.latitude * PI / 180) * sin(lon / 2).pow(2)
        return 12_742 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
