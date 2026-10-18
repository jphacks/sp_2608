package com.theacar.music

import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuestRequestSessionTest {
    private fun uri(n: Int) = "spotify:track:${n.toString().padStart(22, '0')}"
    private fun guest(id: Long, timing: RequestTiming, by: String = "たろう") =
        GuestRequest(id, SongRequest("guest:$id", uri(id.toInt()), "Song $id", "Artist", 180_000, by), timing)

    private class Inbox : RequestInbox {
        val batches = ArrayDeque<RequestBatch>()
        val syncs = mutableListOf<Pair<Long, List<SharedTrack>>>()
        val closed = mutableListOf<String>()
        var openFailure: MusicFailure? = null
        override suspend fun open(): RequestParty {
            openFailure?.let { throw MusicException(it) }
            return RequestParty("c".repeat(22), 0)
        }
        override suspend fun sync(code: String, after: Long, nowPlaying: SharedTrack?, queue: List<SharedTrack>): RequestBatch {
            syncs += after to queue
            return batches.removeFirstOrNull() ?: RequestBatch(true, emptyList())
        }
        override suspend fun close(code: String) { closed += code }
    }

    private class NoMusic : MusicService {
        override suspend fun search(query: String) = emptyList<MusicTrack>()
        override suspend fun devices() = emptyList<MusicDevice>()
        override suspend fun playback() = MusicPlayback()
        override suspend fun play(trackUri: String?, deviceId: String) = Unit
        override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) = Unit
        override suspend fun pause(deviceId: String) = Unit
        override suspend fun next(deviceId: String) = Unit
        override suspend fun previous(deviceId: String) = Unit
    }

    private class NoRecommendations : BgmRecommendationService {
        override suspend fun recommend(request: BgmRecommendationRequest) = BgmRecommendation(null)
        override suspend fun link(trackIds: List<String>) = TrackLinks(emptyList())
    }

    @Test fun reservationsGoToTheQueueAndTheLastPlayNowIsPlayed() = runTest {
        val inbox = Inbox()
        val bgm = BgmMusicSession(NoRecommendations(), NoMusic(), this, { 0L })
        val played = mutableListOf<SongRequest>()
        val session = GuestRequestSession(inbox, bgm, { played += it }, { null }, this, intervalMillis = 5_000)
        inbox.batches += RequestBatch(true, listOf(guest(1, RequestTiming.NEXT), guest(2, RequestTiming.NOW), guest(3, RequestTiming.NEXT), guest(4, RequestTiming.NOW)))
        session.open()
        runCurrent()
        assertEquals("c".repeat(22), session.state.value.code)
        assertEquals(listOf("guest:4"), played.map { it.id })
        // 先に届いた「今すぐ」は予約の先頭へ
        assertEquals(listOf("guest:2", "guest:1", "guest:3"), bgm.state.value.requests.map { it.id })
        assertEquals(4, session.state.value.received)
        // 次の問い合わせでは、受け取った分より後だけを求め、予約の列を同乗者の画面へ渡す
        advanceTimeBy(5_001)
        assertEquals(4L, inbox.syncs.last().first)
        assertEquals(listOf("Song 2", "Song 1", "Song 3"), inbox.syncs.last().second.map { it.title })
        session.close()
        runCurrent()
        assertEquals(listOf("c".repeat(22)), inbox.closed)
        assertNull(session.state.value.code)
    }

    @Test fun aClosedPartyStopsPolling() = runTest {
        val inbox = Inbox()
        val bgm = BgmMusicSession(NoRecommendations(), NoMusic(), this, { 0L })
        val session = GuestRequestSession(inbox, bgm, { }, { null }, this)
        inbox.batches += RequestBatch(false, emptyList())
        session.open()
        runCurrent()
        assertNull(session.state.value.code)
        advanceTimeBy(30_000)
        assertEquals(1, inbox.syncs.size)
    }

    @Test fun failingToOpenIsReported() = runTest {
        val inbox = Inbox().apply { openFailure = MusicFailure.NETWORK }
        val session = GuestRequestSession(inbox, BgmMusicSession(NoRecommendations(), NoMusic(), this, { 0L }), { }, { null }, this)
        session.open()
        runCurrent()
        assertEquals(MusicFailure.NETWORK, session.state.value.failure)
        assertNull(session.state.value.code)
    }
}
