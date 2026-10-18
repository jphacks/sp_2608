package com.theacar.android.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 景観BGMのあいだ、画面を閉じても車内の雰囲気の判定を続けるための前景サービス。
 * Android ではバックグラウンドの収音に microphone 種別の前景サービスが要る。
 * 推薦が止まったら自分で終了する。
 */
class CabinListeningService : Service() {
    private val runtime get() = (application as AndroidMusicOwner).spotifyRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "車内の雰囲気の判定", NotificationManager.IMPORTANCE_LOW))
        val notification = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(ID, notification)
        } catch (_: RuntimeException) {
            // 権限が無い・前景から開始していないときは、判定なし（normal）で推薦だけ続ける。
            stopSelf()
            return
        }
        runtime.startCabinListening()
        scope.launch {
            runtime.bgmMusic.state.map { it.enabled }.distinctUntilChanged().collect { if (!it) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runtime.stopCabinListening()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, SpotifyMusicActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("旅れこ 景観BGM")
            .setContentText("会話の量だけを端末内で判定しています。音声は保存・送信しません。")
            .setContentIntent(pending).setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL = "cabin"
        private const val ID = 1002

        /** 前景の画面から呼ぶ。マイクの許可があるときだけ開始する。 */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, CabinListeningService::class.java))
            } catch (_: IllegalStateException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
