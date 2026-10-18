package com.theacar.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 景観BGMの推薦（[BgmMusicSession]）を、再生状態を確かめながら定期的に進める。
 * 普段は5秒ごと、曲の終わりが近いときは細かく確かめ、残り1.5秒ほどで次の曲へ切り替えられるようにする。
 */
class BgmPlaybackLoop(
    private val bgm: BgmMusicSession,
    private val service: MusicService,
    private val session: MusicSession,
    private val playback: PlaybackMonitor,
    private val scope: CoroutineScope,
    private val scenery: () -> SceneryRoute?,
    private val cabin: () -> CabinState,
    private val connected: () -> Boolean,
    /** 再生状態の取得で認証が切れたと分かったとき。推薦はもう止めてある。 */
    private val onAuthorizationLost: () -> Unit,
    /** 取得した再生状態。記録用。 */
    private val onPlayback: (MusicPlayback) -> Unit = {},
) {
    private var job: Job? = null

    /**
     * 推薦を始める。[awaitScenery] が真なら、景観が届くまで最初の1曲を少し待つ。
     * 案内開始の直後は現在位置が経路上に乗っておらず、景観を渡せないため。
     */
    fun start(
        members: List<MusicMember>,
        pool: List<PlaylistEntry>,
        mode: RecommendationMode,
        awaitScenery: Boolean = false,
        requestsOnly: Boolean = false,
    ) {
        halt()
        bgm.start(members, pool, mode, requestsOnly = requestsOnly)
        job = scope.launch {
            // 通常の経路では景観が無いので待ち切ってから選ぶ。
            if (awaitScenery) withTimeoutOrNull(SCENERY_WAIT_MILLIS) { while (scenery() == null) delay(1_000) }
            var played = bgm.state.value.playedCount
            while (isActive && bgm.state.value.enabled && connected()) {
                val deviceId = session.state.value.selectedDeviceId
                val current = if (deviceId == null) MusicPlayback() else try {
                    service.playback()
                } catch (error: MusicException) {
                    if (error.failure == MusicFailure.AUTHORIZATION) { bgm.stop(); onAuthorizationLost(); break }
                    // 状態の取得失敗を曲の終了と誤認して、予約曲で割り込まない。
                    delay(IDLE_INTERVAL_MILLIS)
                    continue
                }
                if (deviceId != null) playback.report(current)
                onPlayback(current)
                bgm.update(scenery(), cabin(), deviceId, current, session.state.value.busy)
                val count = bgm.state.value.playedCount
                if (count != played) {
                    played = count
                    session.refresh()
                    // 曲を切り替えた直後は表示を早く追従させる。
                    delay(1_500)
                    playback.refreshBgm()
                }
                val remaining = if (current.playing) bgm.remainingMillis(current) else null
                delay(if (remaining != null && remaining <= END_WATCH_MILLIS) END_INTERVAL_MILLIS else IDLE_INTERVAL_MILLIS)
            }
        }
    }

    /** 推薦を止める。流れている曲はそのまま。 */
    fun halt() {
        bgm.stop()
        job?.cancel()
        job = null
        playback.clear()
    }

    private companion object {
        const val SCENERY_WAIT_MILLIS = 10_000L
        /** 曲の残りがこれ以下になったら、再生状態を細かく確かめる。 */
        const val END_WATCH_MILLIS = 8_000
        const val END_INTERVAL_MILLIS = 750L
        const val IDLE_INTERVAL_MILLIS = 5_000L
    }
}
