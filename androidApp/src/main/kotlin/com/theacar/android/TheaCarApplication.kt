package com.theacar.android

import android.app.Application
import android.content.Context
import android.content.Intent
import com.theacar.android.memory.AndroidMemoryOwner
import com.theacar.android.memory.AndroidMemoryRuntime
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
import com.theacar.drive.NavigationMusicJourney
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

class TheaCarApplication : Application(), DriveSessionOwner, AndroidNavigationOwner, AndroidMusicOwner, AndroidWindowOwner,
    NavigationCompanionOwner, CabinNoticeOwner, AndroidMemoryOwner {
    override lateinit var spotifyRuntime: AndroidSpotifyRuntime
        private set
    override lateinit var memoryRuntime: AndroidMemoryRuntime
        private set
    override lateinit var windowRuntime: AndroidWindowRuntime
        private set
    override lateinit var navigationRuntime: AndroidNavigationRuntime
        private set
    lateinit var graph: AppGraph
        private set
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
            driveBgmLine(bgm, now, connection.connected, isGuiding)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000),
            driveBgmLine(link.state.value, null, music.connection.value.connected, false))
        val mapped = line.map { MiniPlayerState(it.title, it.playing, it.subtitle, it.actionLabel, it.indicator) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), line.value.let { MiniPlayerState(it.title, it.playing, it.subtitle, it.actionLabel, it.indicator) })
        object : MiniPlayerSource {
            override val state = mapped
            override fun toggle() { music.togglePlayback() }
            override fun open(context: Context) {
                context.startActivity(Intent(context, SpotifyMusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            override fun action(context: Context) { line.value.action?.let { link.perform(it, context) } }
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
        navigationRuntime = AndroidNavigationRuntime(this, memoryRuntime.store)
        val musicJourney = NavigationMusicJourney { navigationRuntime.session.state.value }
        val scenery = NavigationSceneryTimeline { navigationRuntime.session.state.value }
        // 景観BGMは経路 API と同じサーバー・同じトークンで呼ぶ（端末で保存したトークンがあればそれを使う）。
        microphone = SharedSensorFeed(AndroidMicrophone(this), scope, capacity = 50)
        spotifyRuntime = AndroidSpotifyRuntime(this, navigationRuntime.locations, microphone, musicJourney::current, scenery::current,
            navigationRuntime.configuration.routingUrl) { navigationRuntime.routingToken.value }
        windowRuntime = AndroidWindowRuntime(this)
        navigationCompanion = NavigationMusicCompanion(spotifyRuntime.driveBgm, spotifyRuntime, navigationRuntime.session.state, MainScope())
        // 読み上げは画面に依存しない。Android Auto でも、アプリを見ていないときでも鳴る。
        // 読み上げを用意できない端末でもアプリは動かす。ここで投げるとアプリ全体が起動できなくなり、
        // Android Auto の一覧からも消えてしまうため、必ず飲み込む。
        speaker = runCatching { CabinNoticeSpeaker(this) }.getOrNull()
        speaker?.let { speech -> scope.launch { windowRuntime.notices.collect(speech::speak) } }
    }
}
