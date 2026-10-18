package com.theacar.android.music

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMusicVolumeTest {
    private class FakeStream(override var current: Int) : StreamVolume {
        val calls = mutableListOf<Int>()
        var acceptsChanges = true
        override fun set(volume: Int): Boolean {
            calls += volume
            if (acceptsChanges) current = volume
            return acceptsChanges
        }
    }

    @Test
    fun lowersFromBaseBySteps() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream).apply { begin() }
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(2))
        assertEquals(8, stream.current)
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(1))
        assertEquals(9, stream.current)
    }

    @Test
    fun doesNotTouchVolumeWithoutSteps() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream).apply { begin() }
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(0))
        assertEquals(emptyList(), stream.calls)
    }

    @Test
    fun neverMutes() {
        val stream = FakeStream(2)
        AndroidMusicVolume(stream).apply { begin(); apply(3) }
        assertEquals(1, stream.current)
        val silent = FakeStream(0)
        AndroidMusicVolume(silent).apply { begin(); apply(3) }
        assertEquals(emptyList(), silent.calls)
    }

    @Test
    fun manualChangeBecomesNewBase() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream).apply { begin() }
        volume.apply(2)
        stream.current = 12
        assertEquals(VolumeAdjustment.MANUAL_CHANGE, volume.apply(2))
        assertEquals(12, stream.current)
        // 新しい基準から下げる。
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(1))
        assertEquals(11, stream.current)
    }

    @Test
    fun manualChangeBeforeLoweringBecomesNewBase() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream).apply { begin() }
        stream.current = 6
        assertEquals(VolumeAdjustment.MANUAL_CHANGE, volume.apply(0))
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(1))
        assertEquals(5, stream.current)
    }

    @Test
    fun endRestoresBase() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream).apply { begin() }
        volume.apply(3)
        volume.end()
        assertEquals(10, stream.current)
    }

    @Test
    fun endKeepsManualVolume() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream).apply { begin() }
        volume.apply(3)
        stream.current = 4
        volume.end()
        assertEquals(4, stream.current)
    }

    @Test
    fun ignoresStepsOutsideRecommendation() {
        val stream = FakeStream(10)
        val volume = AndroidMusicVolume(stream)
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(3))
        volume.end()
        assertEquals(emptyList(), stream.calls)
    }

    @Test
    fun failedChangeIsNotTreatedAsAppliedOrManual() {
        val stream = FakeStream(10).apply { acceptsChanges = false }
        val volume = AndroidMusicVolume(stream).apply { begin() }
        assertEquals(VolumeAdjustment.FAILED, volume.apply(2))
        assertEquals(VolumeAdjustment.FAILED, volume.apply(2))
        assertEquals(0, volume.steps)
        assertEquals(10, stream.current)
        stream.acceptsChanges = true
        assertEquals(VolumeAdjustment.APPLIED, volume.apply(2))
        assertEquals(8, stream.current)
        volume.end()
        assertEquals(10, stream.current)
    }

    @Test
    fun reportsActualStepsWhenTheVolumeFloorIsReached() {
        val volume = AndroidMusicVolume(FakeStream(2)).apply { begin() }
        volume.apply(3)
        assertEquals(1, volume.steps)
        volume.end()
        assertEquals(0, volume.steps)
    }
}
