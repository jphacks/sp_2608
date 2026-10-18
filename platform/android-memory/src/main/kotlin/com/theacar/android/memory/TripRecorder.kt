package com.theacar.android.memory

import com.theacar.location.LocationSource
import com.theacar.location.LocationStatus
import android.os.SystemClock
import com.theacar.memory.TripMemoryDraftBuilder
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.memory.TripMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 案内状態とGPSは同じディスパッチャーで受け取り、保存に失敗した記録は再試行まで保持する。 */
class TripRecorder(
    private val states: StateFlow<NavigationState>,
    locations: LocationSource,
    private val save: suspend (TripMemory) -> Unit,
    private val scope: CoroutineScope,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val onError: (Exception) -> Unit = {},
) {
    private val builder = TripMemoryDraftBuilder()
    private val mutableLastSavedId = MutableStateFlow<String?>(null)
    val lastSavedId = mutableLastSavedId.asStateFlow()
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed = mutableSaveFailed.asStateFlow()
    private data class PendingTrip(val sessionId: Long, val memory: TripMemory)
    private val pending = linkedMapOf<String, PendingTrip>()
    private val saving = Mutex()
    private var currentSession = 0L

    init {
        scope.launch {
            locations.location.collect { state ->
                if (state.status == LocationStatus.AVAILABLE) state.fix?.let { builder.onLocation(it, elapsedNow()) }
            }
        }
        scope.launch {
            states.collect { state ->
                if (state.phase == NavigationPhase.GUIDING && state.sessionId != currentSession) {
                    currentSession = state.sessionId
                    mutableLastSavedId.value = null
                }
                val memory = builder.onState(state) ?: return@collect
                pending[memory.id] = PendingTrip(state.sessionId, memory)
                retry()
            }
        }
    }

    fun retry() {
        scope.launch {
            saving.withLock {
                for ((id, entry) in pending.toMap()) {
                    try {
                        save(entry.memory)
                        pending.remove(id)
                        if (entry.sessionId == currentSession) mutableLastSavedId.value = id
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableSaveFailed.value = true
                        onError(error)
                    }
                }
                mutableSaveFailed.value = pending.isNotEmpty()
            }
        }
    }
}
