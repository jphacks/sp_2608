package com.theacar.drive

import com.theacar.model.Coordinate
import com.theacar.navigation.*
import com.theacar.route.*
import kotlin.test.*

class NavigationLiveRouteTest {
    private val points = List(4) { Coordinate(35.0 + it * 0.01, 139.0) }
    private val route = RoutePlan("route", Destination("end", "江の島", points.last()), 40_000, 2_400, points)
    private val explanation = RouteExplanation(
        role = "海を楽しむ", snapshotId = "snap", revision = 1, totalSeconds = 2_400, stopSeconds = 0,
        reasons = emptyList(),
        timeline = listOf(
            ScenicTimelineBlock("sea", ScenicRange(6_000.0, 12_000.0), .6,
                listOf(ScenicRecommendedRoad("road-1", "秀逸な道の名前", ScenicRoadRank.SIGNATURE, listOf("osm")))),
            ScenicTimelineBlock("forest", ScenicRange(0.0, 6_000.0), .3),
            ScenicTimelineBlock("sea", ScenicRange(12_000.0, 20_000.0), .6),
            ScenicTimelineBlock("townscape", ScenicRange(36_000.0, 40_000.0), .3),
        ),
        warnings = emptyList(), sources = emptyList(), searchLimited = false, baselineSeconds = 2_000, load = .4,
    )
    private val guiding = NavigationState(phase = NavigationPhase.GUIDING, route = route, sessionId = 1,
        progress = NavigationProgress(25_000, 1_500), alternatives = listOf(RouteAlternative(route, null, explanation)))

    @Test fun sendsTheWholeRouteWithNeighbouringScenesMergedAndNoRoadNames() {
        val live = assertNotNull(NavigationLiveRoute { guiding }.current())
        assertEquals("江の島", live.destination)
        assertEquals(15_000.0, live.positionMeters)
        assertEquals(40_000.0, live.totalMeters)
        assertEquals(listOf("forest" to 0.0, "sea" to 6_000.0, "townscape" to 36_000.0), live.segments.map { it.category to it.startMeters })
        assertEquals(20_000.0, live.segments[1].endMeters)
        assertEquals(139.0 to 35.0, live.geometry.first())
        assertFalse(live.arrived)
    }

    @Test fun theKeyChangesWhenTheRouteIsReplaced() {
        val source = NavigationLiveRoute { guiding }
        val first = source.current()!!.key
        val rerouted = NavigationLiveRoute { guiding.copy(routeRevision = 1) }.current()!!.key
        assertNotEquals(first, rerouted)
        assertEquals(first, source.current()!!.key)
    }

    @Test fun reportsArrivalAndNothingWhenNotGuiding() {
        val arrived = assertNotNull(NavigationLiveRoute { guiding.copy(phase = NavigationPhase.ARRIVED, progress = null) }.current())
        assertTrue(arrived.arrived)
        assertEquals(40_000.0, arrived.positionMeters)
        assertNull(NavigationLiveRoute { guiding.copy(phase = NavigationPhase.PREVIEW) }.current())
    }

    @Test fun beforeTheGpsSettlesTheCarIsAtTheStart() {
        val live = assertNotNull(NavigationLiveRoute { guiding.copy(progress = null) }.current())
        assertEquals(0.0, live.positionMeters)
        assertNotNull(NavigationLiveRoute { guiding.copy(phase = NavigationPhase.STARTING, progress = null) }.current())
    }

    @Test fun aRouteWithoutScenicDataStillShowsTheMap() {
        val plain = guiding.copy(alternatives = emptyList())
        val live = assertNotNull(NavigationLiveRoute { plain }.current())
        assertTrue(live.segments.isEmpty())
        assertEquals(4, live.geometry.size)
    }
}
