package com.theacar.android.music

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.theacar.android.location.AndroidLocationSource
import com.theacar.bgm.BgmApiService
import com.theacar.bgm.bgmHttpClient
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.SharedSensorFeed
import com.theacar.weather.OpenMeteoWeatherSource
import com.theacar.weather.weatherHttpClient
import com.theacar.weather.WeatherRepository
import com.theacar.music.*
import com.theacar.spotify.SpotifyMusicService
import com.theacar.spotify.spotifyHttpClient
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

interface AndroidMusicOwner { val spotifyRuntime: AndroidSpotifyRuntime }

/**
 * 音楽まわりの部品を組み立て、部品をまたぐ操作（おまかせ再生と景観BGMの切り替え、ログアウト時の後始末など）だけを持つ。
 * 認証は [SpotifyAuthController]、再生状態の監視は [PlaybackMonitor]、景観BGMの実行は [BgmPlaybackLoop]、
 * 環境連動は [DriveEnvironmentController]、車内の発話判定は [CabinSpeechMonitor] が受け持つ。
 * マイクはアプリ全体で共有する [SharedSensorFeed] を受け取り、ここでは作らない。
 */
class AndroidSpotifyRuntime(
    context: Context,
    val locations: AndroidLocationSource,
    /** アプリ全体で共有するマイク入力。景観BGMの間だけ購読する。 */
    microphone: SharedSensorFeed<AudioFrame>,
    journey: () -> MusicJourney? = { null },
    private val scenery: () -> SceneryRoute? = { null },
    /** 経路 API のベース URL。景観BGMはその /bgm/ を同じトークンで呼ぶ。空なら景観BGMは使えない。 */
    routingUrl: String = "",
    private val routingToken: () -> String = { "" },
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = spotifyHttpClient()
    private val auth = SpotifyAuthController(applicationContext, scope, client)
    val connection = auth.connection
    /** この端末の Spotify アプリ。開かなくても裏で起動して再生する。 */
    internal val phone = SpotifyPhonePlayer(applicationContext, { auth.clientId }, SpotifyAuthSettings.REDIRECT_URI)
    private val spotify = SpotifyMusicService(client, auth.authorization, System::currentTimeMillis)
    private val service = PhoneAwareMusicService(spotify, phone) { message -> Log.i(LOG_TAG, message) }
    val session = MusicSession(service, scope)
    val requests = MusicRequests(service, scope,
        ready = { connection.value.connected && session.state.value.selectedDeviceId != null },
        beforeStart = { stopDriveMusic(); stopBgmRecommendation() })
    val weather = WeatherRepository(OpenMeteoWeatherSource(weatherHttpClient(), System::currentTimeMillis), System::currentTimeMillis)
    val driveMusic = DriveMusicSession(weather, service,
        { tracks, device ->
            val success = session.playMix(tracks, device)
            if (!success) throw MusicException(session.state.value.failure ?: MusicFailure.UNKNOWN)
            true
        }, scope, System::currentTimeMillis)
    private val environment = DriveEnvironmentController(
        locations, locations::acquire, locations::release, weather, driveMusic, scope,
        SystemClock::elapsedRealtime, { connection.value.connected }, { session.state.value }, journey,
    )
    private val routingBase = routingUrl.trim().trimEnd('/')
    private val bgmEndpoint = routingBase.takeIf { it.isNotEmpty() }?.let { "$it/bgm" }.orEmpty()
    val bgmConfigured = bgmEndpoint.isNotEmpty()
    private val bgmApi = BgmApiService(bgmEndpoint, bgmHttpClient(), routingToken)
    val playlists = PlaylistInputSession(spotify, bgmApi, scope)
    /** 案内中に流れた曲と車内の発話割合。案内の終わりに Web 版へ送る。 */
    private val musicLog = DriveMusicLog()
    private val musicLogUploader = androidMusicLogUploader(applicationContext)
    val bgmMusic = BgmMusicSession(bgmApi,
        service, scope, System::currentTimeMillis, listener = object : BgmPlaybackListener {
            override fun onPlayed(track: RecommendedTrack, nowMillis: Long) = musicLog.onTrackStarted(track, nowMillis)
            override fun onStopped(nowMillis: Long) = musicLog.onTrackStopped(nowMillis)
        })
    /** 同乗者のリクエスト。QR コードから開いたページで探した曲を、景観BGMの予約の列か「今すぐ」で流す。 */
    val guests = GuestRequestSession(bgmApi, bgmMusic, { request -> playRequest(request) { } }, ::sharedNowPlaying, scope)

    /** 同乗者が QR コードから開くページ。経路 API と同じ公開入口の /party/ に置く。 */
    fun guestUrl(code: String) = "$routingBase/party/$code"
    private val playback = PlaybackMonitor(service, scope, bgmMusic.state, { connection.value.connected }, SystemClock::elapsedRealtime)
    /** 景観BGMの推薦中だけ更新する。Spotify アプリを開かなくても、何が流れているかを示すため。 */
    val nowPlaying = playback.bgmPlayback
    private val cabinSpeech: CabinSpeechMonitor = CabinSpeechMonitor(microphone, scope, SystemClock::elapsedRealtime,
        onSecond = { second ->
            musicLog.onSecond(System.currentTimeMillis(), second.speaking)
            cabinVolume.observe(second)
        })
    val cabin = cabinSpeech.status
    private val cabinVolume: CabinVolumeSession = CabinVolumeSession(
        controller = if (BuildConfig.CABIN_QUICK_CHECK) {
            CabinVolumeController(windowMillis = 3_000, minSpeakingSeconds = 2, baselineSamples = 5,
                stepIntervalMillis = 2_000, restoreAfterMillis = 1_000)
        } else CabinVolumeController(),
        volume = AndroidMusicVolume(applicationContext) { message -> Log.i(CABIN_LOG_TAG, message) },
        scope = scope, bgm = bgmMusic.state, music = session.state, playback = playback.bgmPlayback,
        cabin = cabin, connection = connection, nowMillis = SystemClock::elapsedRealtime,
        onObservation = { second, baseline, steps ->
            if (BuildConfig.DEBUG) Log.d(CABIN_LOG_TAG,
                String.format(Locale.US, "second speaking=%b snr=%s noise=%.1f baseline=%.1f steps=%d",
                    second.speaking, second.snrDb?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
                    second.noiseDb, baseline, steps))
        },
    )
    val cabinVolumeSteps = cabinVolume.steps
    private val bgmLoop = BgmPlaybackLoop(bgmMusic, service, session, playback, scope, scenery, cabinSpeech::current,
        connected = { connection.value.connected }, onAuthorizationLost = { guests.close() }, onPlayback = ::logPlaybackChange)
    /** ナビの案内と景観BGMのつなぎ。ナビ画面から使う。 */
    val driveBgm by lazy { DriveBgmLink(driveBgmPort, SharedPreferencesDriveBgmSettings(applicationContext), scope) }
    private val driveBgmPort = object : DriveBgmPort {
        override val configured get() = bgmConfigured
        override val connection get() = this@AndroidSpotifyRuntime.connection
        override val music get() = session.state
        override val bgm get() = bgmMusic.state
        override val cabin get() = this@AndroidSpotifyRuntime.cabin
        override val phoneProblem get() = phone.problem
        override val spotifyInstalled get() = phone.installed
        override val playlists get() = this@AndroidSpotifyRuntime.playlists.state
        override val playback get() = displayPlayback
        override fun latestPlayback() = this@AndroidSpotifyRuntime.playback.latest()
        override fun refreshMusic() = session.refresh()
        override fun selectDevice(id: String) = session.selectDevice(id)
        override fun clearBgmFailure() = bgmMusic.clearFailure()
        override fun startBgm(members: List<MusicMember>) = startBgmRecommendation(members, awaitScenery = true)
        override fun stopBgm() = stopBgmRecommendation()
        override fun pause(deviceId: String) = pausePlayback(deviceId)
        override val microphoneGranted get() =
            applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        override fun startCabinListening() = CabinListeningService.start(applicationContext)
        override fun beginDriveLog() {
            musicLog.start(System.currentTimeMillis())
            scope.launch { musicLogUploader.retryPending() }
        }
        override fun finishDriveLog() {
            musicLog.finish(System.currentTimeMillis())?.let { record -> scope.launch { musicLogUploader.submit(record) } }
        }
    }
    val environmentCheck = environment.check

    fun inspectEnvironment() = environment.inspect()

    fun stopEnvironmentInspection() = environment.stopInspection()

    fun startDriveMusic() {
        if (!connection.value.connected) return
        stopBgmRecommendation()
        environment.startMusic()
    }

    fun stopDriveMusic() = environment.stopMusic()

    /** 景観BGMを開始していなくても、QRからのリクエストを受け付ける。 */
    fun openGuestRequests() {
        if (!connection.value.connected || !bgmConfigured || session.state.value.selectedDeviceId == null) return
        if (guests.state.value.let { it.opening || it.code != null }) return
        try {
            applicationContext.startForegroundService(Intent(applicationContext, GuestRequestService::class.java))
        } catch (_: RuntimeException) {
            guests.reportFailure(MusicFailure.UNKNOWN)
        }
    }

    internal fun beginGuestReception() {
        if (!connection.value.connected || !bgmConfigured || session.state.value.selectedDeviceId == null) return
        if (!bgmMusic.state.value.enabled) {
            startBgmRecommendation(listOf(MusicMember("host", "ホスト")), requestsOnly = true)
        }
        guests.open()
    }

    fun closeGuestRequests() {
        guests.close()
        stopGuestPlayback()
    }

    internal fun stopGuestPlayback() {
        if (bgmMusic.state.value.requestsOnly) haltBgm()
    }

    /**
     * bgm-api の推薦で1曲ずつ流す。おまかせ再生とは同時に動かさない。
     * 曲の候補と選曲方法は「みんなのプレイリスト」（[playlists]）の取込結果を使う。
     * リストを取り込んだときは、その所有者を同乗者とし、[members] は使わない。
     */
    fun startBgmRecommendation(
        members: List<MusicMember>,
        awaitScenery: Boolean = false,
        requestsOnly: Boolean = false,
    ) {
        if (!connection.value.connected || !bgmConfigured || members.isEmpty()) return
        val input = playlists.state.value
        if (!requestsOnly && input.importing) return
        if (!requestsOnly && input.mode == RecommendationMode.PLAYLIST && input.pool.isEmpty()) return
        stopDriveMusic()
        haltBgm()
        // 長時間運転と無音の長さは、推薦を始めた時点から数える。
        cabinSpeech.beginDrive()
        // 普段の声はドライブごとに学び直す。音量は推薦を始めたときの値を基準にする。
        cabinVolume.beginDrive()
        if (BuildConfig.CABIN_QUICK_CHECK) Log.w(CABIN_LOG_TAG, "quick-check mode: 確認用の短い時間で音量を制御します")
        if (requestsOnly) bgmLoop.start(members, emptyList(), RecommendationMode.BLEND, awaitScenery, requestsOnly = true)
        else bgmLoop.start(input.members.ifEmpty { members }, input.pool, input.mode, awaitScenery)
    }

    private var lastLogged: Pair<String?, Boolean>? = null

    /** 曲が変わったときと、再生・停止が切り替わったときだけ記録する（adb logcat -s TheaCarBgm で見る）。 */
    private fun logPlaybackChange(playback: MusicPlayback) {
        val key = playback.track?.uri to playback.playing
        if (key == lastLogged) return
        lastLogged = key
        val bgm = bgmMusic.state.value
        Log.i(LOG_TAG, "playback ${playback.track?.uri} playing=${playback.playing} pos=${playback.positionMillis}/" +
            "${playback.track?.durationMillis} device=${playback.deviceId?.let(::deviceLabel)} bgmCurrent=${bgm.playingUri} bgmNext=${bgm.upNextUri}")
    }

    /** 画面に出す再生状態。景観BGM以外で流している曲も含む。 */
    val displayPlayback: StateFlow<NowPlaying?> get() = playback.display

    /** 再生中なら一時停止、止まっていれば再開する。景観BGMは一時停止の間、次の曲を選ばずに待つ。 */
    fun togglePlayback() {
        val current = displayPlayback.value?.playback
        if (current?.playing == true) pausePlayback(current.deviceId) else resumePlayback(current?.deviceId)
    }

    fun pausePlayback(deviceHint: String? = null) = control(deviceHint) { service.pause(it) }

    /** 景観BGMの間に音楽画面で選んだ曲を、すぐ再生する。 */
    fun playRequest(track: MusicTrack, onResult: (MusicFailure?) -> Unit) = playRequest(track.ownRequest(), onResult)

    /** 景観BGMの間に音楽画面で選んだ曲を、予約の列に入れる。今の曲の終わりで流れる。 */
    fun enqueueRequest(track: MusicTrack) = bgmMusic.enqueue(track.ownRequest())

    private fun MusicTrack.ownRequest() = SongRequest("me:$uri:${System.currentTimeMillis()}", uri, title, artist, durationMillis)

    /** 同乗者の画面に出す「いま流れている曲」。 */
    private fun sharedNowPlaying(): SharedTrack? {
        val track = playback.latest()?.playback?.track ?: return null
        val request = bgmMusic.state.value.playingRequest?.takeIf { it.uri == track.uri }
        return SharedTrack(track.title, track.artist, request?.requester, request != null)
    }

    /**
     * リクエスト曲を、すぐ再生する。景観BGMは止めず、その曲が終わったら予約の続きか景観BGMの曲を流す。
     * 結果（失敗ならその理由）は再生先が受け付けてから返す。
     */
    fun playRequest(track: SongRequest, onResult: (MusicFailure?) -> Unit) {
        val device = playback.latest()?.playback?.deviceId ?: session.state.value.selectedDeviceId
        if (device == null || !connection.value.connected) {
            Log.w(LOG_TAG, "request skipped: no device (connected=${connection.value.connected})")
            onResult(MusicFailure.NO_DEVICE)
            return
        }
        scope.launch {
            try {
                service.play(track.uri, device)
                bgmMusic.playRequest(track)
                Log.i(LOG_TAG, "request ${track.uri} on ${deviceLabel(device)}")
                onResult(null)
            } catch (error: MusicException) {
                Log.w(LOG_TAG, "request failed ${track.uri} on ${deviceLabel(device)}: ${error.failure}")
                onResult(error.failure)
            }
            playback.requestRefresh()
        }
    }

    private fun deviceLabel(id: String) = if (id == SpotifyPhonePlayer.PHONE_DEVICE_ID) "this-phone(app-remote)"
        else "connect-device ${session.state.value.devices.firstOrNull { it.id == id }?.name ?: "?"} id=${id.take(8)}"

    fun resumePlayback(deviceHint: String? = null) = control(deviceHint) { service.play(null, it) }

    private fun control(deviceHint: String?, action: suspend (String) -> Unit) {
        val device = deviceHint ?: session.state.value.selectedDeviceId ?: return
        if (!connection.value.connected) return
        scope.launch {
            try {
                action(device)
            } catch (_: MusicException) {
                playback.requestRefresh()
                return@launch
            }
            playback.afterControl()
        }
    }

    /** 景観BGMを終える。同乗者のリクエストの受付も閉じる（予約の列は残す）。 */
    fun stopBgmRecommendation() {
        haltBgm()
        guests.close()
    }

    private fun haltBgm() {
        bgmLoop.halt()
        stopCabinListening()
        cabinVolume.stop()
    }

    /** [CabinListeningService] が前景になってから呼ぶ。マイクの許可が無ければ normal のまま推薦する。 */
    fun startCabinListening() {
        if (bgmMusic.state.value.enabled) cabinSpeech.start()
    }

    fun stopCabinListening() = cabinSpeech.stop()

    init {
        auth.restore()
        scope.launch {
            session.state.collect {
                if (it.failure == MusicFailure.AUTHORIZATION) {
                    stopBgmRecommendation()
                    stopDriveMusic()
                    auth.signedOut(MusicFailure.AUTHORIZATION)
                }
            }
        }
    }

    /** ダッシュボードで作成したアプリIDを端末へ保存する。再ビルドは不要。 */
    fun saveClientId(value: String) {
        if (!auth.accepts(value)) return
        playlists.clear()
        stopBgmRecommendation()
        stopDriveMusic()
        // 別のアプリIDで取得した資格情報は使わない。
        auth.saveClientId(value) {
            session.reset()
            driveMusic.reset()
            bgmMusic.reset()
        }
    }

    suspend fun loginUrl(): String? = auth.loginUrl()

    fun finishLogin(uri: Uri?) = auth.finishLogin(uri) { session.refresh() }

    fun disconnect() {
        playlists.clear()
        requests.stop()
        phone.disconnect()
        stopBgmRecommendation()
        bgmMusic.reset()
        stopDriveMusic()
        driveMusic.reset()
        auth.disconnect { session.reset() }
    }

    companion object {
        private const val LOG_TAG = "TheaCarBgm"
    }
}
