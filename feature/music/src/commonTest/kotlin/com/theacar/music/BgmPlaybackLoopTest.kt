package com.theacar.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BgmPlaybackLoopTest {
    private val members = listOf(MusicMember("a", "A"))
    private val song = MusicTrack("spotify:track:0123456789012345678901", "Song", "Artist", "https://open.spotify.com/track/x", 200_000)

    private class Service : MusicService {
        var current = MusicPlayback(deviceId = "phone")
        var failure: MusicFailure? = null
        var playbackCalls = 0
        override suspend fun search(query: String) = emptyList<MusicTrack>()
        override suspend fun devices() = listOf(MusicDevice("phone", "Phone", true))
        override suspend fun playback(): MusicPlayback { playbackCalls++; failure?.let { throw MusicException(it) }; return current }
        override suspend fun play(trackUri: String?, deviceId: String) = Unit
        override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) = Unit
        override suspend fun pause(deviceId: String) = Unit
        override suspend fun next(deviceId: String) = Unit
        override suspend fun previous(deviceId: String) = Unit
    }

    /** 推薦は返さない。ループが進めることだけを確かめる。 */
    private class Silent : BgmRecommendationService {
        override suspend fun recommend(request: BgmRecommendationRequest): BgmRecommendation = awaitCancellation()
        override suspend fun link(trackIds: List<String>): TrackLinks = awaitCancellation()
    }

    private inner class Fixture(scope: CoroutineScope, time: () -> Long) {
        val service = Service()
        val session = MusicSession(service, scope)
        val bgm = BgmMusicSession(Silent(), service, scope, time)
        val monitor = PlaybackMonitor(service, scope, bgm.state, { true }, time)
        var lost = 0
        val cabins = mutableListOf<CabinState>()
        val loop = BgmPlaybackLoop(bgm, service, session, monitor, scope, { null },
            { CabinState.QUIET.also { cabins += it } }, { true }, { lost++ })
    }

    @Test fun reportsPlaybackAndAsksForTheCabinEachRound() = runTest {
        val f = Fixture(backgroundScope) { currentTime }
        f.session.refresh(); runCurrent()
        f.service.current = MusicPlayback(song, true, "phone", positionMillis = 10_000)
        f.loop.start(members, emptyList(), RecommendationMode.BLEND)
        runCurrent()
        assertTrue(f.bgm.state.value.enabled)
        assertEquals(song, f.monitor.bgmPlayback.value?.playback?.track)
        assertEquals(1, f.cabins.size)
        advanceTimeBy(5_001)
        assertEquals(2, f.cabins.size)
    }

    @Test fun stopsWhenAuthorizationIsLost() = runTest {
        val f = Fixture(backgroundScope) { currentTime }
        f.session.refresh(); runCurrent()
        f.service.failure = MusicFailure.AUTHORIZATION
        f.loop.start(members, emptyList(), RecommendationMode.BLEND)
        runCurrent()
        assertFalse(f.bgm.state.value.enabled)
        assertEquals(1, f.lost)
        advanceTimeBy(20_000)
        assertEquals(1, f.lost)
    }

    @Test fun haltStopsTheLoopAndClearsTheReportedPlayback() = runTest {
        val f = Fixture(backgroundScope) { currentTime }
        f.session.refresh(); runCurrent()
        f.loop.start(members, emptyList(), RecommendationMode.BLEND)
        runCurrent()
        f.loop.halt()
        val calls = f.service.playbackCalls
        advanceTimeBy(20_000)
        assertEquals(calls, f.service.playbackCalls)
        assertNull(f.monitor.bgmPlayback.value)
        assertFalse(f.bgm.state.value.enabled)
    }

    @Test fun displayPollsOnlyWhileWatchedAndOutsideBgm() = runTest {
        val f = Fixture(backgroundScope) { currentTime }
        f.service.current = MusicPlayback(song, true, "phone")
        assertNull(f.monitor.display.value)
        val watcher = backgroundScope.launch { f.monitor.display.collect {} }
        runCurrent()
        assertEquals(song, f.monitor.display.value?.playback?.track)
        val calls = f.service.playbackCalls
        advanceTimeBy(5_001)
        assertEquals(calls + 1, f.service.playbackCalls)
        watcher.cancel()
        advanceTimeBy(30_000)
        assertEquals(calls + 2, f.service.playbackCalls)
    }
}
