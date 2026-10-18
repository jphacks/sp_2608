package com.theacar.android.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.theacar.music.MusicFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** QR受付と予約曲の再生を、画面を閉じた後も継続する。 */
class GuestRequestService : Service() {
    private val runtime get() = (application as AndroidMusicOwner).spotifyRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundReady = false
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "曲のリクエスト受付", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SpotifyMusicActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, GuestRequestService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TheaCar リクエスト受付中")
            .setContentText("QRから届いた曲を車内で再生します。")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "受付終了", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1004, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1004, notification)
            foregroundReady = true
        } catch (_: RuntimeException) {
            runtime.guests.reportFailure(MusicFailure.UNKNOWN)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady || intent?.action == STOP) {
            if (intent?.action == STOP) runtime.closeGuestRequests()
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.beginGuestReception()
        stateJob?.cancel()
        stateJob = scope.launch {
            runtime.guests.state.map { it.opening || it.code != null }.distinctUntilChanged().collect {
                if (!it) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (stateJob != null) {
            if (runtime.guests.state.value.let { it.opening || it.code != null }) runtime.closeGuestRequests()
            else runtime.stopGuestPlayback()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "guest-requests"
        const val STOP = "STOP_GUEST_REQUESTS"
    }
}
