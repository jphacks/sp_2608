package com.theacar.android.music

import android.Manifest
import android.content.Context
import com.theacar.music.CabinStatus
import com.theacar.music.BgmMusicState
import com.theacar.music.MusicDevice
import com.theacar.music.MusicMember
import com.theacar.music.MusicState
import com.theacar.music.NowPlaying
import com.theacar.music.PlaylistInputState
import com.theacar.music.RecommendationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class DriveBgmReadiness { UNAVAILABLE, LOGIN_REQUIRED, NO_DEVICE, READY, PLAYLIST_REQUIRED, PREPARING_PLAYLIST }

data class DriveBgmState(
    /** 案内開始と同時に流すか。利用者の選択として保存する。 */
    val wanted: Boolean,
    val readiness: DriveBgmReadiness,
    val deviceName: String?,
    val checking: Boolean,
    val bgm: BgmMusicState,
    val cabin: CabinStatus,
    /** この端末の Spotify アプリを操作できない理由。 */
    val phoneProblem: PhonePlayerProblem? = null,
    val spotifyInstalled: Boolean = true,
)

/** [DriveBgmLink] が音楽側に求める状態と操作。[AndroidSpotifyRuntime] が実装を渡す。 */
internal interface DriveBgmPort {
    /** 景観BGMのサーバーが設定されているか。 */
    val configured: Boolean
    val connection: StateFlow<SpotifyConnectionState>
    val music: StateFlow<MusicState>
    val bgm: StateFlow<BgmMusicState>
    val cabin: StateFlow<CabinStatus>
    val phoneProblem: StateFlow<PhonePlayerProblem?>
    val spotifyInstalled: Boolean
    val playlists: StateFlow<PlaylistInputState>
    /** 画面に出す再生状態。景観BGM以外で流している曲も含む。 */
    val playback: StateFlow<NowPlaying?>
    fun latestPlayback(): NowPlaying?
    fun refreshMusic()
    fun selectDevice(id: String)
    fun clearBgmFailure()
    /** 景観が届くのを少し待ってから、景観BGMを始める。 */
    fun startBgm(members: List<MusicMember>)
    fun stopBgm()
    fun pause(deviceId: String)
    val microphoneGranted: Boolean
    /** 車内の雰囲気の判定を前景サービスで始める。 */
    fun startCabinListening()
    /** 旅の音楽の記録を始める。送れていない前回までの記録も再送する。 */
    fun beginDriveLog()
    /** 旅の音楽の記録を締めて Web 版へ送る。 */
    fun finishDriveLog()
}

/** 利用者の選択の保存先。 */
internal interface DriveBgmSettings {
    var wanted: Boolean
    var device: String?
    var members: String?
    var microphoneAsked: Boolean
}

internal class SharedPreferencesDriveBgmSettings(context: Context) : DriveBgmSettings {
    private val preferences = context.getSharedPreferences("drive-bgm", Context.MODE_PRIVATE)
    override var wanted: Boolean
        get() = preferences.getBoolean(WANTED, true)
        set(value) { preferences.edit().putBoolean(WANTED, value).apply() }
    override var device: String?
        get() = preferences.getString(DEVICE, null)
        set(value) { preferences.edit().putString(DEVICE, value).apply() }
    override var members: String?
        get() = preferences.getString(MEMBERS, null)
        set(value) { preferences.edit().putString(MEMBERS, value).apply() }
    override var microphoneAsked: Boolean
        get() = preferences.getBoolean(MIC_ASKED, false)
        set(value) { preferences.edit().putBoolean(MIC_ASKED, value).apply() }

    private companion object {
        const val WANTED = "wanted"
        const val DEVICE = "device"
        const val MEMBERS = "members"
        const val MIC_ASKED = "mic-asked"
    }
}

/**
 * ナビの案内と景観BGMをつなぐ。ナビの型には依存せず、案内の開始・終了だけを受け取る。
 *
 * 推薦は案内が始まってから開始する。開始前はルートの景観が無く、最初の1曲が景観なしで選ばれてしまうため。
 * ナビから始めた推薦だけを案内の終了で止める。音楽画面で始めた推薦には触れない。
 */
