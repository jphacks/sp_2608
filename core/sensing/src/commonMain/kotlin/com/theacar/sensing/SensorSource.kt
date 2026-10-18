package com.theacar.sensing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

data class SensorSample<out T>(
    val sequence: Long,
    val capturedAtMillis: Long,
    val value: T,
) {
    init {
        require(sequence >= 0 && capturedAtMillis >= 0)
    }
}

sealed interface InputStatus {
    data object Idle : InputStatus
    data object Active : InputStatus
    data object PermissionRequired : InputStatus
    data object Unavailable : InputStatus
    data object Failed : InputStatus
}

interface SensorSource<T> {
    val status: StateFlow<InputStatus>
    val samples: Flow<SensorSample<T>>
}

fun interface CaptureClock {
    fun nowMillis(): Long
}

class SessionClock : CaptureClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}
