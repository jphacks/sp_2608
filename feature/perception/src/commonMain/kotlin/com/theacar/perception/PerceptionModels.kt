package com.theacar.perception

import com.theacar.inference.ModelDescriptor

enum class SoundType { NONE, FLATULENCE }
enum class SceneType { UNKNOWN, COASTAL, FOREST, URBAN }

data class AudioAssessment(
    val conversationLevel: Float,
    val sound: SoundType = SoundType.NONE,
) {
    init {
        require(conversationLevel.isFinite() && conversationLevel in 0f..1f)
    }
}

data class SceneAssessment(val scene: SceneType)

data class Observation<out T>(
    val value: T,
    val confidence: Float,
    val capturedAtMillis: Long,
    val model: ModelDescriptor,
)

data class SoundEvent(
    val sequence: Long,
    val type: SoundType,
    val capturedAtMillis: Long,
)

enum class AnalysisStatus {
    NOT_CONFIGURED, IDLE, ACTIVE, PERMISSION_REQUIRED, INPUT_UNAVAILABLE,
    INPUT_FAILED, LOW_CONFIDENCE, STALE, TIMED_OUT, INFERENCE_FAILED, REMOTE_DISABLED,
}

data class PerceptionState(
    val audio: Observation<AudioAssessment>? = null,
    val scene: Observation<SceneAssessment>? = null,
    val soundEvent: SoundEvent? = null,
    val audioStatus: AnalysisStatus = AnalysisStatus.NOT_CONFIGURED,
    val cameraStatus: AnalysisStatus = AnalysisStatus.NOT_CONFIGURED,
)

data class PerceptionPolicy(
    val minimumConfidence: Float = 0.75f,
    val maximumSampleAgeMillis: Long = 3_000,
    val observationTtlMillis: Long = 5_000,
    val soundEventTtlMillis: Long = 1_500,
    val soundCooldownMillis: Long = 10_000,
    val inferenceTimeoutMillis: Long = 2_000,
    val expiryIntervalMillis: Long = 250,
    val maxConcurrentInferences: Int = 1,
    val allowRemote: Boolean = false,
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        require(maximumSampleAgeMillis > 0 && observationTtlMillis > 0)
        require(soundEventTtlMillis > 0 && soundCooldownMillis >= soundEventTtlMillis)
        require(inferenceTimeoutMillis > 0 && expiryIntervalMillis > 0)
        require(maxConcurrentInferences > 0)
    }
}
