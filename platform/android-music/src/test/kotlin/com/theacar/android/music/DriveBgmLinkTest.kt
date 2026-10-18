package com.theacar.android.music

import com.theacar.music.BgmMusicState
import com.theacar.music.CabinStatus
import com.theacar.music.MusicDevice
import com.theacar.music.MusicMember
import com.theacar.music.MusicPlayback
import com.theacar.music.MusicState
import com.theacar.music.NowPlaying
import com.theacar.music.PlaylistInputState
import com.theacar.music.RecommendationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriveBgmLinkTest {
    private val phone = MusicDevice(SpotifyPhonePlayer.PHONE_DEVICE_ID, "このスマホ", false)
    private val speaker = MusicDevice("speaker", "車のスピーカー", false)

    private class Port : DriveBgmPort {
        override var configured = true
        override val connection = MutableStateFlow(SpotifyConnectionState(configured = true, loading = false, connected = true))
        override val music = MutableStateFlow(MusicState())
        override val bgm = MutableStateFlow(BgmMusicState())
        override val cabin = MutableStateFlow(CabinStatus())
        override val phoneProblem = MutableStateFlow<PhonePlayerProblem?>(null)
        override val spotifyInstalled = true
        override val playlists = MutableStateFlow(PlaylistInputState())
        override val playback = MutableStateFlow<NowPlaying?>(null)
        override var microphoneGranted = true
        val events = mutableListOf<String>()
        var started: List<MusicMember>? = null
        override fun latestPlayback() = playback.value
        override fun refreshMusic() { events += "refresh" }
        override fun selectDevice(id: String) { music.update { it.copy(selectedDeviceId = id) } }
        override fun clearBgmFailure() = Unit
        override fun startBgm(members: List<MusicMember>) { started = members; bgm.update { it.copy(enabled = true) } }
        override fun stopBgm() { events += "stop"; bgm.update { it.copy(enabled = false) } }
        override fun pause(deviceId: String) { events += "pause $deviceId" }
        override fun startCabinListening() { events += "listen" }
        override fun beginDriveLog() { events += "log-start" }
        override fun finishDriveLog() { events += "log-finish" }
    }

    private class Settings : DriveBgmSettings {
        override var wanted = true
        override var device: String? = null
        override var members: String? = "A、B"
        override var microphoneAsked = false
    }

    @Test fun guidanceStartsBgmOnTheRememberedDeviceAndStopsOnlyWhatItStarted() = runTest {
        val port = Port().apply { music.value = MusicState(devices = listOf(phone, speaker)) }
        val settings = Settings().apply { device = "speaker" }
        val link = DriveBgmLink(port, settings, backgroundScope)
        link.onGuidanceStarted(); runCurrent()
        assertEquals("speaker", port.music.value.selectedDeviceId)
        assertEquals(listOf("A", "B"), port.started?.map { it.name })
        assertEquals(listOf("log-start", "refresh", "listen"), port.events)

        link.onGuidanceEnded(); runCurrent()
        assertEquals(listOf("stop", "log-finish"), port.events.drop(3))
    }

    @Test fun leavesBgmStartedFromTheMusicScreenRunningAfterGuidance() = runTest {
        val port = Port()
        val link = DriveBgmLink(port, Settings().apply { wanted = false }, backgroundScope)
        port.bgm.value = BgmMusicState(enabled = true)
        link.onGuidanceStarted(); runCurrent()
        link.onGuidanceEnded(); runCurrent()
        assertTrue(port.bgm.value.enabled)
        assertFalse("stop" in port.events)
    }

    @Test fun stopPausesTheSongThatIsPlaying() = runTest {
        val port = Port().apply { playback.value = NowPlaying(MusicPlayback(playing = true, deviceId = "speaker"), 0) }
        DriveBgmLink(port, Settings(), backgroundScope).stop()
        assertEquals(listOf("stop", "pause speaker"), port.events)
    }

    @Test fun asksForTheMicrophoneOnlyOnce() = runTest {
        val port = Port().apply { music.value = MusicState(devices = listOf(phone)) }
        val link = DriveBgmLink(port, Settings(), backgroundScope)
        runCurrent()
        assertEquals(1, link.permissionsBeforeStart().size)
        assertTrue(link.permissionsBeforeStart().isEmpty())
    }

    @Test fun requiresAPlaylistInPlaylistOnlyMode() = runTest {
        val port = Port().apply { music.value = MusicState(devices = listOf(phone)) }
        val link = DriveBgmLink(port, Settings(), backgroundScope)
        runCurrent()
        assertEquals(DriveBgmReadiness.READY, link.state.value.readiness)
        port.playlists.value = PlaylistInputState(mode = RecommendationMode.PLAYLIST)
        runCurrent()
        assertEquals(DriveBgmReadiness.PLAYLIST_REQUIRED, link.state.value.readiness)
        port.connection.update { it.copy(connected = false) }
        runCurrent()
        assertEquals(DriveBgmReadiness.LOGIN_REQUIRED, link.state.value.readiness)
    }
}
