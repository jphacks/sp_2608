package com.theacar.weather

import com.theacar.environment.LocalWeather
import com.theacar.environment.WeatherSource
import com.theacar.model.Coordinate
import com.theacar.model.Weather
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import kotlin.math.round

fun weatherHttpClient() = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }
}

class OpenMeteoWeatherSource(private val client: HttpClient, private val nowMillis: () -> Long) : WeatherSource {
    override suspend fun current(coordinate: Coordinate): LocalWeather {
        require(coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0)
        require(coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0)
        val requested = Coordinate(round(coordinate.latitude * 100) / 100, round(coordinate.longitude * 100) / 100)
        val response = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", requested.latitude)
            parameter("longitude", requested.longitude)
            parameter("current", "temperature_2m,weather_code,is_day")
            parameter("timeformat", "unixtime")
            parameter("forecast_days", 1)
        }
        check(response.status.value in 200..299) { "Weather request failed" }
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val current = root.getValue("current").jsonObject
        val observed = current.getValue("time").jsonPrimitive.long * 1_000
        check(nowMillis() - observed in -300_000..3_600_000) { "Weather data is stale" }
        val weather = when (current.getValue("weather_code").jsonPrimitive.int) {
            0, 1 -> Weather.CLEAR
            2, 3, 45, 48 -> Weather.CLOUDY
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> Weather.RAIN
            71, 73, 75, 77, 85, 86 -> Weather.SNOW
            else -> error("Unknown weather code")
        }
        val temperature = current.getValue("temperature_2m").jsonPrimitive.double
        val elevation = root.getValue("elevation").jsonPrimitive.double
        val day = current.getValue("is_day").jsonPrimitive.int
        check(temperature.isFinite() && elevation.isFinite() && day in 0..1)
        val latitude = root["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = root["longitude"]?.jsonPrimitive?.doubleOrNull
        val grid = if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0) Coordinate(latitude, longitude) else null
        return LocalWeather(weather, temperature, day == 1, elevation, observed, requested, grid)
    }
}
