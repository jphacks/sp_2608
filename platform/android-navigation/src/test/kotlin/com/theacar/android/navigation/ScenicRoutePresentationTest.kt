package com.theacar.android.navigation

import com.theacar.model.Coordinate
import com.theacar.route.*
import kotlin.test.*

class ScenicRoutePresentationTest {
    @Test fun waterAndValleyMetricsAppearInTitlesTraitsAndSummary() {
        val lake = route("lake", 1200, 0, 100).let { it.copy(scenic = it.scenic!!.copy(categoryMeters = mapOf("lake" to 7000, "valley" to 3000))) }
        val other = route("other", 900, 0, 100)
        assertTrue(routeChoiceTitle(lake, listOf(lake, other)).contains("湖"))
        assertTrue(routeChoiceTraits(lake, listOf(lake, other)).any { it.contains("湖") })
        assertTrue(routeLandscapeSummary(lake.scenic!!).contains("渓谷"))
        assertFalse(routeLandscapeSummary(lake.scenic!!).contains("海に近い"))
    }

    private val destination = Destination("end", "目的地", Coordinate(43.0, 141.0))
    private fun route(id: String, seconds: Int, sea: Int, forest: Int) = RouteAlternative(
        RoutePlan(id, destination, 10000, seconds, emptyList()), ScenicMetrics(60, sea, forest, 1000, .9))

    @Test fun featuresAreMeasuredAcrossAllCandidatesRatherThanFixedRoles() {
        val sea = route("sea", 1200, 7000, 1000)
        val forest = route("forest", 1100, 2000, 8000)
        val fast = route("fast", 900, 1000, 500)
        val all = listOf(sea, forest, fast)
        assertTrue("海に近い区間が最多" in routeChoiceTraits(sea, all))
        assertTrue("森林区間が最多" in routeChoiceTraits(forest, all))
        assertTrue("走行時間が最短（候補内）" in routeChoiceTraits(fast, all))
        assertFalse("走行時間が最短（候補内）" in routeChoiceTraits(sea, all))
    }

    @Test fun commonDistanceAxisDoesNotStretchShorterRoutes() {
        val short = listOf(ScenicTimelineBlock("sea", ScenicRange(0.0, 1000.0), .3))
        val long = listOf(ScenicTimelineBlock("forest", ScenicRange(0.0, 2000.0), .3))
        assertEquals(2000.0, sceneryAxisMeters(short, false, 2000.0))
        assertEquals(2000.0, sceneryAxisMeters(long, false, 2000.0))
        assertEquals(1000.0, sceneryAxisMeters(short, true, 2000.0))
        assertEquals(2000.0, sceneryAxisMeters(long, true, 2000.0))
        assertEquals(1.0, sceneryAxisMeters(emptyList(), true, 0.0))
    }

    @Test fun negativeDifferencesAndMissingSceneryAreClear() {
        assertEquals("−2分", routeTimeDelta(-61))
        assertEquals("+2分", routeTimeDelta(61))
        assertEquals("同じ", routeTimeDelta(0))
        assertEquals("−1.5km", routeDistanceDelta(-1500))
        assertEquals("景観データなし", routeSceneryDescription(emptyList()))
    }

    @Test fun aFasterRouteIsNotPresentedAsZeroExtraTime() {
        val fast = route("fast", 900, 1000, 500).copy(explanation = RouteExplanation(
            "role", "snapshot", 1, 900, 0, emptyList(), emptyList(), emptyList(), emptyList(),
            false, 1200, .3))
        assertEquals(-300, routeDrivingDifference(fast))
        val reference = ScenicRouteReference(900, 10000, 0, fast.scenic!!, emptyList(), .3)
        val same = fast.copy(explanation = fast.explanation!!.copy(baselineSeconds = 901, reference = reference))
        assertEquals(0, routeDrivingDifference(same))
    }
}
