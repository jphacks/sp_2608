package com.theacar.android.music

import com.theacar.android.music.SpotifyPhonePlayer.Companion.PHONE_DEVICE_ID
import com.theacar.music.MusicDevice
import com.theacar.music.MusicPlayback
import com.theacar.music.MusicService
import com.theacar.music.MusicTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneAwareMusicServiceTest {
    private class Web : MusicService {
        val calls = mutableListOf<String>()
        override suspend fun search(query: String) = emptyList<MusicTrack>()
        override suspend fun devices() = listOf(MusicDevice("car", "車のスピーカー", false))
        override suspend fun playback() = MusicPlayback(deviceId = "car")
        override suspend fun play(trackUri: String?, deviceId: String) { calls += "play $trackUri $deviceId" }
        override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) { calls += "mix ${tracks.size} $deviceId" }
        override suspend fun pause(deviceId: String) { calls += "pause $deviceId" }
        override suspend fun enqueue(trackUri: String, deviceId: String) { calls += "queue $trackUri $deviceId" }
        override suspend fun next(deviceId: String) { calls += "next $deviceId" }
        override suspend fun previous(deviceId: String) { calls += "previous $deviceId" }
    }

    private class Phone(override val installed: Boolean = true) : PhonePlayer {
        val calls = mutableListOf<String>()
        override suspend fun play(uri: String) { calls += "play $uri" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun queue(uri: String) { calls += "queue $uri" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
        override suspend fun playback() = MusicPlayback(deviceId = PHONE_DEVICE_ID, playing = true)
    }

    private fun track(id: String) = MusicTrack("spotify:track:$id", id, "artist", "")

    @Test
    fun addsThisPhoneOnlyWhenSpotifyIsInstalled() = runTest {
        assertEquals(listOf("car", PHONE_DEVICE_ID), PhoneAwareMusicService(Web(), Phone()).devices().map { it.id })
        assertEquals(listOf("car"), PhoneAwareMusicService(Web(), Phone(installed = false)).devices().map { it.id })
    }

    @Test
    fun routesPhoneCommandsToAppRemoteAndOthersToWebApi() = runTest {
        val web = Web()
        val phone = Phone()
        val service = PhoneAwareMusicService(web, phone)
        service.play("spotify:track:a", PHONE_DEVICE_ID)
        service.play(null, PHONE_DEVICE_ID)
        service.next(PHONE_DEVICE_ID)
        service.pause("car")
        assertEquals(listOf("play spotify:track:a", "resume", "next"), phone.calls)
        assertEquals(listOf("pause car"), web.calls)
    }

    @Test
    fun queuesOnTheSelectedDeviceWithoutStartingPlayback() = runTest {
        val web = Web()
        val phone = Phone()
        val service = PhoneAwareMusicService(web, phone)
        service.enqueue("spotify:track:a", PHONE_DEVICE_ID)
        service.enqueue("spotify:track:b", "car")
        assertEquals(listOf("queue spotify:track:a"), phone.calls)
        assertEquals(listOf("queue spotify:track:b car"), web.calls)
    }

    @Test
    fun playbackFollowsTheLastTarget() = runTest {
        val service = PhoneAwareMusicService(Web(), Phone())
        assertEquals("car", service.playback().deviceId)
        service.play("spotify:track:a", PHONE_DEVICE_ID)
        assertEquals(PHONE_DEVICE_ID, service.playback().deviceId)
        assertTrue(service.devices().first { it.id == PHONE_DEVICE_ID }.active)
        service.play("spotify:track:b", "car")
        assertEquals("car", service.playback().deviceId)
        assertFalse(service.devices().first { it.id == PHONE_DEVICE_ID }.active)
    }

    @Test
    fun mixOnPhonePlaysFirstAndQueuesTheRest() = runTest {
        val phone = Phone()
        PhoneAwareMusicService(Web(), phone).playMix(listOf(track("a"), track("b"), track("c")), PHONE_DEVICE_ID)
        assertEquals(listOf("play spotify:track:a", "queue spotify:track:b", "queue spotify:track:c"), phone.calls)
    }
}
