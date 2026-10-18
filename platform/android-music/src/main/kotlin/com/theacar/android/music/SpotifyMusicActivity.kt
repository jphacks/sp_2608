package com.theacar.android.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.theacar.music.MusicMember
import com.theacar.music.MusicTrack
import com.theacar.ui.TheaCarTheme
import com.theacar.ui.openTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SpotifyMusicActivity : ComponentActivity() {
    private val runtime get() = (application as AndroidMusicOwner).spotifyRuntime
    private var polling: Job? = null
    private val environmentPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) runtime.inspectEnvironment()
        else Toast.makeText(this, "現在地の確認には正確な位置情報を許可してください。", Toast.LENGTH_LONG).show()
    }

    private fun inspectEnvironment() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) runtime.inspectEnvironment()
        else environmentPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) runtime.startDriveMusic()
        else Toast.makeText(this, "おまかせ再生には正確な位置情報の許可が必要です。", Toast.LENGTH_LONG).show()
    }

    private fun startDriveMusic() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) runtime.startDriveMusic()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** 手動の再生操作は自動の選曲より優先する。 */
    private fun bgmRunning() = runtime.bgmMusic.state.value.enabled

    /**
     * 景観BGMの間に選んだ曲は、すぐ再生する。景観BGMは止めず、その曲が終わったら景観BGMの曲に戻る。
     * 景観BGMを使っていないときは、従来どおり再生して自動の選曲を止める。
     */
    private fun playOrQueue(track: MusicTrack) {
        if (bgmRunning()) {
            runtime.playRequest(track) { failure ->
                val text = if (failure == null) "「${track.title}」を再生します。終わったら予約の曲か景観BGMに戻ります"
                    else "「${track.title}」を再生できませんでした：${failure.message()}"
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        } else {
            stopAutomaticPlayback()
            runtime.session.play(track)
        }
    }

    private fun enqueue(track: MusicTrack) {
        runtime.enqueueRequest(track)
        Toast.makeText(this, "「${track.title}」を予約しました（${runtime.bgmMusic.state.value.requests.size}曲目）", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutomaticPlayback() {
        runtime.stopDriveMusic()
        runtime.stopBgmRecommendation()
    }

    private var pendingMembers: List<MusicMember> = emptyList()
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "マイクを許可しないと、車内の雰囲気は「ふつう」として選曲します。", Toast.LENGTH_LONG).show()
        beginBgm(pendingMembers)
    }

    /** 車内の雰囲気の判定にマイクを使う。許可されなくても推薦は始める。 */
    private fun startBgm(members: List<MusicMember>) {
        pendingMembers = members
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginBgm(members)
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginBgm(members: List<MusicMember>) {
        runtime.driveBgm.saveMemberNames(members.joinToString("、") { it.name })
        runtime.startBgmRecommendation(members)
        if (runtime.bgmMusic.state.value.enabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CabinListeningService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val connection by runtime.connection.collectAsState()
            val state by runtime.session.state.collectAsState()
            val drive by runtime.driveMusic.state.collectAsState()
            val bgm by runtime.bgmMusic.state.collectAsState()
            val playlists by runtime.playlists.state.collectAsState()
            val guests by runtime.guests.state.collectAsState()
            val cabin by runtime.cabin.collectAsState()
            val location by runtime.locations.location.collectAsState()
            val weather by runtime.weather.state.collectAsState()
            val environmentCheck by runtime.environmentCheck.collectAsState()
            TheaCarTheme {
                SpotifyMusicScreen(connection, state, drive, bgm, guests, runtime::guestUrl, cabin, runtime.driveBgm.memberNames(), runtime.bgmConfigured, location, weather, environmentCheck,
                    onInspectEnvironment = ::inspectEnvironment,
                    onStartDriveMusic = ::startDriveMusic,
                    onStopDriveMusic = runtime::stopDriveMusic,
                    onStartBgm = ::startBgm,
                    onStopBgm = runtime::stopBgmRecommendation,
                    onSaveClientId = runtime::saveClientId,
                    onConnect = { lifecycleScope.launch { runtime.loginUrl()?.let(::openUrl) } },
                    onDisconnect = runtime::disconnect,
                    onSearch = runtime.session::search,
                    onDeviceSelected = { stopAutomaticPlayback(); runtime.session.selectDevice(it); runtime.driveBgm.rememberDevice(it) },
                    onRefresh = runtime.session::refresh,
                    onPlay = ::playOrQueue,
                    onEnqueue = ::enqueue,
                    onOpenGuests = runtime::openGuestRequests,
                    onCloseGuests = runtime::closeGuestRequests,
                    onMoveRequest = runtime.bgmMusic::moveRequest,
                    onRemoveRequest = runtime.bgmMusic::removeRequest,
                    onToggle = { if (bgmRunning()) runtime.togglePlayback() else { stopAutomaticPlayback(); runtime.session.togglePlayback() } },
                    onNext = { if (!bgmRunning()) stopAutomaticPlayback(); runtime.session.next() },
                    onPrevious = { if (!bgmRunning()) stopAutomaticPlayback(); runtime.session.previous() },
                    onOpenSpotify = ::openSpotify,
                    onOpenUrl = ::openUrl,
                    onSelectTab = ::openTab,
                    playlists = playlists,
                    onImportPlaylist = runtime.playlists::import,
                    onCancelPlaylist = runtime.playlists::cancelImport,
                    onRemovePlaylist = runtime.playlists::remove,
                    onPlaylistMode = runtime.playlists::setMode)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        polling = lifecycleScope.launch {
            while (isActive) {
                if (runtime.connection.value.connected && !runtime.connection.value.loading) runtime.session.refresh()
                delay(15_000)
            }
        }
    }

    override fun onStop() {
        polling?.cancel()
        polling = null
        // 景観BGMはナビの画面へ移っても続ける。おまかせ再生は従来どおり画面を閉じたら止める。
        runtime.stopDriveMusic()
        runtime.stopEnvironmentInspection()
        super.onStop()
    }

    private fun openSpotify() {
        val intent = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (intent != null) startActivity(intent) else openUrl("https://open.spotify.com/")
    }

    private fun openUrl(value: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "このリンクを開けるアプリがありません", Toast.LENGTH_LONG).show() }
    }
}
