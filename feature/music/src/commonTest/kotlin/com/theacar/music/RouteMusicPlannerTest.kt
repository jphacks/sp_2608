package com.theacar.music

import com.theacar.environment.LocalWeather
import com.theacar.model.Weather
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RouteMusicPlannerTest {
    private val weather = LocalWeather(Weather.CLEAR, 20.0, true, 0.0, 0)
    private fun track(id: String, seconds: Int?) = MusicTrack(id, id, "Artist", "", seconds?.times(1_000))
    private val journey = MusicJourney("route", "Destination", 1200, listOf(
        MusicRouteCue(MusicCueKind.HIGHWAY, "Highway", 300, 600),
        MusicRouteCue(MusicCueKind.WAYPOINT, "Stop", 900),
        MusicRouteCue(MusicCueKind.ARRIVAL, "Arrival", 1200),
    ))

    @Test fun placesThemesAtRouteEventsUsingFullTrackLengths() = runTest {
        val requests = mutableListOf<String>()
        val plan = RouteMusicPlanner().plan(weather, journey) { query ->
            requests += query
            when (query) {
                "sunny drive" -> listOf(track("base300", 300), track("base180", 180), track("base120", 120))
                "road trip pop" -> listOf(track("highway", 300))
                "acoustic chill" -> listOf(track("waypoint", 180))
                "mellow acoustic" -> listOf(track("arrival", 120))
                else -> emptyList()
            }
        }
        assertEquals(listOf(0, 300, 600, 780, 900, 1080), plan.map { it.startsAfterSeconds })
        assertEquals(listOf("base300", "highway", "base180", "base120", "waypoint", "arrival"), plan.map { it.track.uri })
        assertEquals(4, requests.size)
        assertTrue(plan.none { it.durationEstimated })
    }

    @Test fun rainAndSnowTakePriorityButStillFitRouteTiming() = runTest {
        for (condition in listOf(Weather.RAIN, Weather.SNOW)) {
            val queries = mutableListOf<String>()
            val plan = RouteMusicPlanner().plan(weather.copy(weather = condition), journey.copy(remainingSeconds = 180)) { query ->
                queries += query
                listOf(track("short", 180), track("long", 300))
            }
            assertEquals(1, queries.size)
            assertEquals(if (condition == Weather.RAIN) "rain chill" else "winter piano", queries.single())
            assertEquals("short", plan.first().track.uri)
        }
    }

    @Test fun withoutRouteKeepsWeatherSelectionAndMarksUnknownDurations() = runTest {
        val plan = RouteMusicPlanner().plan(weather, null) { listOf(track("a", null), track("b", null)) }
        assertEquals(listOf(0, 210), plan.map { it.startsAfterSeconds })
        assertTrue(plan.all { it.durationEstimated && it.theme.query == "sunny drive" })
    }

    @Test fun neverRepeatsATrackOrSchedulesMoreThanTen() = runTest {
        val plan = RouteMusicPlanner().plan(weather, null) { List(20) { track("track${it / 2}", 180) } }
        assertEquals(10, plan.size)
        assertEquals(10, plan.map { it.track.uri }.distinct().size)
    }

    @Test fun fallsBackToWeatherWhenRouteThemeHasNoResults() = runTest {
        val plan = RouteMusicPlanner().plan(weather, MusicJourney("r", "End", 120, emptyList())) {
            if (it == "sunny drive") listOf(track("base", 120)) else emptyList()
        }
        assertEquals("sunny drive", plan.single().theme.query)
        assertEquals("base", plan.single().track.uri)
    }

    @Test fun emptyResultsDoNotCreateFakeTracks() = runTest {
        assertTrue(RouteMusicPlanner().plan(weather, journey) { emptyList() }.isEmpty())
    }
}
