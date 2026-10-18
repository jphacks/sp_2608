package com.theacar.android.music

import android.content.Context
import android.media.AudioManager

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
 * [CabinVolumeController][com.theacar.music.CabinVolumeController] の段数を、端末の音楽の音量へ反映する。
 * Spotify App Remote には端末の音量を変える手段が無いため、AudioManager で変える。
 *
 * 推薦を始めたときの音量を基準にして、そこから段数ぶん下げる。音を消し切らないよう 1 より下げない。
 * 最後に設定した値と違う音量になっていたら、ユーザーが手で変えたとみなしてその値を新しい基準にする。
 */
internal class AndroidMusicVolume(
    private val stream: StreamVolume,
    /** 実機の確認用。音量を変えたときと手動操作を見つけたときに呼ぶ。 */
    private val log: (String) -> Unit = {},
) {
    constructor(context: Context, log: (String) -> Unit) : this(MusicStreamVolume(context), log)

    private var base: Int? = null
    /** 最後に自分で設定した音量。まだ触っていなければ null。 */
    private var applied: Int? = null

    val steps: Int get() {
        val usual = base ?: return 0
        val set = applied ?: return 0
        return (usual - set).coerceAtLeast(0)
    }

    /** 推薦の開始。今の音量を基準として覚える。 */
    fun begin() {
        base = stream.current
        applied = null
        log("volume begin base=$base")
    }

    /**
     * 基準から [steps] 段下げた音量にする。
     * 手動変更は新しい基準として扱う。設定に失敗した値は反映済みにしない。
     */
    fun apply(steps: Int): VolumeAdjustment {
        val usual = base ?: return VolumeAdjustment.APPLIED
        val now = stream.current
        if (now != (applied ?: usual)) {
            log("volume manual ${applied ?: usual}->$now, new base=$now")
            base = now
            applied = null
            return VolumeAdjustment.MANUAL_CHANGE
        }
        val target = (usual - steps).coerceAtLeast(minOf(usual, 1))
        if (target != now) {
            if (!stream.set(target)) {
                log("volume failed $now->$target")
                return VolumeAdjustment.FAILED
            }
            applied = target
            log("volume set $now->$target (base=$usual steps=$steps)")
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
}
