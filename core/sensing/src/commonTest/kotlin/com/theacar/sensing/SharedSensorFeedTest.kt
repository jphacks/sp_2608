package com.theacar.sensing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSensorFeedTest {
    @Test
    fun twoConsumersShareOneCaptureAndLastConsumerReleasesIt() = runTest {
        var opens = 0
        var closes = 0
        val source = object : SensorSource<Int> {
            override val status = MutableStateFlow<InputStatus>(InputStatus.Idle)
            override val samples = flow {
                opens++
                status.value = InputStatus.Active
                try {
                    emit(SensorSample(0, 0, 1))
                    awaitCancellation()
                } finally {
                    closes++
                    status.value = InputStatus.Idle
                }
            }
        }
        val feed = SharedSensorFeed(source, backgroundScope)
        val first = backgroundScope.launch { feed.samples.collect {} }
        val second = backgroundScope.launch { feed.samples.collect {} }
        runCurrent()
        assertEquals(1, opens)

        first.cancel()
        runCurrent()
        assertEquals(0, closes)
        second.cancel()
        runCurrent()
        assertEquals(1, closes)
        assertEquals(InputStatus.Idle, feed.status.value)
    }

    @Test
    fun capturedBuffersCannotBeChangedByTheirOriginalOwner() {
        val pcm = floatArrayOf(0.1f, 0.2f)
        val audio = AudioFrame.fromPcm(pcm, 16_000)
        pcm[0] = 0.9f
        assertEquals(0.1f, audio.sampleAt(0))

        val rgb = byteArrayOf(1, 2, 3)
        val camera = CameraFrame.fromRgb(rgb, 1, 1)
        rgb[0] = 99
        assertEquals(1, camera.channelAt(0))
    }
}
