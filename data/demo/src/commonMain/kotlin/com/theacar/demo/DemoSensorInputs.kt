package com.theacar.demo

import com.theacar.perception.SceneType
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.CameraFrame
import com.theacar.sensing.CaptureClock
import com.theacar.sensing.InputStatus
import com.theacar.sensing.SensorSample
import com.theacar.sensing.SensorSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

class DemoAudioInput(private val clock: CaptureClock) : SensorSource<AudioFrame> {
    private var sequence = 0L
    private var level = 0.24f
    private val mutableStatus = MutableStateFlow<InputStatus>(InputStatus.Idle)
    private val frames = MutableStateFlow(createFrame(false))
    override val status: StateFlow<InputStatus> = mutableStatus.asStateFlow()
    override val samples: Flow<SensorSample<AudioFrame>> = frames
        .onStart { mutableStatus.value = InputStatus.Active }
        .onCompletion { mutableStatus.value = InputStatus.Idle }

    fun setConversationLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        frames.value = createFrame(false)
    }

    fun emitUnusualSound() {
        frames.value = createFrame(true)
    }

    private fun createFrame(sound: Boolean): SensorSample<AudioFrame> {
        val samples = FloatArray(160) { level }
        samples[0] = if (sound) -1f else 0f
        return SensorSample(sequence++, clock.nowMillis(), AudioFrame.fromPcm(samples, 16_000))
    }
}

class DemoCameraInput(private val clock: CaptureClock) : SensorSource<CameraFrame> {
    private var sequence = 0L
    private var scene = SceneType.UNKNOWN
    private val mutableStatus = MutableStateFlow<InputStatus>(InputStatus.Idle)
    private val frames = MutableStateFlow(createFrame())
    override val status: StateFlow<InputStatus> = mutableStatus.asStateFlow()
    override val samples: Flow<SensorSample<CameraFrame>> = frames
        .onStart { mutableStatus.value = InputStatus.Active }
        .onCompletion { mutableStatus.value = InputStatus.Idle }

    fun cycleScene() {
        val scenes = SceneType.entries
        scene = scenes[(scenes.indexOf(scene) + 1) % scenes.size]
        frames.value = createFrame()
    }

    private fun createFrame(): SensorSample<CameraFrame> = SensorSample(
        sequence++, clock.nowMillis(),
        CameraFrame.fromRgb(byteArrayOf(scene.ordinal.toByte(), 0, 0), 1, 1),
    )
}
