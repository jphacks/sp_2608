package com.theacar.android.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.os.SystemClock
import android.util.Log
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.InputStatus
import com.theacar.sensing.SensorSample
import com.theacar.sensing.SensorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** 車内の収音・声の大きさ・音量の確認用ログ。`adb logcat -s TheaCarCabin` で見る。音声そのものは出さない。 */
internal const val CABIN_LOG_TAG = "TheaCarCabin"

/**
 * 端末のマイクを、20ms ごとのモノラル音声として [SensorSource] で配る。
 * 購読しているあいだだけ収音する。PCM は保存も送信もしない。
 * バックグラウンドで収音を続けるには [CabinListeningService] の前景サービスが要る。
 */
class AndroidMicrophone(private val context: Context) : SensorSource<AudioFrame> {
    private val mutableStatus = MutableStateFlow<InputStatus>(InputStatus.Idle)
    override val status = mutableStatus.asStateFlow()

    @SuppressLint("MissingPermission")
    override val samples = flow {
        mutableStatus.value = InputStatus.Idle
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableStatus.value = InputStatus.PermissionRequired
            return@flow
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            mutableStatus.value = InputStatus.Unavailable
            return@flow
        }
        val record = try {
            // 端末の再生音を参照にエコーキャンセルがかかる音源にして、自分で流している曲の歌声を会話と取り違えにくくする。
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, SAMPLE_RATE / 5 * 2))
        } catch (_: SecurityException) {
            mutableStatus.value = InputStatus.PermissionRequired
            return@flow
        } catch (_: IllegalArgumentException) {
            mutableStatus.value = InputStatus.Unavailable
            return@flow
        }
        val effects = attachEffects(record.audioSessionId)
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                mutableStatus.value = InputStatus.Unavailable
                return@flow
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                mutableStatus.value = InputStatus.Unavailable
                return@flow
            }
            mutableStatus.value = InputStatus.Active
            val buffer = ShortArray(SAMPLE_RATE / 50)
            var sequence = 0L
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    mutableStatus.value = InputStatus.Unavailable
                    return@flow
                }
                if (read == 0) continue
                val pcm = FloatArray(read) { buffer[it] / 32_768f }
                emit(SensorSample(sequence++, SystemClock.elapsedRealtime(), AudioFrame.fromPcm(pcm, SAMPLE_RATE)))
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { it.release() }
            record.release()
            if (mutableStatus.value == InputStatus.Active) mutableStatus.value = InputStatus.Idle
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 端末が対応していればエコーキャンセルを有効にし、自動音量調整は切る。
     * 自動音量調整がかかると、ゆっくり追従する雑音レベルとの比較が崩れ、声の大きさも測れなくなるため。
     * 対応していない端末では何もせず、音源側の処理だけで判定する。
     */
    private fun attachEffects(session: Int): List<AudioEffect> = buildList {
        val echo = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(session)?.also { runCatching { it.enabled = true }; add(it) }
        } else null
        val gain = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(session)?.also { runCatching { it.enabled = false }; add(it) }
        } else null
        Log.i(CABIN_LOG_TAG, "effects aec=${echo.describe(AcousticEchoCanceler.isAvailable())} " +
            "agc=${gain.describe(AutomaticGainControl.isAvailable())}")
    }

    /** 実機の確認用。端末が対応しているか、実際に有効になったかを出す。 */
    private fun AudioEffect?.describe(available: Boolean) = when {
        !available -> "unavailable"
        this == null -> "create-failed"
        else -> if (runCatching { enabled }.getOrDefault(false)) "on" else "off"
    }

    private companion object { const val SAMPLE_RATE = 16_000 }
}
