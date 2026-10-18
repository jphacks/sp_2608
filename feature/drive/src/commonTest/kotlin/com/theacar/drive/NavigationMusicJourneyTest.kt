package com.theacar.drive

import com.theacar.model.Coordinate
import com.theacar.music.MusicCueKind
import com.theacar.navigation.*
import com.theacar.route.*
import kotlin.test.*

class NavigationMusicJourneyTest {
    private val points = List(4) { Coordinate(it * 0.01, 0.0) }
    private val route = RoutePlan("route", Destination("end", "End", points.last()), 3300, 900, points,
        listOf(RouteStep("Start", "", "", 0, 1, 1, 100),
            RouteStep("Highway", "", "", 1, 2, 8, 500, highway = true),
            RouteStep("End", "", "", 2, 3, 4, 300)), listOf(RouteWaypoint(points[2], 2)))

    @Test fun convertsTimedStepsAndOffsetsCuesByNavigationProgress() {
        var state = NavigationState(phase = NavigationPhase.GUIDING, route = route, sessionId = 1,
            progress = NavigationProgress(3000, 800))
        val source = NavigationMusicJourney { state }
        val journey = assertNotNull(source.current())
        assertEquals(800, journey.remainingSeconds)
        assertEquals(500, journey.cues.single { it.kind == MusicCueKind.WAYPOINT }.secondsFromNow)
        assertEquals(0, journey.cues.single { it.kind == MusicCueKind.HIGHWAY }.secondsFromNow)
        assertEquals(500, journey.cues.single { it.kind == MusicCueKind.HIGHWAY }.endSecondsFromNow)
        state = state.copy(progress = NavigationProgress(1000, 200))
        assertTrue(source.current()!!.cues.none { it.kind == MusicCueKind.HIGHWAY || it.kind == MusicCueKind.WAYPOINT })
        assertEquals(200, source.current()!!.cues.single().secondsFromNow)
    }

    @Test fun previewRerouteAndArrivalDoNotBecomeAnActiveMusicRoute() {
        var state = NavigationState(phase = NavigationPhase.PREVIEW, route = route)
        val source = NavigationMusicJourney { state }
        assertNull(source.current())
        state = state.copy(phase = NavigationPhase.GUIDING, rerouting = true)
        assertNull(source.current())
        state = state.copy(phase = NavigationPhase.ARRIVED, rerouting = false)
        assertNull(source.current())
    }

    @Test fun routeRevisionAndSessionChangeInvalidateTheMusicPlanKey() {
        var state = NavigationState(phase = NavigationPhase.GUIDING, route = route, sessionId = 1)
        val source = NavigationMusicJourney { state }
        val first = source.current()!!.key
        state = state.copy(routeRevision = 1)
        assertNotEquals(first, source.current()!!.key)
        val second = source.current()!!.key
        state = state.copy(sessionId = 2, route = route.copy(id = "replacement"))
        assertNotEquals(second, source.current()!!.key)
    }

    @Test fun missingStepTimesFallBackToGeometryDistance() {
        val source = NavigationMusicJourney {
            NavigationState(phase = NavigationPhase.GUIDING, route = route.copy(steps = route.steps.map { it.copy(durationSeconds = null) }))
        }
        val waypoint = source.current()!!.cues.single { it.kind == MusicCueKind.WAYPOINT }
        assertTrue(waypoint.secondsFromNow in 599..601)
    }

    @Test fun adjacentHighwayStepsAreOneContinuousCue() {
        val updated = route.copy(steps = route.steps.map { it.copy(highway = true) })
        val source = NavigationMusicJourney { NavigationState(phase = NavigationPhase.GUIDING, route = updated) }
        val highway = source.current()!!.cues.single { it.kind == MusicCueKind.HIGHWAY }
        assertEquals(0, highway.secondsFromNow)
        assertEquals(900, highway.endSecondsFromNow)
    }
}
