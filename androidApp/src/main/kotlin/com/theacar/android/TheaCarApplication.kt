package com.theacar.android

import android.app.Application
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.theacar.android.memory.AndroidMemoryOwner
import com.theacar.android.memory.AndroidMemoryRuntime
import com.theacar.android.memory.TripRecorder
import com.theacar.android.memory.RecordSpeaker
import com.theacar.android.memory.RoadRecordStore
import com.theacar.android.memory.RoadRecordWatcher
import com.theacar.memory.records.RecordSong
import kotlinx.coroutines.delay
import com.theacar.memory.records.RoadShape
import com.theacar.android.memory.RecordCelebrationActivity
import com.theacar.android.memory.RoadRecordOwner
import com.theacar.android.memory.MemoryActivity
import com.theacar.android.navigation.NavigationTripRecording
import com.theacar.android.music.DriveBgmLine
import com.theacar.android.music.driveBgmLine
import com.theacar.android.music.installId
import com.theacar.android.music.perform
import com.theacar.navigation.NavigationPhase
import com.theacar.android.music.SpotifyMusicActivity
import com.theacar.android.navigation.MiniPlayerSource
import com.theacar.android.navigation.MiniPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.theacar.app.AppGraph
import com.theacar.cabin.CabinNoticeOwner
import com.theacar.cabin.CabinNoticeSource
import com.theacar.dashboard.TripRecord
import com.theacar.drive.DriveSessionOwner
import com.theacar.drive.NavigationDrivingConditions
import com.theacar.drive.NavigationMusicJourney
import com.theacar.drive.NavigationLiveRoute
import com.theacar.drive.NavigationSceneryTimeline
import com.theacar.android.navigation.AndroidNavigationOwner
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.android.navigation.NavigationCompanion
import com.theacar.android.navigation.NavigationCompanionOwner
import com.theacar.android.music.AndroidMusicOwner
import com.theacar.android.music.AndroidMicrophone
import com.theacar.android.music.AndroidSpotifyRuntime
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.SharedSensorFeed
import com.theacar.android.window.AndroidWindowOwner
import com.theacar.android.window.AndroidWindowRuntime
import com.theacar.android.window.CabinNoticeSpeaker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.theacar.android.navigation.MiniPlayerSongPosition
import com.theacar.music.NowPlaying
import android.os.SystemClock

