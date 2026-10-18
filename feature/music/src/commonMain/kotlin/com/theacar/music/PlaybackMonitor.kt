package com.theacar.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/** Spotify から取得した再生状態と、その取得時刻。再生位置は取得時刻から補間して表示する。 */
data class NowPlaying(val playback: MusicPlayback, val receivedAtMillis: Long)

/**
 * 画面に出す再生状態を持つ。景観BGMの推薦中は推薦のループが取得した結果（[report]）を使い、
 * それ以外（手動で流した曲など）は画面が見ている間だけ [pollMillis] ごとに Spotify から取る。見ていないときは問い合わせない。
 */
class PlaybackMonitor(
    private val service: MusicService,
    scope: CoroutineScope,
    private val bgm: StateFlow<BgmMusicState>,
    private val connected: () -> Boolean,
    /** 単調増加する時刻。 */
    private val nowMillis: () -> Long,
    private val pollMillis: Long = 5_000,
) {
    private val mutableBgmPlayback = MutableStateFlow<NowPlaying?>(null)
    /** 景観BGMの推薦中だけ更新する。Spotify アプリを開かなくても、何が流れているかを示すため。 */
    val bgmPlayback = mutableBgmPlayback.asStateFlow()
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val display: StateFlow<NowPlaying?> = run {
        val polled = flow {
            emit(null)
            while (true) {
                if (connected() && !bgm.value.enabled) {
                    try {
                        emit(NowPlaying(service.playback(), nowMillis()))
                    } catch (_: MusicException) { }
                }
                withTimeoutOrNull(pollMillis) { refresh.first() }
            }
        }
        combine(mutableBgmPlayback, polled, bgm.map { it.enabled }.distinctUntilChanged()) { fromBgm, other, enabled ->
            if (enabled) fromBgm ?: other else other
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** いま分かっている再生状態。景観BGMの取得結果を優先する。 */
    fun latest(): NowPlaying? = mutableBgmPlayback.value ?: display.value

    /** 景観BGMの推薦が取得した再生状態を反映する。 */
    fun report(playback: MusicPlayback) { mutableBgmPlayback.value = NowPlaying(playback, nowMillis()) }

    /** 景観BGMの推薦が取り直す。取れなければ前の値のまま。 */
    suspend fun refreshBgm() {
        try { report(service.playback()) } catch (_: MusicException) { }
    }

    /** 再生の操作を受け付けた後に呼ぶ。少し待って取り直し、画面の表示を追従させる。 */
    suspend fun afterControl() {
        try {
            delay(500)
            val fresh = NowPlaying(service.playback(), nowMillis())
            if (bgm.value.enabled) mutableBgmPlayback.value = fresh
        } catch (_: MusicException) { }
        requestRefresh()
    }

    /** 画面向けの問い合わせを、待ち時間を切り上げてすぐ行う。 */
    fun requestRefresh() { refresh.tryEmit(Unit) }

    fun clear() { mutableBgmPlayback.value = null }
}
