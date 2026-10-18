package com.theacar.inference

enum class ExecutionLocation { ON_DEVICE, REMOTE }

data class ModelDescriptor(
    val id: String,
    val version: String,
    val location: ExecutionLocation,
)

data class Prediction<out T>(
    val value: T,
    val confidence: Float,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

interface InferenceEngine<in I, out O> {
    val model: ModelDescriptor
    suspend fun prepare() = Unit
    suspend fun infer(input: I): Prediction<O>
    suspend fun close() = Unit
}