class DriveBgmLink internal constructor(
    private val port: DriveBgmPort,
    private val settings: DriveBgmSettings,
    private val scope: CoroutineScope,
) {
    private val wanted = MutableStateFlow(settings.wanted)
    private var guiding = false
    private var startedHere = false
    private var startJob: Job? = null

    val state: StateFlow<DriveBgmState> = combine(combine(wanted, port.connection, port.music, port.bgm,
        port.cabin) { wanted, connection, music, bgm, cabin ->
        val device = pick(music.devices, music.selectedDeviceId)
        val readiness = when {
            !port.configured -> DriveBgmReadiness.UNAVAILABLE
            !connection.configured || !connection.connected -> DriveBgmReadiness.LOGIN_REQUIRED
            device == null -> DriveBgmReadiness.NO_DEVICE
            else -> DriveBgmReadiness.READY
        }
        DriveBgmState(wanted, readiness, device?.name, connection.loading || music.busy, bgm, cabin)
    }, port.phoneProblem, port.playlists) { state, problem, input ->
        val readiness = when {
            state.readiness in setOf(DriveBgmReadiness.UNAVAILABLE, DriveBgmReadiness.LOGIN_REQUIRED) -> state.readiness
            input.importing -> DriveBgmReadiness.PREPARING_PLAYLIST
            input.mode == RecommendationMode.PLAYLIST && input.pool.isEmpty() -> DriveBgmReadiness.PLAYLIST_REQUIRED
            else -> state.readiness
        }
        state.copy(readiness = readiness, phoneProblem = problem, spotifyInstalled = port.spotifyInstalled)
    }.stateIn(scope, SharingStarted.Eagerly, DriveBgmState(wanted.value, DriveBgmReadiness.UNAVAILABLE, null, true,
        port.bgm.value, port.cabin.value))

    init {
        // 実際に流せた再生先を、次のドライブの既定にする。
        scope.launch {
            port.bgm.map { it.playedCount }.distinctUntilChanged().collect { count ->
                if (count > 0) port.music.value.selectedDeviceId?.let(::rememberDevice)
            }
        }
    }

    /** 画面に出す実際の再生状態。景観BGM以外で流している曲も含む。 */
    val playback get() = port.playback

    fun setWanted(value: Boolean) {
        wanted.value = value
        settings.wanted = value
        if (guiding) { if (value) begin() else stop() }
    }

    fun rememberDevice(id: String) { settings.device = id }

    /** 同乗者の名前。演出の一言（「Aさんのリストから」）に使う。 */
    fun memberNames(): String = settings.members ?: "自分"

    fun saveMemberNames(names: String) { settings.members = names }

    fun members() = parseMembers(memberNames()).ifEmpty { listOf(MusicMember("m0", "自分")) }

    /** Spotify アプリを開いて戻ったときなど、再生先が変わった可能性があるときに呼ぶ。 */
    fun refreshDevices() {
        val connection = port.connection.value
        if (port.configured && connection.connected && !connection.loading) port.refreshMusic()
    }

    /** 権限の確認は初回だけ。断られた後に毎回ダイアログを出さない。 */
    fun permissionsBeforeStart(): List<String> {
        val current = state.value
        if (!current.wanted || current.readiness !in setOf(DriveBgmReadiness.READY, DriveBgmReadiness.NO_DEVICE)) return emptyList()
        if (settings.microphoneAsked) return emptyList()
        settings.microphoneAsked = true
        return listOf(Manifest.permission.RECORD_AUDIO)
    }

    /** 案内を始めたら、流れている曲に関係なく、この先の景色と車内の様子ですぐ選び直す。 */
    fun onGuidanceStarted() {
        guiding = true
        port.beginDriveLog()
        if (wanted.value) begin(restart = true)
    }

    fun onGuidanceEnded() {
        guiding = false
        startJob?.cancel()
        startJob = null
        // 推薦だけを止める。流れている曲は最後まで流れる。
        if (startedHere) port.stopBgm()
        startedHere = false
        port.finishDriveLog()
    }

    /**
     * 案内中の「流す」「BGMに戻す」「再試行」と、案内の開始。流れている曲の終わりを待たずにすぐ1曲選ぶ。
     * [restart] が真なら、音楽画面で始めた推薦が動いていても、案内のルートの景色で選び直す。
     */
    fun begin(restart: Boolean = false) {
        if (!port.configured || !port.connection.value.connected) return
        if (startJob?.isActive == true) return
        if (port.bgm.value.enabled && !restart) return
        port.clearBgmFailure()
        startJob = scope.launch {
            selectDevice()
            if (!guiding) return@launch
            // 再生先が無いときも開始する。推薦側が再生先なしの失敗として示し、画面から直せる。
            port.startBgm(members())
            startedHere = port.bgm.value.enabled
            if (startedHere && port.microphoneGranted) port.startCabinListening()
        }
    }

    /** 案内中の「止める」。流れている曲も一時停止し、この案内のあいだは自動で再開しない。 */
    fun stop() {
        startJob?.cancel()
        startJob = null
        val device = port.latestPlayback()?.playback?.takeIf { it.playing }?.deviceId
        port.stopBgm()
        startedHere = false
        if (device != null) port.pause(device)
    }

    private suspend fun selectDevice() {
        withTimeoutOrNull(10_000) {
            port.music.first { !it.busy }
            port.refreshMusic()
            port.music.first { !it.busy }
        }
        val music = port.music.value
        if (music.selectedDeviceId == null) pick(music.devices, null)?.let { port.selectDevice(it.id) }
    }

    /**
     * 選択中 → 前回流せた再生先 → Spotify で再生中の端末 → このスマホ、の順に選ぶ。
     * このスマホは Spotify アプリを開かなくても使えるので、他に候補が無ければ必ずここに落ちる。
     */
    private fun pick(devices: List<MusicDevice>, selected: String?): MusicDevice? {
        val remembered = settings.device
        return devices.firstOrNull { it.id == selected } ?: devices.firstOrNull { it.id == remembered }
            ?: devices.firstOrNull { it.active } ?: devices.firstOrNull { it.id == SpotifyPhonePlayer.PHONE_DEVICE_ID }
            ?: devices.singleOrNull()
    }
}

internal fun parseMembers(names: String) = names.split(",", "、").map { it.trim() }.filter { it.isNotEmpty() }
    .mapIndexed { index, name -> MusicMember("m$index", name) }
