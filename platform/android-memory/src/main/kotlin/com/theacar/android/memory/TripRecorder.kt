package com.theacar.android.memory

import com.theacar.location.LocationSource
import com.theacar.location.LocationStatus
import android.os.SystemClock
import com.theacar.memory.TripMemoryDraftBuilder
import com.theacar.navigation.NavigationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ナビの到着を見張り、案内中に通った位置とあわせて旅の記録を保存する。保存した記録の ID を [lastSavedId] で知らせる。地名は利用者がステッカー画面で入れる。 */
class TripRecorder(
    session: NavigationSession,
    locations: LocationSource,
    private val store: TripMemoryStore,
    private val scope: CoroutineScope,
) {
    private val builder = TripMemoryDraftBuilder()
    private val mutableLastSavedId = MutableStateFlow<String?>(null)
    val lastSavedId = mutableLastSavedId.asStateFlow()

    init {
        scope.launch {
            locations.location.collect { state ->
                if (state.status == LocationStatus.AVAILABLE) state.fix?.let { builder.onLocation(it, SystemClock.elapsedRealtime()) }
            }
        }
        scope.launch {
            session.state.collect { state ->
                val memory = builder.onState(state) ?: return@collect
                store.save(memory)
                mutableLastSavedId.value = memory.id
            }
        }
    }
}
