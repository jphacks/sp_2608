package com.theacar.navigation

import com.theacar.model.Coordinate
import com.theacar.route.Destination
import com.theacar.route.DestinationSearch
import com.theacar.route.RouteOptions
import com.theacar.route.RoutePlan
import com.theacar.route.RouteAlternative
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NavigationSessionTest {
    @Test fun canStartWhileImprovingAndLateUpdatesCannotReplaceGuidance() = runTest {
        var cancelled = false
        val engine = FakeEngine(plan)
        engine.progressive = { update ->
            engine.alternatives = listOf(RouteAlternative(plan))
            update(plan, engine.alternatives)
            try { delay(10_000) } finally { cancelled = true }
            plan
        }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        assertEquals(NavigationPhase.PREVIEW, session.state.value.phase)
        assertTrue(session.state.value.previewImproving)
        session.start(); runCurrent()
        assertTrue(cancelled)
        assertFalse(session.state.value.previewImproving)
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        assertEquals(plan, session.state.value.route)
        session.close()
    }

    @Test fun backgroundFailureRetainsUsableProvisionalPreview() = runTest {
        val engine = FakeEngine(plan)
        engine.progressive = { update ->
            engine.alternatives = listOf(RouteAlternative(plan))
            update(plan, engine.alternatives)
            error("background failed")
        }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        assertEquals(NavigationPhase.PREVIEW, session.state.value.phase)
        assertEquals(plan, session.state.value.route)
        assertFalse(session.state.value.previewImproving)
        assertEquals(NavigationFailure.NETWORK, session.state.value.failure)
        session.start(); runCurrent()
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        session.close()
    }

    @Test fun invalidationDiscardsLateProgressiveCallback() = runTest {
        val engine = FakeEngine(plan)
        engine.progressive = { update ->
            withContext(NonCancellable) { delay(100); update(plan, listOf(RouteAlternative(plan))) }
            plan
        }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.invalidatePreview()
        advanceTimeBy(101); runCurrent()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        assertNull(session.state.value.route)
        assertFalse(session.state.value.previewImproving)
        session.close()
    }

    private val destination = Destination("tokyo", "東京駅", Coordinate(35.681, 139.767))
    private val plan = RoutePlan("route-1", destination, 10_000, 1_200, emptyList())

    @Test fun searchReturnsDestinationsAndEmptyQueryClearsResults() = runTest {
        val session = NavigationSession(Search { listOf(destination) }, FakeEngine(plan), backgroundScope)
        session.search(" 東京駅 ")
        runCurrent()
        assertEquals("東京駅", session.state.value.query)
        assertEquals(listOf(destination), session.state.value.destinations)
        assertFalse(session.state.value.searching)
        session.search("")
        assertTrue(session.state.value.destinations.isEmpty())
        session.close()
    }

    @Test fun alternativeSelectionChangesOnlyPreviewAndStopClearsCandidates() = runTest {
        val second = plan.copy(id = "second")
        val engine = FakeEngine(plan).apply {
            alternatives = listOf(RouteAlternative(plan), RouteAlternative(second))
        }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        assertEquals(2, session.state.value.alternatives.size)
        session.selectAlternative(second.id)
        assertEquals(second, session.state.value.route)
        session.selectAlternative("unknown")
        assertEquals(second, session.state.value.route)
        session.start(); runCurrent()
        session.selectAlternative(plan.id)
        assertEquals(second, session.state.value.route)
        session.stop(); runCurrent()
        assertTrue(session.state.value.alternatives.isEmpty())
        session.close()
    }

    @Test fun changedConditionsInvalidateRouteAndPreventStartingOldPreview() = runTest {
        val engine = FakeEngine(plan)
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        val options = RouteOptions(avoidTolls = true)
        session.preview(destination, options); runCurrent()
        assertEquals(options, session.state.value.previewOptions)
        assertEquals(destination, session.state.value.previewDestination)
        session.invalidatePreview()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        assertNull(session.state.value.route)
        assertTrue(session.state.value.alternatives.isEmpty())
        assertTrue(session.state.value.previewNeedsRefresh)
        session.start(); runCurrent()
        assertEquals(0, engine.startCount)
        session.preview(destination); runCurrent()
        assertFalse(session.state.value.previewNeedsRefresh)
        session.start(); runCurrent()
        session.invalidatePreview()
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        session.close()
    }

    @Test fun invalidationDiscardsLatePreviewResult() = runTest {
        val engine = FakeEngine(plan).apply { delayedPreview = true }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.invalidatePreview()
        advanceTimeBy(101); runCurrent()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        assertNull(session.state.value.route)
        assertTrue(session.state.value.previewNeedsRefresh)
        session.close()
    }

    @Test fun obsoleteSearchCannotReplaceNewResults() = runTest {
        val session = NavigationSession(Search { query ->
            if (query == "old") withContext(NonCancellable) { delay(100); emptyList() } else listOf(destination)
        }, FakeEngine(plan), backgroundScope)
        session.search("old"); runCurrent()
        session.search("new"); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(destination), session.state.value.destinations)
        assertNull(session.state.value.failure)
        session.close()
    }

    @Test fun emptyAndFailedSearchHaveDifferentFailures() = runTest {
        var fail = false
        val session = NavigationSession(Search { if (fail) error("offline") else emptyList() }, FakeEngine(plan), backgroundScope)
        session.search("nothing"); runCurrent()
        assertEquals(NavigationFailure.NO_RESULTS, session.state.value.failure)
        fail = true
        session.search("failed"); runCurrent()
        assertEquals(NavigationFailure.SEARCH, session.state.value.failure)
        session.close()
    }

    @Test fun previewStartProgressRerouteArrivalAndStop() = runTest {
        val engine = FakeEngine(plan)
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination, RouteOptions(avoidTolls = true)); runCurrent()
        assertEquals(NavigationPhase.PREVIEW, session.state.value.phase)
        assertTrue(engine.options!!.avoidTolls)
        session.start(); runCurrent()
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        val id = session.state.value.sessionId
        engine.state.value = EngineState(id, EnginePhase.GUIDING, NavigationProgress(8_000, 900), rerouting = true)
        runCurrent()
        assertEquals(8_000, session.state.value.progress?.remainingMeters)
        assertTrue(session.state.value.rerouting)
        engine.state.value = EngineState(id, EnginePhase.GUIDING, routeRevision = 1)
        runCurrent()
        assertFalse(session.state.value.rerouting)
        assertEquals(1, session.state.value.routeRevision)
        engine.state.value = EngineState(id, EnginePhase.ARRIVED)
        runCurrent()
        assertEquals(NavigationPhase.ARRIVED, session.state.value.phase)
        session.stop(); runCurrent()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        assertNull(session.state.value.route)
        session.close()
    }

    @Test fun startWaitsForEngineAndCannotBeDuplicated() = runTest {
        val engine = FakeEngine(plan).apply { startGate = CompletableDeferred() }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.start(); session.start(); runCurrent()
        assertEquals(NavigationPhase.STARTING, session.state.value.phase)
        assertEquals(1, engine.startCount)
        engine.startGate!!.complete(Unit); runCurrent()
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        session.close()
    }

    @Test fun failedStartRetainsPreviewAndAllowsRetry() = runTest {
        val engine = FakeEngine(plan).apply { failStart = true }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.start(); runCurrent()
        assertEquals(NavigationPhase.PREVIEW, session.state.value.phase)
        assertEquals(NavigationFailure.START, session.state.value.failure)
        engine.failStart = false
        session.start(); runCurrent()
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        assertNull(session.state.value.failure)
        session.close()
    }

    @Test fun failedStopDoesNotPretendGuidanceEnded() = runTest {
        val engine = FakeEngine(plan)
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.start(); runCurrent()
        engine.failStop = true
        session.stop(); runCurrent()
        assertEquals(NavigationPhase.GUIDING, session.state.value.phase)
        assertEquals(NavigationFailure.STOP, session.state.value.failure)
        engine.failStop = false
        session.stop(); runCurrent()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        session.close()
    }

    @Test fun stoppedSessionIgnoresLateProgressAndArrival() = runTest {
        val engine = FakeEngine(plan)
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.start(); runCurrent()
        val oldId = session.state.value.sessionId
        session.stop(); runCurrent()
        engine.state.value = EngineState(oldId, EnginePhase.ARRIVED)
        runCurrent()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        session.preview(destination); runCurrent()
        session.start(); runCurrent()
        engine.state.value = EngineState(oldId, EnginePhase.GUIDING, NavigationProgress(1, 1))
        runCurrent()
        assertNull(session.state.value.progress)
        session.close()
    }

    @Test fun guidingRouteCannotBeReplacedByPreview() = runTest {
        val engine = FakeEngine(plan)
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.start(); runCurrent()
        session.preview(destination.copy(id = "other")); runCurrent()
        assertEquals(1, engine.previewCount)
        assertEquals(plan, session.state.value.route)
        session.close()
    }

    @Test fun stopDuringPreviewRejectsLateResult() = runTest {
        val engine = FakeEngine(plan).apply { delayedPreview = true }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.stop(); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(NavigationPhase.IDLE, session.state.value.phase)
        assertNull(session.state.value.route)
        session.close()
    }

    @Test fun searchTimeoutCanRecover() = runTest {
        var slow = true
        val session = NavigationSession(Search { if (slow) delay(1_000); listOf(destination) }, FakeEngine(plan), backgroundScope, 100)
        session.search("first"); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(NavigationFailure.TIMEOUT, session.state.value.failure)
        assertFalse(session.state.value.searching)
        slow = false
        session.search("second"); runCurrent()
        assertEquals(listOf(destination), session.state.value.destinations)
        assertNull(session.state.value.failure)
        session.close()
    }

    @Test fun closeRejectsLateResultsAndNewCommands() = runTest {
        val engine = FakeEngine(plan).apply { delayedPreview = true }
        val session = NavigationSession(Search { emptyList() }, engine, backgroundScope)
        session.preview(destination); runCurrent()
        session.close()
        val state = session.state.value
        session.preview(destination); session.start(); session.stop(); session.search("ignored")
        advanceTimeBy(101); runCurrent()
        assertEquals(state, session.state.value)
        assertEquals(1, engine.previewCount)
    }

    private class Search(val action: suspend (String) -> List<Destination>) : DestinationSearch {
        override suspend fun search(query: String) = action(query)
    }

    private class FakeEngine(val plan: RoutePlan) : NavigationEngine {
        var progressive: (suspend (suspend (RoutePlan, List<RouteAlternative>) -> Unit) -> RoutePlan)? = null
        override suspend fun preview(destination: Destination, options: RouteOptions,
            onUpdate: suspend (RoutePlan, List<RouteAlternative>) -> Unit): RoutePlan =
            progressive?.invoke(onUpdate) ?: preview(destination, options)
        override val state = MutableStateFlow(EngineState())
        override var alternatives = emptyList<RouteAlternative>()
        override fun selectPreview(id: String) = alternatives.firstOrNull { it.plan.id == id }?.plan
            ?: throw NavigationException(NavigationFailure.NO_ROUTE)
        var failStart = false
        var failStop = false
        var delayedPreview = false
        var startGate: CompletableDeferred<Unit>? = null
        var startCount = 0
        var previewCount = 0
        var options: RouteOptions? = null
        override suspend fun preview(destination: Destination, options: RouteOptions): RoutePlan {
            previewCount++
            this.options = options
            if (delayedPreview) withContext(NonCancellable) { delay(100) }
            return plan
        }
        override suspend fun start(plan: RoutePlan, sessionId: Long) {
            startCount++
            if (failStart) error("start")
            startGate?.await()
            state.value = EngineState(sessionId, EnginePhase.GUIDING)
        }
        override suspend fun stop() {
            if (failStop) error("stop")
            state.value = EngineState()
        }
    }
}
