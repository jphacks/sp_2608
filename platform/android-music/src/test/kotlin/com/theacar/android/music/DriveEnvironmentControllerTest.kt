package com.theacar.android.music

import com.theacar.environment.LocalWeather
import com.theacar.environment.WeatherSource
import com.theacar.location.*
import com.theacar.model.Coordinate
import com.theacar.model.Weather
import com.theacar.music.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriveEnvironmentControllerTest {
    private val coordinate = Coordinate(43.06, 141.35)

    private inner class Fixture(scope: CoroutineScope, now: () -> Long) {
        val owners = mutableSetOf<Any>()
        val location = MutableStateFlow(LocationState())
        var connected = true
        var music = MusicState(selectedDeviceId = "phone")
        var weatherCalls = 0
        var weatherPending: CompletableDeferred<LocalWeather>? = null
        var weatherFailure: Exception? = null
        val weather = object : WeatherSource {
            override suspend fun current(coordinate: Coordinate): LocalWeather {
                weatherCalls++
                weatherFailure?.let { throw it }
                return weatherPending?.await() ?: LocalWeather(Weather.CLEAR, 20.0, true, 10.0, now())
            }
        }
        val service = object : MusicService {
            override suspend fun search(query: String) = emptyList<MusicTrack>()
            override suspend fun devices() = emptyList<MusicDevice>()
            override suspend fun playback() = MusicPlayback()
            override suspend fun play(trackUri: String?, deviceId: String) = Unit
            override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) = Unit
            override suspend fun pause(deviceId: String) = Unit
            override suspend fun next(deviceId: String) = Unit
            override suspend fun previous(deviceId: String) = Unit
        }
        val session = DriveMusicSession(weather, service, { _, _ -> true }, scope, now)
        val controller = DriveEnvironmentController(
            object : LocationSource { override val location = this@Fixture.location },
            { owners.add(it) }, { owners.remove(it) }, weather, session, scope, now,
            { connected }, { music }, { null },
        )

        fun locate() {
            location.value = LocationState(LocationStatus.AVAILABLE, LocationFix(coordinate, 5f, 0))
        }
    }

    @Test fun inspectionFetchesWeatherAndReleasesLocation() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.locate()
        f.weatherPending = CompletableDeferred()
        f.controller.inspect()
        runCurrent()
        assertEquals(EnvironmentCheckStatus.FETCHING, f.controller.check.value)
        f.controller.inspect()
        assertEquals(1, f.owners.size)
        assertEquals(1, f.weatherCalls)
        f.weatherPending!!.complete(LocalWeather(Weather.CLEAR, 20.0, true, 10.0, 0))
        runCurrent()
        assertEquals(EnvironmentCheckStatus.IDLE, f.controller.check.value)
        assertTrue(f.owners.isEmpty())
    }

    @Test fun unavailableLocationDoesNotFetchWeather() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.location.value = LocationState(LocationStatus.PERMISSION_REQUIRED)
        f.controller.inspect()
        runCurrent()
        assertEquals(EnvironmentCheckStatus.LOCATION_UNAVAILABLE, f.controller.check.value)
        assertEquals(0, f.weatherCalls)
        assertTrue(f.owners.isEmpty())
    }

    @Test fun locationTimeoutReleasesLocation() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.controller.inspect()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(EnvironmentCheckStatus.LOCATION_UNAVAILABLE, f.controller.check.value)
        assertEquals(0, f.weatherCalls)
        assertTrue(f.owners.isEmpty())
    }

    @Test fun stoppingInspectionLeavesMusicLocationOwnerActive() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.controller.startMusic()
        f.controller.inspect()
        runCurrent()
        assertEquals(2, f.owners.size)
        f.controller.stopInspection()
        runCurrent()
        assertEquals(EnvironmentCheckStatus.IDLE, f.controller.check.value)
        assertEquals(1, f.owners.size)
        assertTrue(f.session.state.value.enabled)
        f.controller.stopMusic()
        runCurrent()
        assertTrue(f.owners.isEmpty())
        assertFalse(f.session.state.value.enabled)
    }

    @Test fun weatherFailureReleasesLocation() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.locate()
        f.weatherFailure = IllegalStateException("offline")
        f.controller.inspect()
        runCurrent()
        assertEquals(EnvironmentCheckStatus.IDLE, f.controller.check.value)
        assertTrue(f.owners.isEmpty())
    }

    @Test fun cancelledInspectionDoesNotOverwriteNextInspection() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.controller.inspect()
        runCurrent()
        f.controller.stopInspection()
        f.controller.inspect()
        runCurrent()
        assertEquals(EnvironmentCheckStatus.LOCATING, f.controller.check.value)
        assertEquals(1, f.owners.size)
        f.controller.stopInspection()
        runCurrent()
        assertTrue(f.owners.isEmpty())
    }

    @Test fun musicWaitsForNextTickAndStopsUpdatingAfterStop() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.controller.startMusic()
        runCurrent()
        f.locate()
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(0, f.weatherCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, f.weatherCalls)
        f.controller.stopMusic()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, f.weatherCalls)
        assertTrue(f.owners.isEmpty())
    }

    @Test fun musicDoesNotStartWithoutConnection() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }
        f.connected = false
        f.controller.startMusic()
        runCurrent()
        assertFalse(f.session.state.value.enabled)
        assertTrue(f.owners.isEmpty())
    }
}
