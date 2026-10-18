package com.theacar.android.music

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Keeps the phone relay running while the DHU is displayed and the phone is locked. */
class MusicRequestService : Service() {
    private val runtime get() = (application as AndroidMusicOwner).spotifyRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundReady = false
    private var stateJob: Job? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("music-requests", "曲のリクエスト受付", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SpotifyMusicActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MusicRequestService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "music-requests").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TheaCar 曲のリクエスト受付中").setContentText("同乗者のリクエストをSpotifyへ送信します。")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "受付終了", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1003, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1003, notification)
            foregroundReady = true
        } catch (_: RuntimeException) { runtime.requests.stop(); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "STOP") { runtime.requests.stop(); stopSelf(); return START_NOT_STICKY }
        val url = intent?.getStringExtra("url")
        val device = intent?.getStringExtra("device")
        if (url != null && device != null) runtime.requests.start(url, device)
        else { stopSelf(); return START_NOT_STICKY }
        stateJob?.cancel()
        stateJob = scope.launch {
            runtime.requests.state.map { it.active || it.busy }.distinctUntilChanged().collect { if (!it) stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (runtime.requests.state.value.let { it.active || it.busy }) runtime.requests.stop()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
