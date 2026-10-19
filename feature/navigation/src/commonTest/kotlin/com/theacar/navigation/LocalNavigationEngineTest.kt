package com.theacar.navigation

import com.theacar.location.*
import com.theacar.model.Coordinate
import com.theacar.route.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalNavigationEngineTest {
    @Test fun progressivePreviewKeepsSelectedRouteEvenWhenMissingFromFinalPortfolio() = runTest {
        val positions = Positions().apply { emit(origin, 1_000) }
        val first = route().copy(id = "first")
        val selected = route().copy(id = "selected")
        val improved = route().copy(id = "improved")
        val proceed = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = LocalNavigationEngine(object : RouteAlternativesCalculator {
            override suspend fun calculate(query: RouteQuery) = error("ordinary")
            override suspend fun alternatives(query: RouteQuery) = error("non-progressive")
            override suspend fun alternatives(query: RouteQuery, onUpdate: suspend (List<RouteAlternative>) -> Unit): List<RouteAlternative> {
                onUpdate(listOf(RouteAlternative(first), RouteAlternative(selected)))
                proceed.await()
                onUpdate(listOf(RouteAlternative(improved)))
                finish.await()
                return listOf(RouteAlternative(improved))
            }
        }, positions, backgroundScope, { 1_000 })
        val updates = mutableListOf<String>()
        val pending = async { engine.preview(destination, RouteOptions(scenic = ScenicOptions())) { plan, _ -> updates += plan.id } }
        runCurrent()
        assertEquals(selected, engine.selectPreview(selected.id))
        proceed.complete(Unit); runCurrent()
        assertEquals(listOf(first.id, selected.id), updates)
        assertEquals(listOf(improved.id, selected.id), engine.alternatives.map { it.plan.id })
        finish.complete(Unit)
        assertEquals(selected, pending.await())
        engine.start(selected, 1)
        assertEquals(selected.id, engine.state.value.route?.id)
        engine.stop()
    }

    private val origin = Coordinate(35.0, 139.0)
    private val middle = Coordinate(35.001, 139.0)
    private val end = Coordinate(35.002, 139.0)
    private val destination = Destination("end", "到着地点", end)

    private fun route(points: List<Coordinate> = listOf(origin, middle, end), target: Destination = destination) = RoutePlan(
        "route", target, 222, 100, points,
        listOf(RouteStep("左折", "左に曲がります", "道路", 1, 2, 15)),
        listOf(RouteWaypoint(middle, 1)),
    )

    private class Positions : LocationSource {
        override val location = MutableStateFlow(LocationState())
        fun emit(point: Coordinate, time: Long, accuracy: Float = 5f) {
            location.value = LocationState(LocationStatus.AVAILABLE, LocationFix(point, accuracy, time))
        }
    }

    @Test fun startsFromWherePassengerAlreadyIsWhenMovingAlongRoute() = runTest {
        // 約4.4km の直線。検索してから約2.8km 進んだところで案内を始める（追跡の先読み1.5kmより先）
        val points = (0..40).map { Coordinate(35.0 + it * 0.001, 139.0) }
        val positions = Positions().apply { emit(points.first(), 1_000) }
        var time = 1_000L
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery) = route(points, Destination("end", "到着地点", points.last()))
        }, positions, backgroundScope, { time })
        val plan = engine.preview(destination, RouteOptions())
        positions.emit(Coordinate(35.0253, 139.0002), ++time)
        engine.start(plan, 1)
        runCurrent()
        assertEquals(EnginePhase.GUIDING, engine.state.value.phase)
        val remaining = assertNotNull(engine.state.value.progress).remainingMeters
        assertTrue(remaining in 1_500..1_800, "remaining=$remaining")
        engine.stop()
    }

    @Test fun refusesToStartAwayFromRoute() = runTest {
        val positions = Positions().apply { emit(origin, 1_000) }
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery) = route()
        }, positions, backgroundScope, { 1_000 })
        val plan = engine.preview(destination, RouteOptions())
        positions.emit(Coordinate(35.001, 139.01), 1_000)
        assertEquals(NavigationFailure.AWAY_FROM_ROUTE, assertFailsWith<NavigationException> { engine.start(plan, 1) }.failure)
    }

    @Test fun budgetResetsOnlyAfterWindow() {
        var time = 0L
        val budget = RoutingBudget({ time }, 2, 1_000)
        budget.consume()
        budget.consume()
        assertEquals(NavigationFailure.LIMIT_REACHED, assertFailsWith<NavigationException> { budget.consume() }.failure)
        time = 999
        assertFailsWith<NavigationException> { budget.consume() }
        time = 1_000
        budget.consume()
    }

    @Test fun selectedScenicPreviewCanStartWithoutAnotherRequest() = runTest {
        val positions = Positions().apply { emit(origin, 1_000) }
        val first = route().copy(id = "first")
        val second = route().copy(id = "second", durationSeconds = 120)
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteAlternativesCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan = error("Unexpected ordinary route")
            override suspend fun alternatives(query: RouteQuery): List<RouteAlternative> {
                calls++
                return listOf(RouteAlternative(first), RouteAlternative(second))
            }
        }, positions, backgroundScope, { 1_000 })
        assertEquals(first, engine.preview(destination, RouteOptions(scenic = ScenicOptions())))
        assertEquals(second, engine.selectPreview("second"))
        engine.start(second, 1)
        assertEquals("second", engine.state.value.route?.id)
        assertEquals(1, calls)
        assertFailsWith<IllegalStateException> { engine.selectPreview("first") }
        engine.stop()
        assertTrue(engine.alternatives.isEmpty())
        assertFailsWith<NavigationException> { engine.selectPreview("second") }
    }

    @Test fun expiredScenicPreviewNeedsExplicitRefresh() = runTest {
        var now = 1_000L
        val positions = Positions().apply { emit(origin, now) }
        val engine = LocalNavigationEngine(object : RouteAlternativesCalculator {
            override suspend fun calculate(query: RouteQuery) = route()
            override suspend fun alternatives(query: RouteQuery) = listOf(RouteAlternative(route()))
        }, positions, backgroundScope, { now })
        val preview = engine.preview(destination, RouteOptions(scenic = ScenicOptions()))
        now += 121_000
        positions.emit(origin, now)
        assertEquals(NavigationFailure.PREVIEW_EXPIRED, assertFailsWith<NavigationException> { engine.start(preview, 1) }.failure)
        val refreshed = engine.preview(destination, RouteOptions(scenic = ScenicOptions()))
        engine.start(refreshed, 2)
        assertEquals(EnginePhase.GUIDING, engine.state.value.phase)
        engine.stop()
    }

    @Test fun stoppedPreviewCannotBeStartedByLateResponse() = runTest {
        val positions = Positions().apply { emit(origin, 1_000) }
        val pending = CompletableDeferred<RoutePlan>()
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery) = pending.await()
        }, positions, backgroundScope, { 1_000 })
        val preview = async { engine.preview(destination, RouteOptions()) }
        runCurrent()
        engine.stop()
        pending.complete(route())
        assertFailsWith<CancellationException> { preview.await() }
        assertEquals(EnginePhase.STOPPED, engine.state.value.phase)
        assertFailsWith<NavigationException> { engine.start(route(), 1) }
    }

    @Test fun trackerDoesNotAdvanceOnOffRouteFix() {
        val tracker = RouteTracker(route())
        val first = tracker.locate(origin)
        val noisy = tracker.locate(Coordinate(35.002, 139.01))
        assertEquals(first.traveledMeters, noisy.traveledMeters)
        assertTrue(noisy.distanceFromRouteMeters > 60)
        val next = tracker.locate(middle)
        assertTrue(next.traveledMeters in 100.0..120.0)
        assertEquals("左に曲がります", tracker.maneuver(next)?.voiceInstruction)
    }

    @Test fun rejectsExpiredAndVeryImprecisePreviewLocations() = runTest {
        val positions = Positions()
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan { calls++; return route() }
        }, positions, backgroundScope, { 140_000 })
        positions.emit(origin, 1_000)
        assertEquals(NavigationFailure.LOCATION, assertFailsWith<NavigationException> { engine.preview(destination, RouteOptions()) }.failure)
        positions.emit(origin, 140_000, 1_000f)
        assertFailsWith<NavigationException> { engine.preview(destination, RouteOptions()) }
        assertEquals(0, calls)
    }

    @Test fun previewAcceptsRecentApproximatePositionButGuidanceRejectsIt() = runTest {
        val positions = Positions().apply { emit(origin, 1_000, 100f) }
        positions.location.value = positions.location.value.copy(status = LocationStatus.STALE)
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan {
                calls++
                assertEquals(origin, query.origin)
                return route()
            }
        }, positions, backgroundScope, { 20_000 })
        val plan = engine.preview(destination, RouteOptions())
        assertEquals(1, calls)
        assertEquals(NavigationFailure.LOCATION, assertFailsWith<NavigationException> { engine.start(plan, 1) }.failure)
        positions.emit(origin, 20_000)
        engine.start(plan, 1)
        assertEquals(EnginePhase.GUIDING, engine.state.value.phase)
    }

    @Test fun startsWithApproximatePositionButHoldsInstructionsUntilPrecise() = runTest {
        val positions = Positions().apply { emit(origin, 1_000, 120f) }
        var time = 1_000L
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery) = route()
        }, positions, backgroundScope, { time })
        val plan = engine.preview(destination, RouteOptions())
        assertEquals(NavigationFailure.LOCATION, assertFailsWith<NavigationException> { engine.start(plan, 1) }.failure)
        positions.emit(origin, ++time, 90f)
        engine.start(plan, 1)
        runCurrent()
        assertEquals(EnginePhase.GUIDING, engine.state.value.phase)
        assertNull(engine.state.value.maneuver)
        positions.emit(origin, ++time, 10f)
        runCurrent()
        assertNotNull(engine.state.value.maneuver)
    }

    @Test fun previewWaitsForFirstUsablePosition() = runTest {
        val positions = Positions()
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan { calls++; return route() }
        }, positions, backgroundScope, { 20_000 })
        val pending = async { engine.preview(destination, RouteOptions()) }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(0, calls)
        positions.emit(origin, 20_000, 100f)
        assertEquals("route", pending.await().id)
        assertEquals(1, calls)
    }

    @Test fun previewStopsWaitingWhenPermissionIsRevoked() = runTest {
        val positions = Positions()
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan { calls++; return route() }
        }, positions, backgroundScope, { 20_000 })
        val pending = async { runCatching { engine.preview(destination, RouteOptions()) } }
        runCurrent()
        positions.location.value = LocationState(LocationStatus.PERMISSION_REQUIRED)
        assertEquals(NavigationFailure.PERMISSION, (pending.await().exceptionOrNull() as NavigationException).failure)
        assertEquals(0, calls)
    }

    @Test fun disabledLocationIsNotUsedEvenWithRecentFix() = runTest {
        val positions = Positions().apply { emit(origin, 1_000) }
        positions.location.value = positions.location.value.copy(status = LocationStatus.DISABLED)
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan { calls++; return route() }
        }, positions, backgroundScope, { 1_000 })
        assertEquals(NavigationFailure.LOCATION, assertFailsWith<NavigationException> { engine.preview(destination, RouteOptions()) }.failure)
        assertEquals(0, calls)
    }

    @Test fun stoppedPreviewDoesNotRequestRouteAfterLocationArrives() = runTest {
        val positions = Positions()
        var calls = 0
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan { calls++; return route() }
        }, positions, backgroundScope, { 20_000 })
        val pending = async { engine.preview(destination, RouteOptions()) }
        runCurrent()
        engine.stop()
        positions.emit(origin, 20_000)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, calls)
    }

    @Test fun requiresRepeatedArrivalFixesAndSuppressesBadGpsInstructions() = runTest {
        val positions = Positions()
        var time = 1_000L
        positions.emit(origin, time)
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery) = route()
        }, positions, backgroundScope, { time })
        engine.start(engine.preview(destination, RouteOptions()), 1)
        runCurrent()
        assertNotNull(engine.state.value.maneuver)
        positions.emit(origin, ++time, 100f)
        runCurrent()
        assertNull(engine.state.value.maneuver)
        assertEquals(NavigationFailure.LOCATION, engine.state.value.failure)
        repeat(2) { positions.emit(end, ++time); runCurrent() }
        assertEquals(EnginePhase.GUIDING, engine.state.value.phase)
        positions.emit(end, ++time)
        runCurrent()
        assertEquals(EnginePhase.ARRIVED, engine.state.value.phase)
        engine.stop()
        assertNull(engine.state.value.route)
    }

    @Test fun loopDoesNotArriveAtItsStartingPoint() = runTest {
        val positions = Positions()
        var time = 1_000L
        val target = Destination("loop", "出発地点", origin)
        val loop = route(listOf(origin, middle, Coordinate(35.001, 139.001), origin), target)
        positions.emit(origin, time)
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery) = loop
        }, positions, backgroundScope, { time })
        engine.start(engine.preview(target, RouteOptions()), 1)
        runCurrent()
        repeat(4) { positions.emit(origin, ++time); runCurrent() }
        assertEquals(EnginePhase.GUIDING, engine.state.value.phase)
        assertTrue(requireNotNull(engine.state.value.progress).remainingMeters > 100)
    }

    @Test fun rerouteKeepsUnvisitedWaypointsAndThrottlesFailures() = runTest {
        val positions = Positions()
        val requests = mutableListOf<RouteQuery>()
        var time = 1_000L
        positions.emit(origin, time)
        val engine = LocalNavigationEngine(object : RouteCalculator {
            override suspend fun calculate(query: RouteQuery): RoutePlan {
                requests += query
                if (requests.size > 1) throw RoutingException(RoutingFailure.NETWORK)
                return route()
            }
        }, positions, backgroundScope, { time })
        engine.start(engine.preview(destination, RouteOptions(viaPoints = listOf(middle))), 1)
        runCurrent()
        val offRoute = Coordinate(35.002, 139.01)
        repeat(3) { time += 3_000; positions.emit(offRoute, time); runCurrent() }
        assertEquals(2, requests.size)
        assertEquals(listOf(middle), requests.last().options.viaPoints)
        assertEquals(NavigationFailure.NETWORK, engine.state.value.failure)
        assertNotNull(engine.state.value.route)
        repeat(3) { time += 3_000; positions.emit(offRoute, time); runCurrent() }
        assertEquals(2, requests.size)
        engine.stop()
        time += 30_000
        positions.emit(offRoute, time)
        runCurrent()
        assertEquals(2, requests.size)
    }
}
