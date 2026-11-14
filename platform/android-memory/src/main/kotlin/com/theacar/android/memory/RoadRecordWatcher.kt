package com.theacar.android.memory

import android.os.SystemClock
import com.theacar.location.LocationSource
import com.theacar.location.LocationStatus
import com.theacar.memory.records.RecordSong
import com.theacar.memory.records.RoadCoverage
import com.theacar.memory.records.RoadShape
import com.theacar.memory.records.roadRecord
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 案内中の位置を見張り、名のある道を走り切ったらレコードを手に入れる。案内の種類（景観ルートか普通の経路か）は問わない。
 *
 * レコードに刻む曲は、走り切った時点で流れていた曲。そのとき何も流れていなければ、その道で最後に流れていた曲。
 * 走り切ったその場で保存し、走行中は短い読み上げだけにする（画面の演出は着いてから）。
 */
class RoadRecordWatcher(
    private val states: StateFlow<NavigationState>,
    locations: LocationSource,
    private val store: RoadRecordStore,
    shapes: suspend () -> List<RoadShape>,
    private val currentSong: () -> RecordSong?,
    private val announce: (String) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val onError: (Exception) -> Unit = {},
) {
    private var coverage: RoadCoverage? = null
    private var sessionId = 0L
    private val lastSongOnRoad = mutableMapOf<String, RecordSong>()
    private val counting = Mutex()

    init {
        scope.launch {
            val loaded = withContext(Dispatchers.Default) { shapes() }
            if (loaded.isEmpty()) return@launch
            coverage = RoadCoverage(loaded)
            launch {
                states.collect { state ->
                    // 新しい案内が始まったら数え直す（引き直しでは数えた分を残す）
                    if (state.phase == NavigationPhase.GUIDING && state.sessionId != sessionId) counting.withLock {
                        sessionId = state.sessionId
                        coverage?.reset()
                        lastSongOnRoad.clear()
                    }
                }
            }
            locations.location.collect { location ->
                val fix = location.fix
                if (location.status != LocationStatus.AVAILABLE || fix == null || states.value.phase != NavigationPhase.GUIDING) return@collect
                val update = counting.withLock { coverage?.onLocation(fix, elapsedNow()) } ?: return@collect
                currentSong()?.let { song -> update.near.forEach { lastSongOnRoad[it] = song } }
                update.unlocked.forEach { id -> unlock(id) }
            }
        }
    }

    private suspend fun unlock(id: String) {
        val song = currentSong() ?: lastSongOnRoad[id]
        try {
            store.unlock(id, now(), song)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            onError(error)
        }
        roadRecord(id)?.let { announce("${it.name}のレコードを手に入れました") }
    }
}
