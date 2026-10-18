package com.theacar.weather

import com.theacar.environment.LocalWeather
import com.theacar.environment.WeatherSource
import com.theacar.model.Coordinate
import com.theacar.model.Weather
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherRepositoryTest {
    private val point = Coordinate(43.0612, 141.3543)
    private val weather = LocalWeather(Weather.CLEAR, 15.5, true, 26.0, 1_000_000)
    private inner class Source : WeatherSource {
        var calls = 0
        var error: Exception? = null
        var pending: CompletableDeferred<LocalWeather>? = null
        override suspend fun current(coordinate: Coordinate): LocalWeather {
            calls++
            error?.let { throw it }
            return pending?.await() ?: weather
        }
    }

    @Test fun publishesActualInputDataAndFetchTime() = runTest {
        val source = Source()
        val repository = WeatherRepository(source) { 1_010_000 }
        assertEquals(weather, repository.current(point))
        val observation = assertNotNull(repository.state.value.observation)
        assertEquals(point, observation.coordinate)
        assertEquals(1_010_000, observation.fetchedAtMillis)
        assertFalse(repository.state.value.loading)
        assertFalse(repository.state.value.failed)
    }

    @Test fun nearbyRequestsUseOneMinuteCacheButMovementAndExpiryFetchAgain() = runTest {
        val source = Source()
        var now = 1_010_000L
        val repository = WeatherRepository(source) { now }
        repository.current(point)
        repository.current(point.copy(latitude = 43.0613))
        assertEquals(1, source.calls)
        now += 60_000
        repository.current(point)
        assertEquals(2, source.calls)
        repository.current(Coordinate(44.0, 141.0))
        assertEquals(3, source.calls)
    }

    @Test fun firstFailureDoesNotInventWeather() = runTest {
        val repository = WeatherRepository(Source().apply { error = IllegalStateException() }) { 1_010_000 }
        assertFails { repository.current(point) }
        assertNull(repository.state.value.observation)
        assertTrue(repository.state.value.failed)
        assertFalse(repository.state.value.loading)
    }

    @Test fun failedRefreshRetainsThePreviousResultAndTimestamp() = runTest {
        val source = Source()
        var now = 1_010_000L
        val repository = WeatherRepository(source) { now }
        repository.current(point)
        val previous = repository.state.value.observation
        now += 60_000
        source.error = IllegalStateException()
        assertFails { repository.current(point) }
        assertEquals(previous, repository.state.value.observation)
        assertTrue(repository.state.value.failed)
    }

    @Test fun concurrentMusicAndInspectionRequestsShareTheResult() = runTest {
        val source = Source().apply { pending = CompletableDeferred() }
        val repository = WeatherRepository(source) { 1_010_000 }
        val first = backgroundScope.async { repository.current(point) }
        runCurrent()
        assertTrue(repository.state.value.loading)
        val second = backgroundScope.async { repository.current(point) }
        runCurrent()
        assertEquals(1, source.calls)
        source.pending!!.complete(weather)
        runCurrent()
        assertEquals(weather, first.await())
        assertEquals(weather, second.await())
        assertEquals(1, source.calls)
    }

    @Test fun cancellationClearsLoadingWithoutInventingANetworkError() = runTest {
        val source = Source().apply { pending = CompletableDeferred() }
        val repository = WeatherRepository(source) { 1_010_000 }
        val request = backgroundScope.launch { repository.current(point) }
        runCurrent()
        request.cancelAndJoin()
        assertFalse(repository.state.value.loading)
        assertFalse(repository.state.value.failed)
        assertNull(repository.state.value.observation)
    }

    @Test fun invalidCoordinatesCannotHitTheCache() = runTest {
        val source = Source()
        val repository = WeatherRepository(source) { 1_010_000 }
        repository.current(Coordinate(90.0, 0.0))
        assertFailsWith<IllegalArgumentException> { repository.current(Coordinate(90.001, 0.0)) }
        assertEquals(1, source.calls)
    }
}
