package com.theacar.music

import com.theacar.environment.LocalWeather
import com.theacar.model.Weather
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SceneryMusicPolicyTest {
    private val weather = LocalWeather(Weather.CLEAR, 20.0, true, 0.0, 0)
    private val policy = SceneryMusicPolicy()
    private fun cue(category: String, start: Int = 0, end: Int = 600, confidence: Double? = .3) =
        MusicSceneryCue(category, start, end, confidence)

    @Test fun selectsEachLandscapeAndNightVariant() {
        for ((category, query) in mapOf("sea" to "summer soft pop", "forest" to "acoustic folk",
            "valley" to "acoustic folk", "urban" to "mellow soul", "lake" to "acoustic chill",
            "farmland" to "indie folk")) {
            assertEquals(query, policy.theme(weather, listOf(cue(category)), 0)?.query)
        }
        assertEquals("chill soul", policy.theme(weather.copy(isDay = false), listOf(cue("sea")), 0)?.query)
    }

    @Test fun missingUnknownWeakAndBriefEvidenceDoNotInventAScene() {
        for (cues in listOf(emptyList(), listOf(cue("unknown")), listOf(cue("sea", confidence = .1)),
            listOf(cue("sea", end = 105)), listOf(cue("sea", end = 80), cue("sea", end = 80)))) {
            assertNull(policy.theme(weather, cues, 0))
        }
    }

    @Test fun looksAheadAndUsesTheMajorityRatherThanAShortCurrentScene() {
        assertEquals("acoustic folk", policy.theme(weather,
            listOf(cue("sea", end = 60), cue("forest", start = 60)), 0)?.query)
        assertNull(policy.theme(weather, listOf(cue("sea", end = 100)), 100))
    }

    @Test fun plannerUsesSceneryButPrioritizesWeatherAndArrival() = runTest {
        suspend fun query(condition: Weather, remaining: Int): String {
            val journey = MusicJourney("r", "destination", remaining, emptyList(), listOf(cue("sea")))
            return RouteMusicPlanner().plan(weather.copy(weather = condition), journey) {
                listOf(MusicTrack("track", "Track", "Artist", "", 210_000))
            }.first().theme.query
        }
        assertEquals("summer soft pop", query(Weather.CLEAR, 600))
        assertEquals("rain chill", query(Weather.RAIN, 600))
        assertEquals("winter piano", query(Weather.SNOW, 600))
        assertEquals("mellow acoustic", query(Weather.CLEAR, 180))
    }
}
