package com.theacar.memory

import com.theacar.model.Coordinate
import com.theacar.location.LocationFix
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.navigation.NavigationProgress
import com.theacar.route.Destination
import com.theacar.route.RoutePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripMemoryDraftTest {
    private val route = RoutePlan("r1", Destination("d1", "八雲パーキング", Coordinate(42.25, 140.26)), 357_600, 15_000,
        listOf(Coordinate(41.77, 140.73), Coordinate(42.25, 140.26)))
    private var now = 1_000_000L
    private val builder = TripMemoryDraftBuilder({ now }, { "Asia/Tokyo" }, { "memory-1" })
    private var fixTime = 0L
    private fun location(coordinate: Coordinate) {
        fixTime += 5_000
        builder.onLocation(LocationFix(coordinate, 5f, fixTime), fixTime)
    }

    @Test fun arrivalProducesMemoryWithMeasuredDuration() {
        assertNull(builder.onState(NavigationState(NavigationPhase.STARTING, route = route, sessionId = 1)))
        assertNull(builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1)))
        now += 16_200_000
        assertNull(builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1)))
        val memory = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 1)))
        assertEquals("memory-1", memory.id)
        assertEquals(1_000_000L, memory.startedAtEpochMillis)
        assertEquals(16_200, memory.durationSeconds)
        assertEquals(357_600, memory.distanceMeters)
        assertEquals("八雲パーキング", memory.destinationTitle)
        assertEquals(route.geometry, memory.geometry)
        assertEquals("Asia/Tokyo", memory.timeZoneId)
        assertNull(memory.placeName)
        // 到着状態が続いても 2 件目は作らない
        assertNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 1)))
    }

    @Test fun stoppingBeforeArrivalDiscardsTheDraft() {
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1))
        builder.onState(NavigationState(NavigationPhase.STOPPING, route = route, sessionId = 1))
        builder.onState(NavigationState(NavigationPhase.IDLE))
        assertNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 1)))
    }

    @Test fun arrivalWithoutGuidanceIsIgnored() {
        assertNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 3)))
    }

    @Test fun rerouteKeepsOriginalStartTime() {
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1))
        now += 600_000
        val rerouted = route.copy(id = "r2", distanceMeters = 547, geometry = listOf(Coordinate(42.24, 140.27), Coordinate(42.25, 140.26)))
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = rerouted, sessionId = 1))
        now += 20_000
        val memory = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = rerouted, sessionId = 1)))
        assertEquals(1_000_000L, memory.startedAtEpochMillis)
        assertEquals(620, memory.durationSeconds)
    }

    @Test fun partialLocationsDoNotReplaceTheWholeRoute() {
        // 案内前の位置は拾わない
        location(Coordinate(41.0, 140.0))
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1))
        val a = Coordinate(41.77, 140.73)
        val near = Coordinate(41.77005, 140.73) // 約 5.6 m
        val b = Coordinate(41.771, 140.73) // 約 111 m
        val c = Coordinate(41.772, 140.73) // さらに約 111 m
        location(a); location(near); location(b); location(c)
        val memory = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 1)))
        assertEquals(route.geometry, memory.geometry)
        assertEquals(357_600, memory.distanceMeters)
    }

    @Test fun withoutLocationsFallsBackToRoute() {
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1))
        location(Coordinate(41.77, 140.73)) // 1 点だけでは軌跡にならない
        val memory = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 1)))
        assertEquals(route.geometry, memory.geometry)
        assertEquals(357_600, memory.distanceMeters)
    }

    @Test fun newSessionResetsTrackAndStartTime() {
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1))
        location(Coordinate(41.77, 140.73)); location(Coordinate(41.771, 140.73))
        now += 60_000
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 2))
        val memory = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 2)))
        assertEquals(1_060_000L, memory.startedAtEpochMillis)
        assertEquals(route.geometry, memory.geometry)
    }

    @Test fun longRouteIsThinnedKeepingBothEnds() {
        val points = List(20_001) { Coordinate(41.0 + it * 1e-5, 140.0) }
        val longRoute = route.copy(geometry = points)
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = longRoute, sessionId = 1))
        val memory = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = longRoute, sessionId = 1)))
        assertTrue(memory.geometry.size <= GEOMETRY_POINT_LIMIT, "size=${memory.geometry.size}")
        assertEquals(points.first(), memory.geometry.first())
        assertEquals(points.last(), memory.geometry.last())
        // 上限以下はそのまま
        assertEquals(points.take(20_000), thinGeometry(points.take(20_000)))
    }

    @Test fun progressIsAccumulatedAcrossReroutesWithoutCountingBacktrackingTwice() {
        fun guiding(plan: RoutePlan, remaining: Int) = NavigationState(NavigationPhase.GUIDING,
            route = plan, sessionId = 1, progress = NavigationProgress(remaining, 100))
        builder.onState(guiding(route, route.distanceMeters))
        builder.onState(guiding(route, route.distanceMeters - 100_000))
        builder.onState(guiding(route, route.distanceMeters - 99_950))
        val replacement = route.copy(id = "r2", distanceMeters = 50_000)
        builder.onState(guiding(replacement, 49_900))
        val saved = assertNotNull(builder.onState(guiding(replacement, 0).copy(phase = NavigationPhase.ARRIVED)))
        assertEquals(150_000, saved.distanceMeters)
    }

    @Test fun completeAccurateTrackIsUsedForGeometry() {
        val a = Coordinate(41.77, 140.73)
        val b = Coordinate(41.771, 140.73)
        val c = Coordinate(41.772, 140.73)
        val short = route.copy(distanceMeters = 222, geometry = listOf(a, c))
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = short, sessionId = 1))
        location(a); location(b); location(c)
        val saved = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = short, sessionId = 1)))
        assertEquals(listOf(a, b, c), saved.geometry)
        assertEquals(222, saved.distanceMeters)
    }

    @Test fun inaccurateOrJumpingFixesDoNotReplaceThePlannedRoute() {
        builder.onState(NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1))
        builder.onLocation(LocationFix(Coordinate(41.77, 140.73), 500f, 0), 0)
        location(Coordinate(41.77, 140.73))
        location(Coordinate(45.0, 145.0))
        val saved = assertNotNull(builder.onState(NavigationState(NavigationPhase.ARRIVED, route = route, sessionId = 1)))
        assertEquals(route.distanceMeters, saved.distanceMeters)
        assertEquals(route.geometry, saved.geometry)
    }
}
