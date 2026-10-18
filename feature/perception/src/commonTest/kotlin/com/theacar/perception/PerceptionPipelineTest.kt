package com.theacar.perception

import com.theacar.inference.ExecutionLocation
import com.theacar.inference.InferenceEngine
import com.theacar.inference.ModelDescriptor
import com.theacar.inference.Prediction
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.CameraFrame
import com.theacar.sensing.CaptureClock
import com.theacar.sensing.InputStatus
import com.theacar.sensing.SensorSample
import com.theacar.sensing.SensorSource
import com.theacar.sensing.SharedSensorFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PerceptionPipelineTest {
    @Test
    fun lowConfidenceAndFailedInferenceDoNotBecomeDecisionsAndNextFrameRecovers() = runTest {
        val fixture = Fixture(this)
        fixture.engine.confidence = 0.3f
        runCurrent()
        fixture.emit(0)
        runCurrent()
        assertNull(fixture.pipeline.state.value.audio)
        assertEquals(AnalysisStatus.LOW_CONFIDENCE, fixture.pipeline.state.value.audioStatus)

        fixture.engine.fail = true
        fixture.emit(1)
        runCurrent()
        assertEquals(AnalysisStatus.INFERENCE_FAILED, fixture.pipeline.state.value.audioStatus)

        fixture.engine.fail = false
        fixture.engine.confidence = 0.9f
        fixture.emit(2)
        runCurrent()
        assertEquals(0.8f, fixture.pipeline.state.value.audio?.value?.conversationLevel)
        assertEquals(AnalysisStatus.ACTIVE, fixture.pipeline.state.value.audioStatus)
    }

    @Test
    fun soundEventsExpireAndRepeatedDetectionsRespectCooldown() = runTest {
        val fixture = Fixture(this)
        fixture.engine.sound = SoundType.FLATULENCE
        runCurrent()
        fixture.emit(0)
        runCurrent()
        assertEquals(0L, fixture.pipeline.state.value.soundEvent?.sequence)

        advanceTimeBy(1_500)
        runCurrent()
        assertNull(fixture.pipeline.state.value.soundEvent)
        assertTrue(fixture.pipeline.state.value.audio != null)
        fixture.emit(1)
        runCurrent()
        assertNull(fixture.pipeline.state.value.soundEvent)

        advanceTimeBy(8_500)
        runCurrent()
        fixture.emit(2)
        runCurrent()
        assertEquals(2L, fixture.pipeline.state.value.soundEvent?.sequence)
    }

    @Test
    fun permissionsLossImmediatelyClearsAudioAndEvents() = runTest {
        val fixture = Fixture(this)
        fixture.engine.sound = SoundType.FLATULENCE
        runCurrent()
        fixture.emit(0)
        runCurrent()
        fixture.source.status.value = InputStatus.PermissionRequired
        runCurrent()
        assertNull(fixture.pipeline.state.value.audio)
        assertNull(fixture.pipeline.state.value.soundEvent)
        assertEquals(AnalysisStatus.PERMISSION_REQUIRED, fixture.pipeline.state.value.audioStatus)
        fixture.emit(1)
        runCurrent()
        assertEquals(1, fixture.engine.calls)
    }

    @Test
    fun inferenceCompletingAfterPermissionLossCannotRestoreObservation() = runTest {
        val fixture = Fixture(this)
        fixture.engine.gate = CompletableDeferred()
        runCurrent()
        fixture.emit(0)
        runCurrent()
        fixture.source.status.value = InputStatus.PermissionRequired
        runCurrent()
        fixture.engine.gate!!.complete(Unit)
        runCurrent()
        assertNull(fixture.pipeline.state.value.audio)
        assertEquals(AnalysisStatus.PERMISSION_REQUIRED, fixture.pipeline.state.value.audioStatus)
    }

    @Test
    fun staleSamplesAreSkippedAndLateResultsAreDiscarded() = runTest {
        val fixture = Fixture(this, policy = PerceptionPolicy(maximumSampleAgeMillis = 1_000))
        runCurrent()
        advanceTimeBy(1_001)
        fixture.emit(0, capturedAt = 0)
        runCurrent()
        assertEquals(0, fixture.engine.calls)
        assertEquals(AnalysisStatus.STALE, fixture.pipeline.state.value.audioStatus)

        fixture.engine.waitMillis = 1_100
        fixture.emit(1)
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertNull(fixture.pipeline.state.value.audio)
        assertEquals(AnalysisStatus.STALE, fixture.pipeline.state.value.audioStatus)
    }

    @Test
    fun timedOutInferenceDoesNotStopLaterFrames() = runTest {
        val fixture = Fixture(this, policy = PerceptionPolicy(inferenceTimeoutMillis = 100))
        fixture.engine.waitMillis = 500
        runCurrent()
        fixture.emit(0)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(AnalysisStatus.TIMED_OUT, fixture.pipeline.state.value.audioStatus)

        fixture.engine.waitMillis = 0
        fixture.emit(1)
        runCurrent()
        assertEquals(AnalysisStatus.ACTIVE, fixture.pipeline.state.value.audioStatus)
    }

    @Test
    fun slowInferenceSkipsBacklogAndProcessesNewestFrame() = runTest {
        val fixture = Fixture(this)
        fixture.engine.gate = CompletableDeferred()
        runCurrent()
        fixture.emit(0, level = 0.1f)
        runCurrent()
        for (index in 2..9) {
            fixture.emit(index.toLong(), level = index / 10f)
            runCurrent()
        }
        fixture.engine.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(0.1f, 0.9f), fixture.engine.inputs)
    }

    @Test
    fun remoteEngineDoesNotOpenInputUnlessExplicitlyEnabled() = runTest {
        val fixture = Fixture(this, location = ExecutionLocation.REMOTE)
        runCurrent()
        assertEquals(AnalysisStatus.REMOTE_DISABLED, fixture.pipeline.state.value.audioStatus)
        assertEquals(0, fixture.source.samples.subscriptionCount.value)
        assertEquals(0, fixture.engine.calls)
    }

    @Test
    fun cameraAndAudioShareConcurrencyBudget() = runTest {
        val audio = Source<AudioFrame>()
        val camera = Source<CameraFrame>()
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maximum = 0
        suspend fun enter() {
            running++
            maximum = maxOf(maximum, running)
            gate.await()
            running--
        }
        val audioEngine = object : InferenceEngine<AudioFrame, AudioAssessment> {
            override val model = descriptor()
            override suspend fun infer(input: AudioFrame): Prediction<AudioAssessment> {
                enter()
                return Prediction(AudioAssessment(0.2f), 1f)
            }
        }
        val cameraEngine = object : InferenceEngine<CameraFrame, SceneAssessment> {
            override val model = descriptor()
            override suspend fun infer(input: CameraFrame): Prediction<SceneAssessment> {
                enter()
                return Prediction(SceneAssessment(SceneType.COASTAL), 1f)
            }
        }
        val pipeline = PerceptionPipeline(
            SharedSensorFeed(audio, backgroundScope),
            SharedSensorFeed(camera, backgroundScope),
            audioEngine, cameraEngine, backgroundScope,
            CaptureClock { testScheduler.currentTime },
            inferenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        audio.samples.emit(SensorSample(0, 0, AudioFrame.fromPcm(floatArrayOf(0.1f), 16_000)))
        camera.samples.emit(SensorSample(0, 0, CameraFrame.fromRgb(byteArrayOf(1, 2, 3), 1, 1)))
        runCurrent()
        assertEquals(1, maximum)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SceneType.COASTAL, pipeline.state.value.scene?.value?.scene)
        assertTrue(pipeline.state.value.audio != null)
        assertEquals(1, maximum)
    }

    @Test
    fun closingPipelineReleasesSubscriptionsAndClearsObservations() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        fixture.emit(0)
        runCurrent()
        fixture.pipeline.close()
        runCurrent()
        assertEquals(0, fixture.source.samples.subscriptionCount.value)
        assertEquals(PerceptionState(), fixture.pipeline.state.value)
        assertEquals(1, fixture.engine.prepares)
        assertEquals(1, fixture.engine.closes)
    }

    @Test
    fun modelIsPreparedOnceAndReleasedWhenOwnerScopeStops() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        fixture.emit(0)
        runCurrent()
        fixture.emit(1)
        runCurrent()
        assertEquals(1, fixture.engine.prepares)
        assertEquals(2, fixture.engine.calls)
        fixture.pipeline.close()
        runCurrent()
        assertEquals(1, fixture.engine.closes)
    }

    @Test
    fun oldSoundResultCannotCreateFreshCoverEvent() = runTest {
        val fixture = Fixture(this, policy = PerceptionPolicy(inferenceTimeoutMillis = 3_000))
        fixture.engine.sound = SoundType.FLATULENCE
        fixture.engine.waitMillis = 1_600
        runCurrent()
        fixture.emit(0)
        runCurrent()
        advanceTimeBy(1_600)
        runCurrent()
        assertTrue(fixture.pipeline.state.value.audio != null)
        assertNull(fixture.pipeline.state.value.soundEvent)
    }

    @Test
    fun oldObservationsExpireWithoutNewSensorFrames() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        fixture.emit(0)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(fixture.pipeline.state.value.audio)
        assertEquals(AnalysisStatus.STALE, fixture.pipeline.state.value.audioStatus)
    }

    private class Fixture(
        val scope: TestScope,
        policy: PerceptionPolicy = PerceptionPolicy(),
        location: ExecutionLocation = ExecutionLocation.ON_DEVICE,
    ) {
        val source = Source<AudioFrame>()
        val engine = AudioEngine(location)
        val pipeline = PerceptionPipeline(
            SharedSensorFeed(source, scope.backgroundScope), null, engine, null,
            scope.backgroundScope, CaptureClock { scope.testScheduler.currentTime },
            policy, StandardTestDispatcher(scope.testScheduler),
        )

        suspend fun emit(
            sequence: Long,
            capturedAt: Long = scope.testScheduler.currentTime,
            level: Float = 0.8f,
        ) {
            source.samples.emit(
                SensorSample(sequence, capturedAt, AudioFrame.fromPcm(floatArrayOf(level), 16_000))
            )
        }
    }

    private class Source<T> : SensorSource<T> {
        override val status = MutableStateFlow<InputStatus>(InputStatus.Active)
        override val samples = MutableSharedFlow<SensorSample<T>>(extraBufferCapacity = 1)
    }

    private class AudioEngine(location: ExecutionLocation) : InferenceEngine<AudioFrame, AudioAssessment> {
        override val model = ModelDescriptor("test-audio", "1", location)
        var confidence = 0.9f
        var sound = SoundType.NONE
        var fail = false
        var calls = 0
        var prepares = 0
        var closes = 0
        var waitMillis = 0L
        var gate: CompletableDeferred<Unit>? = null
        val inputs = mutableListOf<Float>()

        override suspend fun prepare() { prepares++ }
        override suspend fun close() { closes++ }

        override suspend fun infer(input: AudioFrame): Prediction<AudioAssessment> {
            calls++
            inputs += input.sampleAt(0)
            gate?.await()
            delay(waitMillis)
            check(!fail) { "Model unavailable" }
            return Prediction(AudioAssessment(input.sampleAt(0), sound), confidence)
        }
    }
}

private fun descriptor() = ModelDescriptor("test", "1", ExecutionLocation.ON_DEVICE)
