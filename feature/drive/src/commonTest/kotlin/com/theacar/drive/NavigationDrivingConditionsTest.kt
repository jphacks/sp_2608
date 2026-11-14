package com.theacar.drive

import com.theacar.model.Coordinate
import com.theacar.navigation.*
import com.theacar.route.*
import kotlin.math.*
import kotlin.test.*

class NavigationDrivingConditionsTest {
    // 緯度1度 ≒ 111,195m（景観エンジンと同じ近似）。赤道付近なので経度も同じ。
    private fun meters(north: Double, east: Double) = Coordinate(north / 111_195, east / 111_195)

    /** 東へ [length] m の直線。 */
    private fun straight(length: Double) = (0..(length / 10).toInt()).map { meters(0.0, it * 10.0) }

    /** 半径 [radius] m の円弧を、向きを交互に変えながら続けた曲がりくねった道（約 [length] m）。 */
    private fun winding(radius: Double, length: Double): List<Coordinate> {
        val points = mutableListOf(meters(0.0, 0.0))
        var x = 0.0; var y = 0.0; var heading = 0.0
        val step = 5.0
        var side = 1
        var bent = 0.0
        repeat((length / step).toInt()) {
            heading += side * step / radius
            bent += step / radius
            if (bent >= PI / 2) { side = -side; bent = 0.0 }
            x += step * cos(heading); y += step * sin(heading)
            points += meters(y, x)
        }
        return points
    }

    private fun guiding(points: List<Coordinate>, remaining: Int, steps: List<RouteStep> = emptyList()): NavigationState {
        val total = RouteTiming(RoutePlan("r", Destination("end", "End", points.last()), 1, 1, points)).totalDistanceMeters.toInt()
        val route = RoutePlan("r", Destination("end", "End", points.last()), total, 600, points, steps)
        return NavigationState(phase = NavigationPhase.GUIDING, route = route, sessionId = 1,
            progress = NavigationProgress(remaining, 300))
    }

    @Test fun curvatureMatchesTheRadiusOfACircularArc() {
        val arc = (0..60).map { meters(100 * sin(it * 0.05), 100 * (1 - cos(it * 0.05))) }
        val values = curvatureProfile(arc).filterNotNull()
        assertTrue(values.isNotEmpty())
        values.drop(1).dropLast(1).forEach { assertEquals(0.01, it, 0.001) }
        assertTrue(curvatureProfile(straight(500.0)).filterNotNull().all { it < 1e-6 })
        assertTrue(curvatureProfile(straight(50.0)).all { it == null })
    }

    @Test fun windingRoadAheadIsCurvyAndAStraightIsNot() {
        val road = winding(radius = 40.0, length = 3_000.0)
        val state = guiding(road, remaining = 3_000)
        assertTrue(NavigationDrivingConditions { state }.current().curvy)
        val flat = guiding(straight(3_000.0), remaining = 3_000)
        assertFalse(NavigationDrivingConditions { flat }.current().curvy)
    }

    @Test fun onlyTheStretchAheadCounts() {
        // 前半はくねくね、後半は直線。後半に入ったら curvy ではない。
        val road = winding(40.0, 3_000.0)
        val end = road.last()
        val tail = (1..400).map { Coordinate(end.latitude, end.longitude + it * 10.0 / 111_195) }
        val points = road + tail
        var remaining = 7_000
        val source = NavigationDrivingConditions { guiding(points, remaining) }
        assertTrue(source.current().curvy)
        remaining = 3_500
        assertFalse(source.current().curvy)
    }

    @Test fun turnsAtIntersectionsAreNotCurves() {
        // 200m ごとに直角に曲がる道。曲がり角は案内の曲がり角なので数えない。
        val points = mutableListOf(meters(0.0, 0.0))
        val steps = mutableListOf<RouteStep>()
        var x = 0.0; var y = 0.0
        repeat(15) { leg ->
            val begin = points.lastIndex
            repeat(20) { if (leg % 2 == 0) x += 10 else y += 10; points += meters(y, x) }
            steps += RouteStep("", "", "", begin, points.lastIndex, 0)
        }
        val withSteps = guiding(points, 3_000, steps)
        assertFalse(NavigationDrivingConditions { withSteps }.current().curvy)
    }

    @Test fun nothingOutsideGuidance() {
        val state = guiding(winding(40.0, 3_000.0), 3_000)
        assertFalse(NavigationDrivingConditions { state.copy(phase = NavigationPhase.PREVIEW) }.current().curvy)
        assertFalse(NavigationDrivingConditions { state.copy(rerouting = true) }.current().curvy)
        assertFalse(NavigationDrivingConditions { state.copy(progress = null) }.current().curvy)
    }
}
