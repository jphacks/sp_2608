package com.theacar.music

import com.theacar.sensing.AudioFrame
import com.theacar.sensing.InputStatus
import com.theacar.sensing.SensorSample
import com.theacar.sensing.SensorSource
import com.theacar.sensing.SharedSensorFeed
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CabinSpeechMonitorTest {
    @Test fun sharesTheMicrophoneAndReleasesItOnStop() = runTest {
        var opens = 0
        var closes = 0
        val source = object : SensorSource<AudioFrame> {
            override val status = MutableStateFlow<InputStatus>(InputStatus.Idle)
            override val samples = flow {
                opens++
                status.value = InputStatus.Active
                try {
                    var sequence = 0L
                    // 1秒ぶん（20ms × 50）の静かなフレーム。
                    repeat(50) { emit(SensorSample(sequence++, 0, AudioFrame.fromPcm(FloatArray(320), 16_000))) }
                    awaitCancellation()
                } finally {
                    closes++
                    status.value = InputStatus.Idle
                }
            }
        }
        val feed = SharedSensorFeed(source, backgroundScope, capacity = 50)
        val seconds = mutableListOf<Boolean>()
        val monitor = CabinSpeechMonitor(feed, backgroundScope, { currentTime }, onSecond = { seconds += it.speaking })
        assertEquals(CabinState.NORMAL, monitor.current())
        monitor.start()
        val other = backgroundScope.launch { feed.samples.collect {} }
        runCurrent()
        assertEquals(1, opens)
        assertEquals(CabinListening.ACTIVE, monitor.status.value.listening)
        assertEquals(listOf(false), seconds)
        assertNotNull(monitor.status.value.state)

        monitor.stop()
        runCurrent()
        assertEquals(0, closes)
        assertEquals(CabinStatus(), monitor.status.value)
        other.cancel()
        runCurrent()
        assertEquals(1, closes)
    }

    @Test fun reportsAMissingPermission() = runTest {
        val source = object : SensorSource<AudioFrame> {
            override val status = MutableStateFlow<InputStatus>(InputStatus.Idle)
            override val samples = flow<SensorSample<AudioFrame>> { status.value = InputStatus.PermissionRequired }
        }
        val monitor = CabinSpeechMonitor(SharedSensorFeed(source, backgroundScope), backgroundScope, { currentTime })
        monitor.start()
        runCurrent()
        assertEquals(CabinListening.PERMISSION_REQUIRED, monitor.status.value.listening)
        assertEquals(CabinState.NORMAL, monitor.current())
    }
}
