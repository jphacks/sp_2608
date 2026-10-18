package com.theacar.valhalla

import kotlinx.serialization.json.*
import kotlin.test.*

class ScenicEnvironmentParserTest {
    private val fixture get() = Json.parseToJsonElement("""
        {"timeline":[{"start_m":0,"end_m":100,"start_time":"2026-10-17T08:00:00Z","end_time":"2026-10-17T08:01:00Z",
        "arrival_time":"2026-10-17T08:00:30Z","elapsed_seconds":30,"solar_altitude":5,"solar_azimuth":270,
        "light_phase":"dusk","sky":"unknown","cloud_cover":null,"precipitation":null,"features":{"lake":0.8,"river":null},
        "urban_ratio":0.1,"tunnel":false}],"best_spots":[{"start_m":0,"end_m":100,"distance_m":50,
        "arrival_time":"2026-10-17T08:00:30Z","category":"lake","reasons":["lake_proximity","forecast_unavailable"],
        "confidence":"low","stopping_verified":false}],"sampling_meters":100,"landscape_available":true,"warning":"推定"}
    """)

    @Test fun optionalFieldsKeepOldApiCompatible() {
        assertNull(parseScenicEnvironment(null, 100.0))
        assertNull(parseScenicEnvironment(JsonNull, 100.0))
    }

    @Test fun parsesAlignedTimesWaterAndUnverifiedSpots() {
        val result = assertNotNull(parseScenicEnvironment(fixture, 100.0))
        assertEquals("unknown", result.sections.single().sky)
        assertNull(result.sections.single().cloudCover)
        assertEquals(.8, result.sections.single().features["lake"])
        assertEquals(50.0, result.bestSpots.single().distanceMeters)
        assertEquals("low", result.bestSpots.single().confidence)
    }

    @Test fun parsesOptionalTerrainAndSignedTemperatureWithoutClaimingRoadSnowDepth() {
        val raw = fixture.toString().replace("\"tunnel\":false", "\"tunnel\":false,\"elevation_m\":123.4,\"terrain_valley\":0.8,\"weather_details\":{\"temperature_2m\":-5.2,\"snow_depth\":0.3}")
        val result = parseScenicEnvironment(Json.parseToJsonElement(raw), 100.0)!!.sections.single()
        assertEquals(123.4, result.elevationMeters)
        assertTrue(result.terrainDerived)
        assertEquals(-5.2, result.weatherDetails["temperature_2m"])
        assertEquals(.3, result.weatherDetails["snow_depth"])
        assertFails { parseScenicEnvironment(Json.parseToJsonElement(raw.replace("123.4", "12345.0")), 100.0) }
    }

    @Test fun invalidScoresOrOutOfRoutePositionsAreRejected() {
        assertFails { parseScenicEnvironment(Json.parseToJsonElement(fixture.toString().replace("0.8", "1.8")), 100.0) }
        assertFails { parseScenicEnvironment(fixture, 10.0) }
        assertFails { parseScenicEnvironment(Json.parseToJsonElement(fixture.toString().replace("\"stopping_verified\":false", "\"stopping_verified\":true")), 100.0) }
    }
}
