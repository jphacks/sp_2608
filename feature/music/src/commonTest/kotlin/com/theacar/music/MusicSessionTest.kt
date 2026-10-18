package com.theacar.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicSessionTest {
    private val track = MusicTrack("spotify:track:0123456789012345678901", "Track", "Artist", "https://open.spotify.com/track/0123456789012345678901")
    private class Service : MusicService {
        var available = listOf(MusicDevice("phone", "Phone", true), MusicDevice("computer", "Computer", false))
        var current = MusicPlayback()
        var failure: MusicFailure? = null
        var selected: String? = null
        var searches = mutableListOf<String>()
        var pendingSearch: CompletableDeferred<List<MusicTrack>>? = null
        override suspend fun search(query: String): List<MusicTrack> { searches += query; return pendingSearch?.await().orEmpty() }
        override suspend fun devices() = available
        override suspend fun playback() = current
        override suspend fun play(trackUri: String?, deviceId: String) {
            failure?.let { throw MusicException(it) }
            selected = deviceId
            current = current.copy(playing = true, deviceId = deviceId)
        }
        override suspend fun pause(deviceId: String) { current = current.copy(playing = false) }
        override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) = play(tracks.first().uri, deviceId)
        override suspend fun next(deviceId: String) = Unit
        override suspend fun previous(deviceId: String) = Unit
    }

    @Test fun selectsActiveDeviceAndKeepsExplicitSelectionOnRefresh() = runTest {
        val service = Service()
        val session = MusicSession(service, backgroundScope)
        session.refresh(); runCurrent()
        assertEquals("phone", session.state.value.selectedDeviceId)
        session.selectDevice("computer")
        session.refresh(); runCurrent()
        assertEquals("computer", session.state.value.selectedDeviceId)
        session.play(track); runCurrent()
        assertEquals("computer", service.selected)
        assertTrue(session.state.value.playback.playing)
    }

    @Test fun selectingAnotherDeviceStartsThereInsteadOfPausingTheActiveDevice() = runTest {
        val service = Service().apply { current = MusicPlayback(playing = true, deviceId = "phone") }
        val session = MusicSession(service, backgroundScope)
        session.refresh(); runCurrent()
        session.selectDevice("computer")
        session.togglePlayback(); runCurrent()
        assertEquals("computer", service.selected)
        assertTrue(session.state.value.playback.playing)
    }

    @Test fun neverSelectsAnArbitraryInactiveDevice() = runTest {
        val service = Service().apply { available = listOf(MusicDevice("computer", "Computer", false)) }
        val session = MusicSession(service, backgroundScope)
        session.refresh(); runCurrent()
        assertNull(session.state.value.selectedDeviceId)
        session.play(track); runCurrent()
        assertEquals(MusicFailure.NO_DEVICE, session.state.value.failure)
        assertNull(service.selected)
    }

    @Test fun failedPlayDoesNotPretendMusicIsPlaying() = runTest {
        val service = Service().apply { failure = MusicFailure.ACCESS_DENIED }
        val session = MusicSession(service, backgroundScope)
        session.refresh(); runCurrent()
        session.play(track); runCurrent()
        assertFalse(session.state.value.playback.playing)
        assertEquals(MusicFailure.ACCESS_DENIED, session.state.value.failure)
        assertFalse(session.state.value.busy)
    }

    @Test fun newSearchCancelsOldResultsAndBlankSearchClearsThem() = runTest {
        val pending = CompletableDeferred<List<MusicTrack>>()
        val service = Service().apply { pendingSearch = pending }
        val session = MusicSession(service, backgroundScope)
        session.search("first"); runCurrent()
        service.pendingSearch = null
        session.search("second"); runCurrent()
        pending.complete(listOf(track)); runCurrent()
        assertEquals("second", session.state.value.query)
        assertTrue(session.state.value.results.isEmpty())
        session.search(" "); runCurrent()
        assertFalse(session.state.value.searching)
        assertEquals(2, service.searches.size)
    }

    @Test fun mixPreservesThePlaybackFailureForTheCaller() = runTest {
        val service = Service().apply { failure = MusicFailure.ACCESS_DENIED }
        val session = MusicSession(service, backgroundScope)
        val result = backgroundScope.async {
            assertFailsWith<MusicException> { session.playMix(listOf(track), "phone") }.failure
        }
        runCurrent()
        assertEquals(MusicFailure.ACCESS_DENIED, result.await())
        assertEquals(MusicFailure.ACCESS_DENIED, session.state.value.failure)
        assertFalse(session.state.value.busy)
    }

    @Test fun mixUsesTheChosenDeviceAndThenReadsServerPlayback() = runTest {
        val service = Service()
        val session = MusicSession(service, backgroundScope)
        val result = backgroundScope.async { session.playMix(listOf(track), "computer") }
        runCurrent()
        assertTrue(result.await())
        assertEquals("computer", service.selected)
        assertEquals("computer", session.state.value.playback.deviceId)
    }

    @Test fun resetCancelsSearchAndClearsPlaybackAndDeviceState() = runTest {
        val service = Service().apply { pendingSearch = CompletableDeferred() }
        val session = MusicSession(service, backgroundScope)
        session.refresh(); runCurrent()
        session.search("pending"); runCurrent()
        session.reset()
        service.pendingSearch?.complete(listOf(track)); runCurrent()
        assertEquals(MusicState(), session.state.value)
        session.close()
        session.search("ignored"); runCurrent()
        assertEquals(1, service.searches.size)
    }
}
