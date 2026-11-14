package com.theacar.android.music

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** 端末の音楽の音量（段数）。テストで差し替えられるよう AudioManager から切り離している。 */
internal interface StreamVolume {
    val current: Int
    fun set(volume: Int): Boolean
}

internal enum class VolumeAdjustment { APPLIED, MANUAL_CHANGE, FAILED }

private class MusicStreamVolume(context: Context) : StreamVolume {
    private val audio = context.getSystemService(AudioManager::class.java)
    override val current: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    // 音量バーは出さない。推薦中に何度も出ると運転の邪魔になるため。
    override fun set(volume: Int): Boolean = runCatching {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        current == volume
    }.getOrDefault(false)
}

/**
 * 推奨音量の比率と [CabinVolumeController][com.theacar.music.CabinVolumeController] の段数を、端末の音楽の音量へ反映する。
 * Spotify App Remote には端末の音量を変える手段が無いため、AudioManager で変える。
 *
 * 推薦を始めたときの音量を基準にして、比率を掛けてから段数ぶん下げる。音を消し切らないよう 1 より下げない。
 * 最後に設定した値と違う音量になっていたら、ユーザーが手で変えたとみなしてその値を新しい基準にする。
 * そのときの比率も覚え、以後はそこからの変化だけを掛ける。手で決めた音量より上げることはしない。
 */
internal class AndroidMusicVolume(
    private val stream: StreamVolume,
    /** 実機の確認用。音量を変えたときと手動操作を見つけたときに呼ぶ。 */
    private val log: (String) -> Unit = {},
) {
    constructor(context: Context, log: (String) -> Unit) : this(MusicStreamVolume(context), log)

    private var base: Int? = null
    /** [base] を決めたときの比率。手で変えた音量は、そのときの比率で聞きたい音量とみなす。 */
    private var reference = 1f
    /** 最後に自分で設定した音量。まだ触っていなければ null。 */
    private var applied: Int? = null

    /** 基準から下げている段数（推奨音量と会話の両方の分）。 */
    val steps: Int get() {
        val usual = base ?: return 0
        val set = applied ?: return 0
        return (usual - set).coerceAtLeast(0)
    }

    /** 推薦の開始。今の音量を基準として覚える。 */
    fun begin() {
        base = stream.current
        reference = 1f
        applied = null
        log("volume begin base=$base")
    }

    /**
     * 基準に [scale]（0..1）を掛け、そこから [steps] 段下げた音量にする。
     * 手動変更は新しい基準として扱う。設定に失敗した値は反映済みにしない。
     */
    fun apply(steps: Int, scale: Float = 1f): VolumeAdjustment {
        val usual = base ?: return VolumeAdjustment.APPLIED
        val now = stream.current
        val ratio = scale.coerceIn(MIN_SCALE, 1f)
        if (now != (applied ?: usual)) {
            log("volume manual ${applied ?: usual}->$now, new base=$now scale=$ratio")
            base = now
            reference = ratio
            applied = null
            return VolumeAdjustment.MANUAL_CHANGE
        }
        // API の volume は小数2桁なので、比率がちょうど .5 の境目に来やすい。浮動小数の誤差で下がらないよう少し足す。
        val scaled = (usual * (ratio / reference).coerceAtMost(1f) + ROUNDING_SLACK).roundToInt().coerceAtMost(usual)
        val target = (scaled - steps).coerceAtLeast(minOf(usual, 1))
        if (target != now) {
            if (!stream.set(target)) {
                log("volume failed $now->$target")
                return VolumeAdjustment.FAILED
            }
            applied = target
            log("volume set $now->$target (base=$usual scale=$scale steps=$steps)")
        }
        return VolumeAdjustment.APPLIED
    }

    /** 推薦の終了。手で変えられていなければ基準の音量に戻す。 */
    fun end() {
        val usual = base
        val set = applied
        if (usual != null && set != null && stream.current == set) {
            if (stream.set(usual)) log("volume restore $set->$usual")
            else log("volume restore failed $set->$usual")
        }
        base = null
        applied = null
    }

    private companion object {
        const val MIN_SCALE = .1f
        const val ROUNDING_SLACK = 1e-3f
    }
}
