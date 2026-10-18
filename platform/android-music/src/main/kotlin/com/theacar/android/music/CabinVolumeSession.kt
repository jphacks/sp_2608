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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** このスマホでBGMを再生している間だけ、会話に合わせて端末の音量を調整する。 */
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
            combine(bgm, music, playback, cabin, connection) { _, _, _, _, _ -> canAdjust() }
                .distinctUntilChanged().collect { if (!it) suspendAdjustment() }
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
        if (!adjusting) {
            volume.begin()
            adjusting = true
        }
        when (volume.apply(controller.observe(nowMillis(), second))) {
            VolumeAdjustment.APPLIED -> mutableSteps.value = volume.steps
            VolumeAdjustment.MANUAL_CHANGE -> {
                controller.reset()
                mutableSteps.value = 0
            }
            VolumeAdjustment.FAILED -> Unit
        }
        onObservation(second, controller.baselineSnrDb, mutableSteps.value)
    }

    private fun canAdjust(): Boolean {
        val current = playback.value ?: return false
        return bgm.value.enabled && connection.value.connected && cabin.value.listening == CabinListening.ACTIVE &&
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

    private companion object { const val PLAYBACK_FRESHNESS_MILLIS = 15_000L }
}
