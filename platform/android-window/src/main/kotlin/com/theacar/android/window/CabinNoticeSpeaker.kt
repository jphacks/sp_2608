package com.theacar.android.window

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.theacar.cabin.CabinNotice
import java.util.Locale

/**
 * 車内の知らせを読み上げる。
 *
 * やわらかく聞こえるように、既定より少し低く・ゆっくりにする。
 * 音楽が鳴っている最中でも重ねられるよう、音の権利は MAY_DUCK で借りる。Spotify は別のプロセスで
 * 鳴っているので、音量を下げるのは OS が受け持つ。`NavigationService` の読み上げと同じ作り。
 *
 * 用途を「案内の音声」にしているのは、Android Auto で車のスピーカーへ最も確実に流れる経路だから。
 * 意味としてはナビの案内ではないが、`USAGE_ASSISTANT` だと鳴らないヘッドユニットがある。
 */
class CabinNoticeSpeaker(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var lastSpoken = 0L

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) tts?.stop()
        }
        .build()

    private var tts: TextToSpeech? = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val language = tts?.setLanguage(Locale.JAPAN)
            ready = language != TextToSpeech.LANG_MISSING_DATA &&
                language != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }.also { speech ->
        speech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        speech.setPitch(SOFT_PITCH)
        speech.setSpeechRate(SOFT_RATE)
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focus)
            }

            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focus)
            }
        })
    }

    /**
     * 同じ知らせは読み上げない。音の権利が借りられないときは黙る（画面側の表示は残る）。
     * 読み上げられなくても知らせそのものは壊さないので、失敗は飲み込む。
     */
    fun speak(notice: CabinNotice) {
        if (!ready || notice.id <= lastSpoken) return
        runCatching {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
            lastSpoken = notice.id
            tts?.speak(notice.spoken, TextToSpeech.QUEUE_FLUSH, null, "cabin:${notice.id}")
        }
    }

    fun close() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
            tts = null
            audioManager.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        /** 既定より少し低く、少しゆっくり。やわらかく聞こえる。 */
        const val SOFT_PITCH = 0.95f
        const val SOFT_RATE = 0.9f
    }
}
