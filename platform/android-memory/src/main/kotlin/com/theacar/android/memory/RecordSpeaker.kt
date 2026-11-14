package com.theacar.android.memory

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * レコードを手に入れたことを短く読み上げる。作りは CabinNoticeSpeaker と同じ（音楽は下げて重ねる、案内の音声として流す）。
 * 続けて何枚か手に入れたときに前の読み上げを切らないよう、後ろに並べる（QUEUE_ADD）。
 * 曲がる案内（NavigationService）とは別の読み上げなので、ごくまれに重なることがある。道を走り切るのは道の終わりで、
 * 曲がる案内の直前とは限らないため、そのままにしている。
 */
class RecordSpeaker(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var count = 0

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) tts?.stop() }
        .build()

    private var tts: TextToSpeech? = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val language = tts?.setLanguage(Locale.JAPAN)
            ready = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }.also { speech ->
        speech.setAudioAttributes(attributes)
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { audioManager.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) { audioManager.abandonAudioFocusRequest(focus) }
        })
    }

    /** 読み上げられなくてもレコードは手に入っているので、失敗は飲み込む */
    fun speak(text: String) {
        if (!ready) return
        runCatching {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "record:${count++}")
        }
    }
}
