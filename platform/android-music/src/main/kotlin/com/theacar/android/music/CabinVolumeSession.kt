package com.theacar.android.music

import com.theacar.music.BgmMusicState
import com.theacar.music.CabinListening
import com.theacar.music.CabinSecond
import com.theacar.music.CabinStatus
import com.theacar.music.CabinVolumeController
import com.theacar.music.MusicState
import com.theacar.music.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * このスマホで景観BGMを再生している間だけ、端末の音量を調整する。
 * - 推奨音量：流れている曲を選んだときの bgm-api の volume。普段（[USUAL_VOLUME]）との比率を、推薦を始めたときの音量に掛ける。
 *   マイクが使えなくても反映する。
 * - 会話：声が普段より大きいと、さらに [CabinVolumeController] の段数ぶん下げる。マイクで聞けている間だけ。
 */
internal class CabinVolumeSession(
    private val controller: CabinVolumeController,
    private val volume: AndroidMusicVolume,
    scope: CoroutineScope,
    private val bgm: StateFlow<BgmMusicState>,
    private val music: StateFlow<MusicState>,
    private val playback: StateFlow<NowPlaying?>,
    private val cabin: StateFlow<CabinStatus>,
    private val connection: StateFlow<SpotifyConnectionState>,
    private val nowMillis: () -> Long,
    private val onObservation: (CabinSecond, Double, Int) -> Unit = { _, _, _ -> },
) {
    private var adjusting = false
    private val mutableSteps = MutableStateFlow(0)
    val steps = mutableSteps.asStateFlow()

    init {
        scope.launch {
            // 曲が変わって推奨音量が変わったときや、再生が止まったときは、次の1秒を待たずに反映する。
            combine(bgm, music, playback, cabin, connection) { _, _, _, _, _ -> }.collect { refresh() }
        }
    }

    fun beginDrive() {
        suspendAdjustment()
        controller.start()
    }

    fun stop() = suspendAdjustment()

    fun observe(second: CabinSecond) {
        if (!canAdjust()) {
            suspendAdjustment()
            return
        }
        adjust(controller.observe(nowMillis(), second))
        onObservation(second, controller.baselineSnrDb, mutableSteps.value)
    }

    private fun refresh() {
        if (!canAdjust()) {
            suspendAdjustment()
            return
        }
        if (cabin.value.listening != CabinListening.ACTIVE) controller.reset()
        adjust(controller.steps)
    }

    private fun adjust(steps: Int) {
        if (!adjusting) {
            volume.begin()
            adjusting = true
        }
        when (volume.apply(steps, scale())) {
            VolumeAdjustment.APPLIED -> mutableSteps.value = volume.steps
            VolumeAdjustment.MANUAL_CHANGE -> {
                controller.reset()
                mutableSteps.value = 0
            }
            VolumeAdjustment.FAILED -> Unit
        }
    }

    /** 推奨音量の普段との比率。普段より上げることはしない。 */
    private fun scale(): Float = bgm.value.recommendedVolume?.let { (it / USUAL_VOLUME).coerceIn(0f, 1f) } ?: 1f

    private fun canAdjust(): Boolean {
        val current = playback.value ?: return false
        return bgm.value.enabled && connection.value.connected &&
            music.value.selectedDeviceId == SpotifyPhonePlayer.PHONE_DEVICE_ID &&
            current.playback.deviceId == SpotifyPhonePlayer.PHONE_DEVICE_ID && current.playback.playing &&
            nowMillis() - current.receivedAtMillis in 0..PLAYBACK_FRESHNESS_MILLIS
    }

    private fun suspendAdjustment() {
        if (adjusting) volume.end()
        adjusting = false
        controller.reset()
        mutableSteps.value = 0
    }

    private companion object {
        const val PLAYBACK_FRESHNESS_MILLIS = 15_000L
        /** bgm-api が普段（車内が normal で負荷も低いとき）に返す volume。 */
        const val USUAL_VOLUME = .6f
    }
}