class TheaCarApplication : Application(), DriveSessionOwner, AndroidNavigationOwner, AndroidMusicOwner, AndroidWindowOwner,
    NavigationCompanionOwner, CabinNoticeOwner, AndroidMemoryOwner, RoadRecordOwner {
    override lateinit var spotifyRuntime: AndroidSpotifyRuntime
        private set
    override lateinit var memoryRuntime: AndroidMemoryRuntime
        private set
    override lateinit var windowRuntime: AndroidWindowRuntime
        private set
    override lateinit var navigationRuntime: AndroidNavigationRuntime
        private set
    override lateinit var tripRecording: NavigationTripRecording
        private set
    lateinit var graph: AppGraph
        private set

    /** 名のある道を走り切って手に入れたレコード。RECORD タブと着いたときの演出が読む */
    override lateinit var roadRecords: RoadRecordStore

    /** レコードの道の形（アプリに入れた GeoJSON）。判定と「この道へ行く」の始点に使う */
    val roadShapes: List<RoadShape> by lazy { RoadRecordStore.loadShapes(this) }

    private var recordSpeaker: RecordSpeaker? = null

    /** レコードを手に入れたことを読み上げる。音声案内をオフにしているときは黙る */
    fun announceRecord(text: String) {
        if (navigationRuntime.voiceEnabled.value) recordSpeaker?.speak(text)
    }

    /** 新しいレコードの演出を見せる。走行中は見せず、着いたときかアプリを開いたときに見せる */
    fun showPendingRecords(context: Context) {
        if (roadRecords.book.value.pendingCelebration.isEmpty() || RecordCelebrationActivity.showing) return
        if (navigationRuntime.session.state.value.phase in setOf(NavigationPhase.GUIDING, NavigationPhase.STARTING)) return
        runCatching {
            context.startActivity(RecordCelebrationActivity.intent(context).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { Log.w("RoadRecords", "レコードの演出を開けませんでした", it) }
    }
    override var navigationCompanion: NavigationCompanion? = null
        private set
    /** 端末のマイク。収音は1回にまとめ、購読する機能（今は景観BGMの車内判定）がいる間だけ動く。 */
    lateinit var microphone: SharedSensorFeed<AudioFrame>
        private set

    /** 換気の知らせの配信元。スマホの画面と車載画面が同じものを受け取る。 */
    override val cabinNotices: CabinNoticeSource get() = windowRuntime

    private var speaker: CabinNoticeSpeaker? = null
    private val scope = MainScope()

    override val driveSession get() = graph.driveSession
    override val miniPlayer: MiniPlayerSource by lazy {
        // 地図画面のミニプレーヤー。Spotify の実際の再生（景観BGM・音楽画面で選んだ曲のどちらも）と、
        // 選曲の理由・車内の様子をまとめて出す。再生状態は、このバーが表示されている間だけ取得する。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val music = spotifyRuntime
        val link = music.driveBgm
        val guiding = navigationRuntime.session.state.map { it.phase == NavigationPhase.GUIDING }.distinctUntilChanged()
        val line = combine(music.connection, music.displayPlayback, link.state, guiding) { connection, now, bgm, isGuiding ->
            driveBgmLine(bgm, now, connection.connected, isGuiding) to now
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000),
            driveBgmLine(link.state.value, null, music.connection.value.connected, false) to null)
        fun state(pair: Pair<DriveBgmLine, NowPlaying?>) = pair.let { (it, now) ->
            MiniPlayerState(it.title, it.playing, it.subtitle, it.actionLabel, it.indicator,
                actionReplacesPlay = it.actionReplacesPlay, progress = now?.progress(),
                scene = it.scene, requested = it.requested)
        }
        val mapped = line.map(::state).stateIn(scope, SharingStarted.WhileSubscribed(5_000), state(line.value))
        object : MiniPlayerSource {
            override val state = mapped
            override fun clock() = SystemClock.elapsedRealtime()
            override fun toggle() { music.togglePlayback() }
            override fun open(context: Context) {
                context.startActivity(Intent(context, SpotifyMusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            override fun action(context: Context) { line.value.first.action?.let { link.perform(it, context) } }
        }
    }
    override val isDemo get() = graph.demoControls != null

    /** 一覧には記録のメタデータだけを持ち、写真は表示するカードから読む。 */
    val tripRecords: StateFlow<List<TripRecord>> by lazy {
        memoryRuntime.store.memories.map { list -> list.map { it.toTripRecord() } }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.demo()
        memoryRuntime = AndroidMemoryRuntime(this, installId(this))
        navigationRuntime = AndroidNavigationRuntime(this)
        val recorder = TripRecorder(navigationRuntime.session.state, navigationRuntime.locations,
            memoryRuntime.store::save, scope, onError = { Log.w("TripRecorder", "旅の記録を保存できませんでした", it) })
        tripRecording = object : NavigationTripRecording {
            override val lastSavedId = recorder.lastSavedId
            override val saveFailed = recorder.saveFailed
            override fun retry() = recorder.retry()
            override fun open(context: Context, id: String) { context.startActivity(MemoryActivity.intent(context, id)) }
        }
        val musicJourney = NavigationMusicJourney { navigationRuntime.session.state.value }
        val scenery = NavigationSceneryTimeline { navigationRuntime.session.state.value }
        val drivingConditions = NavigationDrivingConditions { navigationRuntime.session.state.value }
        // 景観BGMは経路 API と同じサーバー・同じトークンで呼ぶ（端末で保存したトークンがあればそれを使う）。
        microphone = SharedSensorFeed(AndroidMicrophone(this), scope, capacity = 50)
        val liveRoute = NavigationLiveRoute { navigationRuntime.session.state.value }
        // ライブ表示は旅の記録と同じ Web 版の送り先・トークンを使う
        @Suppress("DEPRECATION")
        val metadata = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
        spotifyRuntime = AndroidSpotifyRuntime(this, navigationRuntime.locations, microphone, musicJourney::current, scenery::current,
            drivingConditions::current, navigationRuntime.configuration.routingUrl, { navigationRuntime.routingToken.value }, liveRoute::current,
            metadata?.getString("com.theacar.MEMORY_WEB_URL").orEmpty(), metadata?.getString("com.theacar.MEMORY_WEB_TOKEN").orEmpty().trim())
        windowRuntime = AndroidWindowRuntime(this)
        navigationCompanion = NavigationMusicCompanion(spotifyRuntime.driveBgm, navigationRuntime.session.state, MainScope())
        // 読み上げは画面に依存しない。Android Auto でも、アプリを見ていないときでも鳴る。
        // 読み上げを用意できない端末でもアプリは動かす。ここで投げるとアプリ全体が起動できなくなり、
        // Android Auto の一覧からも消えてしまうため、必ず飲み込む。
        speaker = runCatching { CabinNoticeSpeaker(this) }.getOrNull()
        speaker?.let { speech -> scope.launch { windowRuntime.notices.collect(speech::speak) } }
        // 名のある道を走り切ったらレコードを手に入れる。曲は景観BGMなど、そのとき流れていた曲を刻む
        roadRecords = RoadRecordStore(this)
        recordSpeaker = runCatching { RecordSpeaker(this) }.getOrNull()
        RoadRecordWatcher(navigationRuntime.session.state, navigationRuntime.locations, roadRecords,
            shapes = { roadShapes },
            currentSong = {
                spotifyRuntime.displayPlayback.value?.playback?.takeIf { it.playing }?.track
                    ?.let { RecordSong(it.uri, it.title, it.artist, it.url) }
            },
            announce = ::announceRecord,
            scope = scope,
            onError = { Log.w("RoadRecordWatcher", "レコードを保存できませんでした", it) })
        // 着いたときに見せられなかったレコード（Android Auto だけで走ったときなど）は、アプリのどの画面を開いても見せる
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity !is RecordCelebrationActivity) showPendingRecords(activity)
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // 着いたら、少し間を置いて（到着の案内を先に見せてから）新しいレコードの演出を出す
        scope.launch {
            navigationRuntime.session.state.map { it.phase == NavigationPhase.ARRIVED }.distinctUntilChanged().collect { arrived ->
                if (arrived) { delay(1_500); showPendingRecords(this@TheaCarApplication) }
            }
        }
    }
}

/** ミニプレーヤーの再生位置。曲の長さと位置の両方が分かるときだけ。 */
private fun NowPlaying.progress(): MiniPlayerSongPosition? {
    val duration = playback.track?.durationMillis ?: return null
    val position = playback.positionMillis ?: return null
    return MiniPlayerSongPosition(position.toLong(), duration.toLong(), receivedAtMillis, playback.playing)
}
