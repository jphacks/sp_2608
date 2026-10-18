package com.theacar.perception

import com.theacar.inference.ExecutionLocation
import com.theacar.inference.InferenceEngine
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.CameraFrame
import com.theacar.sensing.CaptureClock
import com.theacar.sensing.InputStatus
import com.theacar.sensing.SharedSensorFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PerceptionPipeline(
    audio: SharedSensorFeed<AudioFrame>?,
    camera: SharedSensorFeed<CameraFrame>?,
    audioEngine: InferenceEngine<AudioFrame, AudioAssessment>?,
    cameraEngine: InferenceEngine<CameraFrame, SceneAssessment>?,
    private val scope: CoroutineScope,
    private val clock: CaptureClock,
    private val policy: PerceptionPolicy = PerceptionPolicy(),
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableState = MutableStateFlow(PerceptionState())
    val state: StateFlow<PerceptionState> = mutableState.asStateFlow()
    private val permits = Semaphore(policy.maxConcurrentInferences)
    private var lastSoundAtMillis: Long? = null
    private val jobs = mutableListOf<Job>()
    private var closed = false

    init {
        observe(audio, audioEngine, ::setAudioStatus) { sequence, observation ->
            val sound = observation.value.sound
            val now = clock.nowMillis()
            val previous = lastSoundAtMillis
            val event = if (
                sound != SoundType.NONE &&
                now - observation.capturedAtMillis < policy.soundEventTtlMillis &&
                (previous == null || now - previous >= policy.soundCooldownMillis)
            ) {
                lastSoundAtMillis = now
                SoundEvent(sequence, sound, observation.capturedAtMillis)
            } else null
            mutableState.update {
                it.copy(
                    audio = observation,
                    audioStatus = AnalysisStatus.ACTIVE,
                    soundEvent = event ?: it.soundEvent,
                )
            }
        }
        observe(camera, cameraEngine, ::setCameraStatus) { _, observation ->
            mutableState.update {
                it.copy(scene = observation, cameraStatus = AnalysisStatus.ACTIVE)
            }
        }
        if (jobs.isNotEmpty()) {
            jobs += scope.launch {
                while (true) {
                    delay(policy.expiryIntervalMillis)
                    expireObservations()
                }
            }
        }
    }

    fun close() {
        closed = true
        jobs.forEach { it.cancel() }
        mutableState.value = PerceptionState()
    }

    private fun <I, O> observe(
        feed: SharedSensorFeed<I>?,
        engine: InferenceEngine<I, O>?,
        setStatus: (AnalysisStatus) -> Unit,
        accept: (Long, Observation<O>) -> Unit,
    ) {
        if (feed == null || engine == null) return
        if (engine.model.location == ExecutionLocation.REMOTE && !policy.allowRemote) {
            setStatus(AnalysisStatus.REMOTE_DISABLED)
            return
        }
        jobs += scope.launch {
            feed.status.collect { setStatus(it.toAnalysisStatus()) }
        }
        jobs += scope.launch {
            var lastSequence = -1L
            var prepared = false
            try {
                feed.samples.conflate().collect { sample ->
                    if (sample.sequence <= lastSequence) return@collect
                    lastSequence = sample.sequence
                    if (feed.status.value != InputStatus.Active) return@collect
                    permits.withPermit {
                        if (feed.status.value != InputStatus.Active) return@withPermit
                        if (!isFresh(sample.capturedAtMillis)) {
                            setStatus(AnalysisStatus.STALE)
                            return@withPermit
                        }
                        try {
                            val prediction = withTimeoutOrNull(policy.inferenceTimeoutMillis) {
                                withContext(inferenceDispatcher) {
                                    if (!prepared) {
                                        engine.prepare()
                                        prepared = true
                                    }
                                    engine.infer(sample.value)
                                }
                            }
                            when {
                                closed -> Unit
                                feed.status.value != InputStatus.Active -> Unit
                                prediction == null -> setStatus(AnalysisStatus.TIMED_OUT)
                                !isFresh(sample.capturedAtMillis) -> setStatus(AnalysisStatus.STALE)
                                prediction.confidence < policy.minimumConfidence ->
                                    setStatus(AnalysisStatus.LOW_CONFIDENCE)
                                else -> accept(
                                    sample.sequence,
                                    Observation(
                                        prediction.value, prediction.confidence,
                                        sample.capturedAtMillis, engine.model,
                                    ),
                                )
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (feed.status.value == InputStatus.Active) {
                                setStatus(AnalysisStatus.INFERENCE_FAILED)
                            }
                        }
                    }
                }
            } finally {
                try {
                    withContext(NonCancellable + inferenceDispatcher) { engine.close() }
                } catch (error: Exception) {
                    if (!closed) setStatus(AnalysisStatus.INFERENCE_FAILED)
                }
            }
        }
    }

    private fun isFresh(capturedAtMillis: Long): Boolean =
        clock.nowMillis() - capturedAtMillis in 0..policy.maximumSampleAgeMillis

    private fun expireObservations() {
        val now = clock.nowMillis()
        mutableState.update { current ->
            val audio = current.audio?.takeIf { now - it.capturedAtMillis < policy.observationTtlMillis }
            val scene = current.scene?.takeIf { now - it.capturedAtMillis < policy.observationTtlMillis }
            val sound = current.soundEvent?.takeIf { now - it.capturedAtMillis < policy.soundEventTtlMillis }
            current.copy(
                audio = audio,
                scene = scene,
                soundEvent = sound,
                audioStatus = if (current.audio != null && audio == null) AnalysisStatus.STALE else current.audioStatus,
                cameraStatus = if (current.scene != null && scene == null) AnalysisStatus.STALE else current.cameraStatus,
            )
        }
    }

    private fun setAudioStatus(status: AnalysisStatus) {
        if (closed) return
        mutableState.update {
            if (status.isInputLoss()) {
                it.copy(audioStatus = status, audio = null, soundEvent = null)
            } else it.copy(audioStatus = status)
        }
    }

    private fun setCameraStatus(status: AnalysisStatus) {
        if (closed) return
        mutableState.update {
            if (status.isInputLoss()) {
                it.copy(cameraStatus = status, scene = null)
            } else it.copy(cameraStatus = status)
        }
    }
}

private fun InputStatus.toAnalysisStatus(): AnalysisStatus = when (this) {
    InputStatus.Idle -> AnalysisStatus.IDLE
    InputStatus.Active -> AnalysisStatus.ACTIVE
    InputStatus.PermissionRequired -> AnalysisStatus.PERMISSION_REQUIRED
    InputStatus.Unavailable -> AnalysisStatus.INPUT_UNAVAILABLE
    InputStatus.Failed -> AnalysisStatus.INPUT_FAILED
}

private fun AnalysisStatus.isInputLoss(): Boolean = this in setOf(
    AnalysisStatus.IDLE, AnalysisStatus.PERMISSION_REQUIRED,
    AnalysisStatus.INPUT_UNAVAILABLE, AnalysisStatus.INPUT_FAILED,
)
