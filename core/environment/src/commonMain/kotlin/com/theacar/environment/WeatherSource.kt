package com.theacar.environment

import com.theacar.model.Coordinate
import com.theacar.model.Weather

data class LocalWeather(
    val weather: Weather,
    val temperatureCelsius: Double,
    val isDay: Boolean,
    val elevationMeters: Double,
    val observedAtMillis: Long,
    val requestedCoordinate: Coordinate? = null,
    val gridCoordinate: Coordinate? = null,
)

interface WeatherSource {
    suspend fun current(coordinate: Coordinate): LocalWeather
}
