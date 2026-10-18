package com.theacar.sensing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.shareIn

class SharedSensorFeed<T>(
    source: SensorSource<T>,
    scope: CoroutineScope,
    capacity: Int = 2,
) {
    init {
        require(capacity > 0)
    }

    val status: StateFlow<InputStatus> = source.status
    val samples: SharedFlow<SensorSample<T>> = source.samples
        .buffer(capacity, BufferOverflow.DROP_OLDEST)
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)
}
