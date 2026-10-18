package com.theacar.music

import com.theacar.environment.LocalWeather
import com.theacar.environment.WeatherSource
import com.theacar.model.Coordinate
import com.theacar.model.Weather
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriveMusicSessionTest {
    private val coordinate = Coordinate(43.06, 141.35)
    private val track = MusicTrack("spotify:track:0123456789012345678901", "Song", "Artist", "https://open.spotify.com/track/0123456789012345678901")
    private class Service : MusicService {
        var searches = 0
        var tracks: List<MusicTrack> = emptyList()
        override suspend fun search(query: String): List<MusicTrack> { searches++; return tracks }
        override suspend fun devices() = emptyList<MusicDevice>()
        override suspend fun playback() = MusicPlayback()
        override suspend fun play(trackUri: String?, deviceId: String) = Unit
        override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) = Unit
        override suspend fun pause(deviceId: String) = Unit
        override suspend fun next(deviceId: String) = Unit
        override suspend fun previous(deviceId: String) = Unit
    }

    private inner class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        var time = 10_000L
        var weatherCalls = 0
        var plays = 0
        var playedDevice: String? = null
        var weather = LocalWeather(Weather.CLEAR, 20.0, true, 10.0, time)
        var pending: CompletableDeferred<LocalWeather>? = null
        var failure: Exception? = null
        val music = Service().apply { tracks = listOf(track) }
        val session = DriveMusicSession(object : WeatherSource {
            override suspend fun current(coordinate: Coordinate): LocalWeather {
                weatherCalls++
                failure?.let { throw it }
                return pending?.await() ?: weather.copy(observedAtMillis = time)
            }
        }, music, { _, device -> plays++; playedDevice = device; true }, scope, { time })
        fun tick() = session.update(coordinate, "phone", false)
    }

    @Test fun doesNothingWithoutExplicitStart() = runTest {
        val f = Fixture(backgroundScope)
        f.tick(); runCurrent()
        assertEquals(0, f.weatherCalls)
        assertEquals(0, f.plays)
    }

    @Test fun waitsForLocationAndBusyPlaybackWithoutRequests() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start()
        f.session.update(null, "phone", false); runCurrent()
        assertTrue(f.session.state.value.waitingForLocation)
        f.session.update(coordinate, "phone", true); runCurrent()
        assertEquals(0, f.weatherCalls)
    }

    @Test fun missingDeviceStopsInsteadOfChoosingAnotherDevice() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start()
        f.session.update(coordinate, null, false); runCurrent()
        assertEquals(MusicFailure.NO_DEVICE, f.session.state.value.failure)
        assertFalse(f.session.state.value.enabled)
        assertEquals(0, f.weatherCalls)
    }

    @Test fun startsMixOnTheChosenDeviceAndDoesNotRestartOnEveryTick() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start(); f.tick(); runCurrent()
        repeat(20) { f.tick(); runCurrent() }
        assertEquals(1, f.plays)
        assertEquals("phone", f.playedDevice)
        assertEquals(1, f.weatherCalls)
        assertEquals(1, f.music.searches)
    }

    @Test fun refreshesWeatherAfterFifteenMinutesWithoutInterruptingPlayback() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start(); f.tick(); runCurrent()
        f.time += 900_000
        f.weather = f.weather.copy(weather = Weather.RAIN)
        f.music.tracks = listOf(track.copy(uri = "spotify:track:1123456789012345678901", title = "Rain"))
        f.tick(); runCurrent()
        assertEquals(2, f.weatherCalls)
        assertEquals("雨の日の、チル", f.session.state.value.theme?.title)
        assertTrue(f.session.state.value.needsPlayback)
        assertEquals(1, f.plays)
    }

    @Test fun movementRequiresTenKilometersAndTwoMinutes() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start(); f.tick(); runCurrent()
        f.session.update(Coordinate(44.0, 141.0), "phone", false); runCurrent()
        assertEquals(1, f.weatherCalls)
        f.time += 120_000
        f.session.update(Coordinate(44.0, 141.0), "phone", false); runCurrent()
        assertEquals(2, f.weatherCalls)
        assertEquals(1, f.music.searches)
    }

    @Test fun explicitRestartUsesCachedWeatherAndReplaysTheMix() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start(); f.tick(); runCurrent()
        f.session.start(); f.tick(); runCurrent()
        assertEquals(1, f.weatherCalls)
        assertEquals(2, f.plays)
    }

    @Test fun stopCancelsPendingWeatherAndPreventsDelayedPlayback() = runTest {
        val f = Fixture(backgroundScope)
        f.pending = CompletableDeferred()
        f.session.start(); f.tick(); runCurrent()
        f.session.stop()
        f.pending!!.complete(f.weather); runCurrent()
        assertEquals(0, f.plays)
        assertFalse(f.session.state.value.enabled)
        assertFalse(f.session.state.value.loading)
    }

    @Test fun failureStopsAutomaticRetryAndDoesNotUseDemoWeather() = runTest {
        val f = Fixture(backgroundScope)
        f.failure = IllegalStateException("Offline")
        f.session.start(); f.tick(); runCurrent()
        repeat(10) { f.time += 900_000; f.tick(); runCurrent() }
        assertEquals(1, f.weatherCalls)
        assertEquals(0, f.plays)
        assertNull(f.session.state.value.weather)
        assertEquals(MusicFailure.WEATHER_UNAVAILABLE, f.session.state.value.failure)
    }

    @Test fun emptySearchDoesNotSendPlayback() = runTest {
        val f = Fixture(backgroundScope)
        f.music.tracks = emptyList()
        f.session.start(); f.tick(); runCurrent()
        assertEquals(0, f.plays)
        assertFalse(f.session.state.value.enabled)
    }

    @Test fun newRouteRevisionReplansWithoutFetchingWeatherOrInterruptingPlayback() = runTest {
        val f = Fixture(backgroundScope)
        f.session.start(); f.tick(); runCurrent()
        val route = MusicJourney("route:1", "Destination", 120, emptyList())
        f.session.update(coordinate, "phone", false, route); runCurrent()
        assertEquals("route:1", f.session.state.value.journey?.key)
        assertEquals("mellow acoustic", f.session.state.value.schedule.first().theme.query)
        f.session.update(coordinate, "phone", false, route.copy(key = "route:2")); runCurrent()
        assertEquals("route:2", f.session.state.value.journey?.key)
        assertEquals(1, f.weatherCalls)
        assertEquals(1, f.plays)
        assertEquals(2, f.music.searches)
    }

    @Test fun largeEtaChangeReplansTheProposalButDoesNotRestartMusic() = runTest {
        val f = Fixture(backgroundScope)
        val route = MusicJourney("route", "Destination", 120, emptyList())
        f.session.start()
        f.session.update(coordinate, "phone", false, route); runCurrent()
        f.session.update(coordinate, "phone", false, route.copy(remainingSeconds = 600)); runCurrent()
        assertEquals(600, f.session.state.value.journey?.remainingSeconds)
        assertEquals("sunny drive", f.session.state.value.schedule.first().theme.query)
        assertEquals(1, f.plays)
    }

    @Test fun routeChangingDuringWeatherFetchPreventsStalePlanPlayback() = runTest {
        val f = Fixture(backgroundScope)
        f.pending = CompletableDeferred()
        val first = MusicJourney("first", "Destination", 120, emptyList())
        val second = first.copy(key = "second")
        f.session.start()
        f.session.update(coordinate, "phone", false, first); runCurrent()
        f.session.update(coordinate, "phone", false, second)
        f.pending!!.complete(f.weather); runCurrent()
        assertEquals(0, f.plays)
        f.session.update(coordinate, "phone", false, second); runCurrent()
        assertEquals(1, f.plays)
        assertEquals("second", f.session.state.value.journey?.key)
    }

    @Test fun policyPrioritizesSnowRainNightAndElevation() {
        val policy = DriveMusicPolicy()
        val weather = LocalWeather(Weather.CLEAR, 20.0, true, 10.0, 0)
        assertEquals("sunny drive", policy.theme(weather).query)
        assertEquals("acoustic folk", policy.theme(weather.copy(elevationMeters = 600.0)).query)
        assertEquals("night city pop", policy.theme(weather.copy(isDay = false)).query)
        assertEquals("rain chill", policy.theme(weather.copy(weather = Weather.RAIN, isDay = false)).query)
        assertEquals("winter piano", policy.theme(weather.copy(weather = Weather.SNOW, isDay = false)).query)
        assertEquals("indie mellow", policy.theme(weather.copy(weather = Weather.CLOUDY)).query)
    }
}
