package com.theacar.music

import kotlin.test.Test
import kotlin.test.assertEquals

class CabinMoodTrackerTest {
    private fun CabinMoodTracker.run(fromSeconds: Long, toSeconds: Long, speaking: (Long) -> Boolean) {
        for (second in fromSeconds until toSeconds) observe(second * 1_000, speaking(second))
    }

    @Test
    fun normalBeforeAnyObservation() {
        val tracker = CabinMoodTracker()
        assertEquals(CabinState.NORMAL, tracker.state(0))
        tracker.start(0)
        assertEquals(CabinState.NORMAL, tracker.state(60_000))
    }

    @Test
    fun livelyWhenMostSecondsHaveSpeech() {
        val tracker = CabinMoodTracker()
        tracker.start(0)
        tracker.run(0, 60) { it % 2 == 0L }
        assertEquals(CabinState.LIVELY, tracker.state(59_000))
    }

    @Test
    fun occasionalSpeechIsNormal() {
        val tracker = CabinMoodTracker()
        tracker.start(0)
        tracker.run(0, 60) { it % 10 == 0L }
        assertEquals(CabinState.NORMAL, tracker.state(59_000))
    }

    @Test
    fun livelyNeedsEnoughObservations() {
        val tracker = CabinMoodTracker()
        tracker.start(0)
        tracker.run(0, 10) { true }
        assertEquals(CabinState.NORMAL, tracker.state(9_000))
    }

    @Test
    fun quietAfterThreeSilentMinutes() {
        val tracker = CabinMoodTracker()
        tracker.start(0)
        tracker.run(0, 179) { false }
        assertEquals(CabinState.NORMAL, tracker.state(178_000))
        tracker.run(179, 181) { false }
        assertEquals(CabinState.QUIET, tracker.state(180_000))
    }

    @Test
    fun restingOnlyOnLongDrives() {
        val short = CabinMoodTracker()
        short.start(0)
        short.run(0, 400) { false }
        assertEquals(CabinState.QUIET, short.state(399_000))

        val long = CabinMoodTracker(longDriveMillis = 300_000)
        long.start(0)
        long.run(0, 400) { false }
        assertEquals(CabinState.RESTING, long.state(399_000))
    }

    @Test
    fun speechEndsQuiet() {
        val tracker = CabinMoodTracker()
        tracker.start(0)
        tracker.run(0, 200) { false }
        assertEquals(CabinState.QUIET, tracker.state(199_000))
        tracker.observe(200_000, true)
        assertEquals(CabinState.NORMAL, tracker.state(200_000))
    }

    @Test
    fun staleObservationsFallBackToNormal() {
        val tracker = CabinMoodTracker()
        tracker.start(0)
        tracker.run(0, 200) { false }
        assertEquals(CabinState.NORMAL, tracker.state(220_000))
    }
}
