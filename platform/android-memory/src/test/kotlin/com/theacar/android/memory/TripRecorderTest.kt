package com.theacar.android.memory

import com.theacar.location.LocationSource
import com.theacar.location.LocationState
import com.theacar.memory.TripMemory
import com.theacar.model.Coordinate
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.Destination
import com.theacar.route.RoutePlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TripRecorderTest {
    private val route = RoutePlan("route", Destination("d", "函館", Coordinate(41.77, 140.73)), 100, 60,
        listOf(Coordinate(41.78, 140.73), Coordinate(41.77, 140.73)))
    private val locations = object : LocationSource { override val location = MutableStateFlow(LocationState()) }

    @Test fun failedSaveCanBeRetriedWithoutCreatingAnotherRecord() = runTest {
        val states = MutableStateFlow(NavigationState())
        val attempts = mutableListOf<TripMemory>()
        var fail = true
        val recorder = TripRecorder(states, locations, { memory ->
            attempts += memory
            if (fail) throw java.io.IOException("disk full")
        }, backgroundScope, { 0L })
        states.value = NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1)
        testScheduler.runCurrent()
        states.value = states.value.copy(phase = NavigationPhase.ARRIVED)
        testScheduler.runCurrent()
        assertTrue(recorder.saveFailed.value)
        assertNull(recorder.lastSavedId.value)
        fail = false
        recorder.retry()
        testScheduler.runCurrent()
        assertFalse(recorder.saveFailed.value)
        assertEquals(attempts.first().id, recorder.lastSavedId.value)
        assertEquals(attempts.first(), attempts.last())
        recorder.retry()
        testScheduler.runCurrent()
        assertEquals(2, attempts.size)
    }

    @Test fun previousTripFinishingItsSaveDoesNotAppearAsTheNewTrip() = runTest {
        val states = MutableStateFlow(NavigationState())
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<TripMemory>()
        val recorder = TripRecorder(states, locations, { memory -> gate.await(); saved += memory }, backgroundScope, { 0L })
        states.value = NavigationState(NavigationPhase.GUIDING, route = route, sessionId = 1)
        testScheduler.runCurrent()
        states.value = states.value.copy(phase = NavigationPhase.ARRIVED)
        testScheduler.runCurrent()
        states.value = states.value.copy(phase = NavigationPhase.GUIDING, sessionId = 2)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, saved.size)
        assertNull(recorder.lastSavedId.value)
        states.value = states.value.copy(phase = NavigationPhase.ARRIVED)
        testScheduler.runCurrent()
        assertEquals(2, saved.size)
        assertEquals(saved.last().id, recorder.lastSavedId.value)
    }
}
