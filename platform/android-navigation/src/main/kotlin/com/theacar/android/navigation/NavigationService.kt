package com.theacar.android.navigation

import android.app.Notification
import android.app.NotificationChannel
import android.content.ComponentName
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.theacar.navigation.NavigationPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class NavigationService : Service() {
    private val runtime get() = (application as AndroidNavigationOwner).navigationRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private val spokenCues = mutableSetOf<String>()
    private var attemptedStart = false
    /** 車載画面へ知らせた曲がり角。同じ曲がり角では鳴らし直さない。 */
    private var notifiedManeuver: String? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) tts?.stop()
            }.build()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("navigation", "ドライブ案内", NotificationManager.IMPORTANCE_LOW))
        startForeground(1001, notification("案内を開始しています"))
        runtime.locations.acquire(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.JAPAN)
                speechReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }.also { speech ->
            speech.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
            speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { audioManager.abandonAudioFocusRequest(focus) }
                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) { audioManager.abandonAudioFocusRequest(focus) }
            })
        }
        scope.launch {
            combine(runtime.session.state, runtime.voiceEnabled) { state, voice -> state to voice }.collect { (state, voice) ->
                if (state.phase in setOf(NavigationPhase.IDLE, NavigationPhase.ARRIVED) ||
                    (attemptedStart && state.phase == NavigationPhase.PREVIEW)) { stopSelf(); return@collect }
                if (!voice || state.maneuver == null || state.rerouting || state.failure != null) {
                    tts?.stop()
                    audioManager.abandonAudioFocusRequest(focus)
                }
                val maneuver = state.maneuver
                if (voice && speechReady && maneuver != null && !state.rerouting && state.failure == null) {
                    val stage = when {
                        maneuver.distanceMeters <= 50 -> "now"
                        maneuver.distanceMeters <= 300 -> "soon"
                        else -> null
                    }
                    val cue = "${state.sessionId}:${state.routeRevision}:${maneuver.stepIndex}:$stage"
                    if (stage != null && cue !in spokenCues && audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        spokenCues.add(cue)
                        val text = if (stage == "soon") "およそ${maneuver.distanceMeters}メートル先、${maneuver.voiceInstruction}" else maneuver.voiceInstruction
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, cue)
                    }
                }
                val turn = maneuver?.takeIf { !state.rerouting }
                val key = turn?.let { "${state.sessionId}:${state.routeRevision}:${it.stepIndex}" }
                val alert = key != null && key != notifiedManeuver
                if (key != null) notifiedManeuver = key
                getSystemService(NotificationManager::class.java).notify(1001,
                    notification(if (state.rerouting) "ルートを再検索しています" else maneuver?.instruction ?: "旅れこで案内中",
                        turn?.let { distanceAhead(it.distanceMeters) }, alert))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attemptedStart = true
        runtime.session.start()
        if (runtime.session.state.value.phase == NavigationPhase.IDLE) stopSelf()
        return START_NOT_STICKY
    }

    /**
     * 案内の通知。車載画面では、別のアプリを見ている間も次の曲がり角を出す（NF-3）。
     * [alert] は曲がり角が変わったときだけ true にし、毎回は鳴らさない。
     */
    private fun notification(text: String, distance: String? = null, alert: Boolean = false): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, NavigationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // 車載画面で通知を押したら、スマホではなく車の旅れこを開く
        val carApp = CarPendingIntent.getCarApp(this, 0,
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(this, CAR_APP_SERVICE)), 0)
        val car = CarAppExtender.Builder()
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(distance ?: "旅れこ ナビ")
            .setContentText(text)
            .setContentIntent(carApp)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
            .build()
        return NotificationCompat.Builder(this, "navigation").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("旅れこ ナビ").setContentText(text).setContentIntent(pending).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION).setOnlyAlertOnce(!alert)
            .extend(car).build()
    }

    private fun distanceAhead(meters: Int): String =
        if (meters < 1000) "${meters}m先" else String.format(Locale.JAPAN, "%.1fkm先", meters / 1000.0)

    override fun onTaskRemoved(rootIntent: Intent?) { runtime.session.stop(); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        audioManager.abandonAudioFocusRequest(focus)
        runtime.locations.release(this)
        if (runtime.session.state.value.phase == NavigationPhase.GUIDING) runtime.session.stop()
        super.onDestroy()
    }

    private companion object {
        /** 車載画面のサービス。android-auto にあり、このモジュールからは名前でしか指せない。 */
        const val CAR_APP_SERVICE = "com.theacar.auto.TheaCarService"
    }
}
