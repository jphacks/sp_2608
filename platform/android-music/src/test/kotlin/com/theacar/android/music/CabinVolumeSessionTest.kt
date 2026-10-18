package com.theacar.android.music

import com.theacar.music.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CabinVolumeSessionTest {
    private class Stream : StreamVolume {
        override var current = 10
        var acceptsChanges = true
        override fun set(volume: Int): Boolean {
            if (acceptsChanges) current = volume
            return acceptsChanges
        }
    }

    private class Fixture(scope: CoroutineScope) {
        var now = 0L
        val stream = Stream()
        val bgm = MutableStateFlow(BgmMusicState(enabled = true))
        val music = MutableStateFlow(MusicState(selectedDeviceId = SpotifyPhonePlayer.PHONE_DEVICE_ID))
        val playback = MutableStateFlow<NowPlaying?>(NowPlaying(
            MusicPlayback(playing = true, deviceId = SpotifyPhonePlayer.PHONE_DEVICE_ID), now))
        val cabin = MutableStateFlow(CabinStatus(listening = CabinListening.ACTIVE))
        val connection = MutableStateFlow(SpotifyConnectionState(configured = true, loading = false, connected = true))
        val session = CabinVolumeSession(
            CabinVolumeController(windowMillis = 1_000, minSpeakingSeconds = 1, baselineSamples = 3,
                stepIntervalMillis = 0, restoreAfterMillis = 1_000),
            AndroidMusicVolume(stream), scope, bgm, music, playback, cabin, connection, { now },
        )

        fun speak(snr: Double) {
            now += 1_000
            playback.value = playback.value?.copy(receivedAtMillis = now)
            session.observe(CabinSecond(true, -45.0 + snr, -45.0))
        }

        fun lower() {
            session.beginDrive()
            repeat(3) { speak(15.0) }
            speak(25.0)
            assertEquals(9, stream.current)
            assertEquals(1, session.steps.value)
        }
    }

    @Test fun remotePlaybackDoesNotChangePhoneVolume() = runTest {
        val f = Fixture(backgroundScope)
        f.music.value = f.music.value.copy(selectedDeviceId = "remote")
        f.playback.value = NowPlaying(MusicPlayback(playing = true, deviceId = "remote"), 0)
        repeat(20) { f.speak(25.0) }
        runCurrent()
        assertEquals(10, f.stream.current)
        assertEquals(0, f.session.steps.value)
    }

    @Test fun pauseRestoresVolumeWithoutWaitingForAnotherSecond() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        runCurrent()
        f.playback.value = f.playback.value!!.let { it.copy(playback = it.playback.copy(playing = false)) }
        runCurrent()
        assertEquals(10, f.stream.current)
        assertEquals(0, f.session.steps.value)
        repeat(10) { f.speak(25.0) }
        assertEquals(10, f.stream.current)
    }

    @Test fun switchingDevicesRestoresPhoneVolume() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        runCurrent()
        f.music.value = f.music.value.copy(selectedDeviceId = "remote")
        runCurrent()
        assertEquals(10, f.stream.current)
        repeat(10) { f.speak(25.0) }
        assertEquals(10, f.stream.current)
    }

    @Test fun playbackMustConfirmTheSelectedPhoneIsPlaying() = runTest {
        val f = Fixture(backgroundScope)
        f.playback.value = NowPlaying(MusicPlayback(playing = true, deviceId = "remote"), 0)
        repeat(20) { f.speak(25.0) }
        assertEquals(10, f.stream.current)
        assertEquals(0, f.session.steps.value)
    }

    @Test fun resumingPlaybackKeepsTheLearnedVoiceBaseline() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        runCurrent()
        f.playback.value = f.playback.value!!.let { it.copy(playback = it.playback.copy(playing = false)) }
        runCurrent()
        assertEquals(10, f.stream.current)
        f.playback.value = f.playback.value!!.let { it.copy(playback = it.playback.copy(playing = true)) }
        f.speak(25.0)
        assertEquals(9, f.stream.current)
    }

    @Test fun missingMicrophoneRestoresVolume() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        runCurrent()
        f.cabin.value = CabinStatus(listening = CabinListening.UNAVAILABLE)
        runCurrent()
        assertEquals(10, f.stream.current)
        assertEquals(0, f.session.steps.value)
    }

    @Test fun disabledBgmRestoresVolume() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        runCurrent()
        f.bgm.value = f.bgm.value.copy(enabled = false)
        runCurrent()
        assertEquals(10, f.stream.current)
    }

    @Test fun disconnectRestoresVolume() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        runCurrent()
        f.connection.value = f.connection.value.copy(connected = false)
        runCurrent()
        assertEquals(10, f.stream.current)
    }

    @Test fun stalePlaybackStopsAdjustment() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        f.now += 16_000
        f.session.observe(CabinSecond(true, -20.0, -45.0))
        assertEquals(10, f.stream.current)
    }

    @Test fun manualVolumeBecomesNewBaseAndIsKeptOnStop() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        f.stream.current = 12
        f.speak(25.0)
        assertEquals(12, f.stream.current)
        assertEquals(0, f.session.steps.value)
        f.session.stop()
        assertEquals(12, f.stream.current)
    }

    @Test fun rejectedChangeIsNotReportedAsAttenuation() = runTest {
        val f = Fixture(backgroundScope)
        f.stream.acceptsChanges = false
        repeat(3) { f.speak(15.0) }
        f.speak(25.0)
        assertEquals(10, f.stream.current)
        assertEquals(0, f.session.steps.value)
    }

    @Test fun stopRestoresAndNewDriveLearnsAgain() = runTest {
        val f = Fixture(backgroundScope)
        f.lower()
        f.session.stop()
        assertEquals(10, f.stream.current)
        f.session.beginDrive()
        repeat(3) { f.speak(25.0) }
        assertEquals(10, f.stream.current)
    }
}
