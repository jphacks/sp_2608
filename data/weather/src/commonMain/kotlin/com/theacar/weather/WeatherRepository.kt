package com.theacar.weather

import com.theacar.environment.LocalWeather
import com.theacar.environment.WeatherSource
import com.theacar.model.Coordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.round

data class WeatherObservation(val coordinate: Coordinate, val weather: LocalWeather, val fetchedAtMillis: Long)
data class WeatherReadState(val observation: WeatherObservation? = null, val loading: Boolean = false, val failed: Boolean = false)

class WeatherRepository(private val source: WeatherSource, private val nowMillis: () -> Long) : WeatherSource {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(WeatherReadState())
    val state = mutableState.asStateFlow()

    override suspend fun current(coordinate: Coordinate): LocalWeather = mutex.withLock {
        require(coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0 &&
            coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0)
        val previous = state.value.observation
        if (previous != null && nowMillis() - previous.fetchedAtMillis in 0 until 60_000 &&
            cell(previous.coordinate) == cell(coordinate)) {
            mutableState.update { it.copy(failed = false) }
            return@withLock previous.weather
        }
        mutableState.update { it.copy(loading = true, failed = false) }
        try {
            val weather = source.current(coordinate)
            mutableState.value = WeatherReadState(WeatherObservation(coordinate, weather, nowMillis()))
            weather
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(failed = true) }
            throw error
        } finally { mutableState.update { it.copy(loading = false) } }
    }

    private fun cell(coordinate: Coordinate) = round(coordinate.latitude * 100) to round(coordinate.longitude * 100)
}
