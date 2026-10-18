package com.theacar.weather

import com.theacar.model.Coordinate
import com.theacar.model.Weather
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpenMeteoWeatherSourceTest {
    private fun body(code: Int = 0, time: Int = 1000, day: Int = 1) = """{"latitude":43.057995,"longitude":141.42857,"elevation":123.0,"current":{"time":$time,"temperature_2m":12.5,"weather_code":$code,"is_day":$day}}"""

    @Test fun sendsRoundedCoordinatesAndNoCredentials() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("api.open-meteo.com", it.url.host)
            assertEquals("43.06", it.url.parameters["latitude"])
            assertEquals("141.35", it.url.parameters["longitude"])
            assertEquals("temperature_2m,weather_code,is_day", it.url.parameters["current"])
            assertEquals("unixtime", it.url.parameters["timeformat"])
            assertNull(it.headers[HttpHeaders.Authorization])
            respond(body())
        })
        val weather = OpenMeteoWeatherSource(client) { 1_000_000 }.current(Coordinate(43.061234, 141.354321))
        assertEquals(Weather.CLEAR, weather.weather)
        assertEquals(12.5, weather.temperatureCelsius)
        assertEquals(123.0, weather.elevationMeters)
        assertEquals(Coordinate(43.06, 141.35), weather.requestedCoordinate)
        assertEquals(Coordinate(43.057995, 141.42857), weather.gridCoordinate)
        assertTrue(weather.isDay)
        client.close()
    }

    @Test fun mapsWmoCodesAndDayNight() = runTest {
        for ((code, expected) in listOf(1 to Weather.CLEAR, 3 to Weather.CLOUDY, 48 to Weather.CLOUDY,
            55 to Weather.RAIN, 67 to Weather.RAIN, 95 to Weather.RAIN, 99 to Weather.RAIN, 71 to Weather.SNOW, 86 to Weather.SNOW)) {
            val client = HttpClient(MockEngine { respond(body(code, day = 0)) })
            val weather = OpenMeteoWeatherSource(client) { 1_000_000 }.current(Coordinate(43.0, 141.0))
            assertEquals(expected, weather.weather)
            assertFalse(weather.isDay)
            client.close()
        }
    }

    @Test fun rejectsOldAndFutureWeather() = runTest {
        for (time in listOf(1000, 9000)) {
            val client = HttpClient(MockEngine { respond(body(time = time)) })
            assertFails { OpenMeteoWeatherSource(client) { 5_000_000 }.current(Coordinate(43.0, 141.0)) }
            client.close()
        }
    }

    @Test fun rejectsUnknownWeatherAndMalformedResponses() = runTest {
        for (body in listOf(body(999), body(day = 2), "{}")) {
            val client = HttpClient(MockEngine { respond(body) })
            assertFails { OpenMeteoWeatherSource(client) { 1_000_000 }.current(Coordinate(43.0, 141.0)) }
            client.close()
        }
    }

    @Test fun doesNotReplaceServerErrorsWithSunnyWeather() = runTest {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.TooManyRequests) })
        assertFails { OpenMeteoWeatherSource(client) { 1_000_000 }.current(Coordinate(43.0, 141.0)) }
        client.close()
    }

    @Test fun rejectsInvalidCoordinatesBeforeRequesting() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond(body()) })
        val source = OpenMeteoWeatherSource(client) { 1_000_000 }
        for (coordinate in listOf(Coordinate(Double.NaN, 0.0), Coordinate(91.0, 0.0), Coordinate(0.0, -181.0))) {
            assertFails { source.current(coordinate) }
        }
        assertEquals(0, calls)
        client.close()
    }
}
