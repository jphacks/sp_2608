package com.theacar.route

import com.theacar.model.Coordinate
import kotlin.test.*

class RouteTimingTest {
    private val points = List(4) { Coordinate(it * 0.01, 0.0) }
    private val route = RoutePlan("route", Destination("end", "End", points.last()), 3300, 900, points,
        listOf(RouteStep("", "", "", 0, 1, 1, 100), RouteStep("", "", "", 1, 2, 8, 500), RouteStep("", "", "", 2, 3, 4, 300)))

    @Test fun usesManeuverTimesRatherThanUniformRoadSpeed() {
        val timing = RouteTiming(route)
        assertEquals(100.0, timing.secondsAtIndex(1), 0.01)
        assertEquals(600.0, timing.secondsAtIndex(2), 0.01)
        assertEquals(800, timing.remainingSecondsAtDistance(timing.distanceAtIndex(1)))
        val midpoint = (timing.distanceAtIndex(1) + timing.distanceAtIndex(2)) / 2
        assertTrue(timing.remainingSecondsAtDistance(midpoint) in 549..550)
    }

    @Test fun missingOrDisconnectedStepTimesUseDistanceFallback() {
        for (steps in listOf(emptyList(), route.steps.map { it.copy(durationSeconds = null) }, route.steps.drop(1))) {
            val timing = RouteTiming(route.copy(steps = steps))
            assertEquals(300.0, timing.secondsAtIndex(1), 0.01)
            assertEquals(600.0, timing.secondsAtIndex(2), 0.01)
        }
    }

    @Test fun normalizesManeuverTotalsToTheRouteSummary() {
        val timing = RouteTiming(route.copy(durationSeconds = 1800))
        assertEquals(200.0, timing.secondsAtIndex(1), 0.01)
        assertEquals(1800.0, timing.secondsAtIndex(3), 0.01)
    }

    @Test fun clampsDistancesAndRejectsNonFiniteInputs() {
        val timing = RouteTiming(route)
        assertEquals(900, timing.remainingSecondsAtDistance(-1.0))
        assertEquals(0, timing.remainingSecondsAtDistance(100_000.0))
        assertFailsWith<IllegalArgumentException> { timing.remainingSecondsAtDistance(Double.NaN) }
    }

    @Test fun cannotTimeARouteWithoutGeometry() {
        assertFailsWith<IllegalArgumentException> { RouteTiming(route.copy(geometry = emptyList(), steps = emptyList())) }
    }
}
