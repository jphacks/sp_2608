package com.theacar.android.music

import com.theacar.environment.WeatherSource
import com.theacar.location.LocationSource
import com.theacar.location.LocationStatus
import com.theacar.location.LocationUse
import com.theacar.location.usableFix
import com.theacar.music.DriveMusicSession
import com.theacar.music.MusicJourney
import com.theacar.music.MusicState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class EnvironmentCheckStatus { IDLE, LOCATING, FETCHING, LOCATION_UNAVAILABLE }

internal class DriveEnvironmentController(
    private val locations: LocationSource,
    private val acquireLocation: (Any) -> Unit,
    private val releaseLocation: (Any) -> Unit,
    private val weather: WeatherSource,
    private val driveMusic: DriveMusicSession,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val connected: () -> Boolean,
    private val music: () -> MusicState,
    private val journey: () -> MusicJourney?,
) {
    private var locationOwner: Any? = null
    private var driveJob: Job? = null
    private var environmentJob: Job? = null
    private var environmentGeneration = 0L
    private val mutableCheck = MutableStateFlow(EnvironmentCheckStatus.IDLE)
    val check = mutableCheck.asStateFlow()

    fun inspect() {
        if (environmentJob?.isActive == true) return
        val generation = ++environmentGeneration
        val owner = Any()
        mutableCheck.value = EnvironmentCheckStatus.LOCATING
        acquireLocation(owner)
        environmentJob = scope.launch {
            try {
                val location = withTimeoutOrNull(15_000) {
                    locations.location.first {
                        it.usableFix(LocationUse.ROUTE_PREVIEW, elapsedRealtime()) != null ||
                            it.status in setOf(LocationStatus.DISABLED, LocationStatus.PERMISSION_REQUIRED)
                    }
                }
                val fix = location?.usableFix(LocationUse.ROUTE_PREVIEW, elapsedRealtime())
                if (fix == null) mutableCheck.value = EnvironmentCheckStatus.LOCATION_UNAVAILABLE
                else {
                    mutableCheck.value = EnvironmentCheckStatus.FETCHING
                    weather.current(fix.coordinate)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                releaseLocation(owner)
                if (generation == environmentGeneration && mutableCheck.value != EnvironmentCheckStatus.LOCATION_UNAVAILABLE) {
                    mutableCheck.value = EnvironmentCheckStatus.IDLE
                }
            }
        }
    }

    fun stopInspection() {
        environmentGeneration++
        environmentJob?.cancel()
        environmentJob = null
        mutableCheck.value = EnvironmentCheckStatus.IDLE
    }

    fun startMusic() {
        if (!connected()) return
        stopMusic()
        driveMusic.start()
        val owner = Any()
        locationOwner = owner
        acquireLocation(owner)
        driveJob = scope.launch {
            try {
                while (isActive && driveMusic.state.value.enabled && connected()) {
                    val fix = locations.location.value.usableFix(LocationUse.ROUTE_PREVIEW, elapsedRealtime())
                    val playback = music()
                    driveMusic.update(fix?.coordinate, playback.selectedDeviceId, playback.busy, journey())
                    delay(3_000)
                }
            } finally { releaseLocation(owner) }
        }
    }

    fun stopMusic() {
        driveMusic.stop()
        driveJob?.cancel()
        driveJob = null
        locationOwner?.let(releaseLocation)
        locationOwner = null
    }
}
