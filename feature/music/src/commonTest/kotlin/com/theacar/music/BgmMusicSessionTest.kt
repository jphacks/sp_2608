package com.theacar.music

import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BgmMusicSessionTest {
    private val members = listOf(MusicMember("a", "A"))
    private val firstUri = "spotify:track:0123456789012345678901"
    private val secondUri = "spotify:track:1123456789012345678901"
    private val otherUri = "spotify:track:9123456789012345678901"
    private val first = RecommendedTrack("isrc:AAA", "First", "Artist", 200_000, "a", "海が近いので")
    private val second = RecommendedTrack("isrc:BBB", "Second", "Other", 180_000)
    private val scenery = SceneryRoute(1_000.0, listOf(SceneryBlock(0.0, 5_000.0, "sea", .3)))

    private class Music : MusicService {
        val played = mutableListOf<String>()
        override suspend fun search(query: String) = emptyList<MusicTrack>()
        override suspend fun devices() = emptyList<MusicDevice>()
        override suspend fun playback() = MusicPlayback()
        override suspend fun play(trackUri: String?, deviceId: String) { played += requireNotNull(trackUri) }
        override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) = Unit
        override suspend fun pause(deviceId: String) = Unit
        override suspend fun next(deviceId: String) = Unit
        override suspend fun previous(deviceId: String) = Unit
    }

    private class Recommendations(var response: BgmRecommendation) : BgmRecommendationService {
        val requests = mutableListOf<BgmRecommendationRequest>()
        var links = emptyMap<String, TrackLink>()
        var failure: Exception? = null
        var linkGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun recommend(request: BgmRecommendationRequest): BgmRecommendation {
            requests += request
            failure?.let { throw it }
            return response
        }
        override suspend fun link(trackIds: List<String>): TrackLinks {
            require(trackIds.size <= BgmRecommendationService.MAX_LINKS)
            linkGate?.await()
            return TrackLinks(trackIds.mapNotNull { links[it] })
        }
    }

    private fun found(id: String, uri: String) = id to TrackLink(id, TrackLinkStatus.FOUND, uri)

    private inner class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        var time = 1_000L
        val music = Music()
        val recommendations = Recommendations(BgmRecommendation(first, listOf(second)))
            .apply { links = mapOf(found(first.trackId, firstUri), found(second.trackId, secondUri)) }
        val session = BgmMusicSession(recommendations, music, scope, { time })
        fun tick(playback: MusicPlayback = MusicPlayback(), cabin: CabinState = CabinState.NORMAL) =
            session.update(scenery, cabin, "phone", playback, false)
    }

    private fun playbackOf(uri: String, duration: Int, position: Int) = MusicPlayback(
        MusicTrack(uri, "Song", "Artist", "https://open.spotify.com/track/x", duration), true, "phone", false, position)

    @Test fun playlistModeUsesSharedPoolAndKeepsOwnersWhenPlaying() = runTest {
        val fixture = Fixture(this)
        val group = members + MusicMember("b", "B")
        val pool = listOf(PlaylistEntry(first.trackId, listOf("a", "b")))
        fixture.session.start(group, pool, RecommendationMode.PLAYLIST)
        fixture.tick()
        runCurrent()
        val request = fixture.recommendations.requests.single()
        assertEquals(RecommendationMode.PLAYLIST, request.mode)
        assertEquals(pool, request.pool)
        assertEquals(group, request.members)
        assertEquals(listOf(firstUri), fixture.music.played)
        fixture.session.stop()
    }

    @Test fun requestOnlyReceptionWaitsWithoutStartingRecommendations() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList(), requestsOnly = true)
        fixture.tick()
        runCurrent()
        assertTrue(fixture.session.state.value.requestsOnly)
        assertTrue(fixture.music.played.isEmpty())
        assertTrue(fixture.recommendations.requests.isEmpty())
        fixture.session.stop()
    }

    @Test fun requestOnlyQueuePlaysInOrderWithoutRecommendingAfterwards() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList(), requestsOnly = true)
        fixture.session.enqueue(SongRequest("guest:1", firstUri, "First", "Artist", 200_000, "A"))
        fixture.session.enqueue(SongRequest("guest:2", secondUri, "Second", "Other", 180_000, "B"))
        fixture.tick()
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        fixture.time += 20_000
        fixture.tick(playbackOf(firstUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        fixture.time += 20_000
        fixture.tick(MusicPlayback())
        runCurrent()
        assertTrue(fixture.session.state.value.requests.isEmpty())
        assertTrue(fixture.recommendations.requests.isEmpty())
        fixture.session.stop()
    }

    @Test fun openingReceptionDoesNotInterruptAnExistingSong() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList(), requestsOnly = true)
        fixture.session.enqueue(SongRequest("guest:1", firstUri, "First", "Artist"))
        fixture.tick(playbackOf(otherUri, 200_000, 50_000))
        runCurrent()
        assertTrue(fixture.music.played.isEmpty())
        fixture.time += 20_000
        fixture.tick(playbackOf(otherUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        fixture.session.stop()
    }

    @Test fun playsTheChosenTrackAndSendsItAsHistoryOnTheNextRequest() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, listOf(PlaylistEntry("isrc:AAA", listOf("a"))))
        fixture.tick()
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        assertEquals(first, fixture.session.state.value.playing)
        assertEquals("海が近いので", fixture.session.state.value.message)
        assertEquals(1, fixture.session.state.value.playedCount)
        assertEquals(.6f, fixture.session.state.value.recommendedVolume)

        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        assertEquals(2, fixture.recommendations.requests.size)
        val latest = fixture.recommendations.requests.last()
        assertEquals(listOf(PlayedTrack("isrc:AAA", "a", "Artist")), latest.history)
        // 窓の長さの目安として、今の曲の長さを渡す。
        assertEquals(200_000, latest.scenery?.nextDurationMillis)
        // 今の曲は止めずに準備しておく。
        assertEquals(listOf(firstUri), fixture.music.played)
        assertEquals(secondUri, fixture.session.state.value.upNextUri)
        assertEquals(first, fixture.session.state.value.playing)
        // 残りが少しになるまでは切り替えない。
        fixture.tick(playbackOf(firstUri, 200_000, 190_000))
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        // 今の曲の終わりで、準備した曲を流す。
        fixture.tick(playbackOf(firstUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        assertEquals(second, fixture.session.state.value.playing)
        assertEquals(secondUri, fixture.session.state.value.playingUri)
        assertNull(fixture.session.state.value.upNextUri)
        assertEquals(2, fixture.session.state.value.playedCount)
        assertEquals(2, fixture.recommendations.requests.size)
    }

    @Test fun waitsUntilTheCurrentSongIsNearlyOver() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.tick(playbackOf(firstUri, 200_000, 60_000))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
    }

    @Test fun doesNotRequestMoreOftenThanTheMinimumInterval() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 1_000
        fixture.tick(playbackOf(firstUri, 200_000, 199_000))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
    }

    @Test fun fallsBackToTheNextCandidateWhenSpotifyHasNoMatch() = runTest {
        val fixture = Fixture(this)
        fixture.recommendations.links = mapOf(
            first.trackId to TrackLink(first.trackId, TrackLinkStatus.NOT_FOUND),
            found(second.trackId, secondUri),
        )
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        assertEquals(listOf(secondUri), fixture.music.played)
        assertEquals(second, fixture.session.state.value.playing)
        // 演出の一言は chosen に対して書かれているので、次点へ回ったら出さない。
        assertNull(fixture.session.state.value.message)
    }

    @Test fun pendingAndMissingIsrcMoveOnToTheNextCandidate() = runTest {
        val fixture = Fixture(this)
        fixture.recommendations.links = mapOf(
            first.trackId to TrackLink(first.trackId, TrackLinkStatus.PENDING),
            second.trackId to TrackLink(second.trackId, TrackLinkStatus.NO_ISRC),
        )
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        assertTrue(fixture.music.played.isEmpty())
        assertEquals(MusicFailure.NO_RESULTS, fixture.session.state.value.failure)
        assertTrue(fixture.session.state.value.enabled)
    }

    @Test fun noCandidateLeavesTheNoteAndKeepsRunning() = runTest {
        val fixture = Fixture(this)
        fixture.recommendations.response = BgmRecommendation(null, note = "no tracks left in pool")
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        assertTrue(fixture.music.played.isEmpty())
        assertEquals("no tracks left in pool", fixture.session.state.value.note)
        assertTrue(fixture.session.state.value.enabled)
    }

    @Test fun networkFailureRetriesLaterButAuthorizationStops() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.recommendations.failure = MusicException(MusicFailure.NETWORK)
        fixture.tick()
        runCurrent()
        assertEquals(MusicFailure.NETWORK, fixture.session.state.value.failure)
        assertTrue(fixture.session.state.value.enabled)

        fixture.time += 20_000
        fixture.recommendations.failure = MusicException(MusicFailure.AUTHORIZATION)
        fixture.tick()
        runCurrent()
        assertEquals(MusicFailure.AUTHORIZATION, fixture.session.state.value.failure)
        assertFalse(fixture.session.state.value.enabled)

        fixture.time += 20_000
        fixture.recommendations.failure = null
        fixture.tick()
        runCurrent()
        assertEquals(2, fixture.recommendations.requests.size)
    }

    @Test fun aStaleReportOfThePreviousSongRightAfterSwitchingIsIgnored() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        fixture.tick(playbackOf(firstUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        // 切り替えの直後、再生先がまだ前の曲の終わりを返した（2026-10-18 に実機で確認）。利用者の操作とみなさない。
        fixture.time += 2_000
        fixture.recommendations.response = BgmRecommendation(first)
        fixture.tick(playbackOf(firstUri, 200_000, 199_500))
        runCurrent()
        fixture.tick(playbackOf(secondUri, 180_000, 3_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        assertEquals(2, fixture.recommendations.requests.size)
        assertEquals(second, fixture.session.state.value.playing)
    }

    private val thirdUri = "spotify:track:8123456789012345678901"
    private fun request(id: String, uri: String = otherUri, by: String? = "たろう") = SongRequest(id, uri, "Req $id", "Artist", 200_000, by)

    @Test fun aQueuedRequestPlaysAtTheEndOfTheSongInsteadOfScenicBgm() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        val requested = request("r1")
        fixture.session.enqueue(requested)
        assertEquals(otherUri, fixture.session.state.value.upNextUri)
        // 予約がある間は、次の景観BGMの曲を推薦しない
        fixture.time += 20_000
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
        fixture.tick(playbackOf(firstUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, otherUri), fixture.music.played)
        assertEquals(requested, fixture.session.state.value.playingRequest)
        assertEquals(otherUri, fixture.session.state.value.playingUri)
        assertTrue(fixture.session.state.value.requests.isEmpty())
        // 予約が無くなったら、その曲の終わりで景観BGMに戻る
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(otherUri, 200_000, 180_000))
        runCurrent()
        fixture.tick(playbackOf(otherUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, otherUri, secondUri), fixture.music.played)
        assertNull(fixture.session.state.value.playingRequest)
        assertEquals(second, fixture.session.state.value.playing)
    }

    @Test fun aRequestQueuedAfterTheNextScenicSongWasPreparedStillPlaysFirst() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        fixture.session.enqueue(request("r1"))
        fixture.tick(playbackOf(firstUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, otherUri), fixture.music.played)
        // 準備した景観BGMの曲は、予約の後に流す
        assertEquals(secondUri, fixture.session.state.value.upNextUri)
        fixture.time += 20_000
        fixture.tick(playbackOf(otherUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, otherUri, secondUri), fixture.music.played)
        assertEquals(2, fixture.recommendations.requests.size)
    }

    @Test fun theQueueCanBeReorderedAndTrimmed() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.session.enqueue(request("a", firstUri))
        fixture.session.enqueue(request("b", secondUri))
        fixture.session.enqueue(request("c", thirdUri))
        fixture.session.moveRequest("c", -1)
        assertEquals(listOf("a", "c", "b"), fixture.session.state.value.requests.map { it.id })
        fixture.session.moveRequest("a", -1)
        fixture.session.moveRequest("b", 5)
        assertEquals(listOf("a", "c", "b"), fixture.session.state.value.requests.map { it.id })
        fixture.session.removeRequest("a")
        assertEquals(listOf("c", "b"), fixture.session.state.value.requests.map { it.id })
        assertEquals(thirdUri, fixture.session.state.value.upNextUri)
        fixture.session.enqueue(request("d", firstUri), first = true)
        assertEquals(listOf("d", "c", "b"), fixture.session.state.value.requests.map { it.id })
    }

    @Test fun aSongTheUserPicksMidwayPlaysToTheEndAndScenicBgmFollowsIt() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        // 利用者が曲の途中で別の曲に変えた。その曲を終わりまで任せる。
        fixture.time += 20_000
        fixture.tick(playbackOf(otherUri, 200_000, 100_000))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
        // その曲の終わりが近づいたら景観BGMの次の曲を準備し、終わりで流す。
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(otherUri, 200_000, 180_000))
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        fixture.tick(playbackOf(otherUri, 200_000, 199_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
    }

    @Test fun aRequestedSongPlaysToTheEndAndThePreparedSongIsDropped() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        assertEquals(secondUri, fixture.session.state.value.upNextUri)
        // 準備した曲が始まる前に、利用者が曲を選んで流した。
        fixture.session.playRequest(SongRequest("r1", otherUri, "Other", "Someone"))
        assertNull(fixture.session.state.value.upNextUri)
        fixture.tick(playbackOf(otherUri, 200_000, 5_000))
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        assertEquals(2, fixture.recommendations.requests.size)
        // その曲の終わりが近づいたら、改めて選ぶ。
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(first)
        fixture.tick(playbackOf(otherUri, 200_000, 180_000))
        runCurrent()
        assertEquals(3, fixture.recommendations.requests.size)
    }

    @Test fun spotifyMovingOnByItselfAtTheEndSwitchesToThePreparedSong() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        fixture.tick(playbackOf(firstUri, 200_000, 195_000))
        runCurrent()
        // 切り替える前に、再生先が前に聴いていたプレイリストの続きへ進んだ。すぐ準備した曲へ替える。
        fixture.tick(playbackOf(otherUri, 200_000, 2_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        assertEquals(second, fixture.session.state.value.playing)
    }

    @Test fun changingTheSongMidwayDropsThePreparedSong() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 175_000))
        runCurrent()
        // 残りが十分あるうちに別の曲へ変わったのは、利用者の操作とみなす。
        fixture.tick(playbackOf(otherUri, 200_000, 2_000))
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        assertNull(fixture.session.state.value.upNextUri)
    }

    @Test fun aPreparedSongIsPlayedWhenPlaybackStopsBeforeTheSwitch() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        // 切り替える前に最後まで流れて止まった。新しく選ばずに、準備した曲を流す。
        fixture.time += 20_000
        fixture.tick(playbackOf(firstUri, 200_000, 0).copy(playing = false))
        runCurrent()
        assertEquals(2, fixture.recommendations.requests.size)
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        assertEquals(second, fixture.session.state.value.playing)
    }

    @Test fun missingPlaybackPositionDefersToTheEndOfTheSong() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        // 再生位置が取れない再生先では、再生が止まるまで次を決めない。
        fixture.tick(MusicPlayback(MusicTrack(firstUri, "Song", "Artist", "https://open.spotify.com/track/x", 200_000), true, "phone"))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
        fixture.tick(MusicPlayback(MusicTrack(firstUri, "Song", "Artist", "https://open.spotify.com/track/x", 200_000), false, "phone"))
        runCurrent()
        assertEquals(2, fixture.recommendations.requests.size)
    }

    @Test fun withoutASelectedDeviceNothingIsRequested() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.session.update(scenery, CabinState.NORMAL, null, MusicPlayback(), false)
        runCurrent()
        assertTrue(fixture.recommendations.requests.isEmpty())
        assertEquals(MusicFailure.NO_DEVICE, fixture.session.state.value.failure)
        assertFalse(fixture.session.state.value.enabled)
    }

    @Test fun deliversPlayedTracksAndStopToTheListener() = runTest {
        val events = mutableListOf<String>()
        val listener = object : BgmPlaybackListener {
            override fun onPlayed(track: RecommendedTrack, nowMillis: Long) { events += "played:${track.trackId}@$nowMillis" }
            override fun onStopped(nowMillis: Long) { events += "stopped@$nowMillis" }
        }
        val music = Music()
        val recommendations = Recommendations(BgmRecommendation(first)).apply { links = mapOf(found(first.trackId, firstUri)) }
        val session = BgmMusicSession(recommendations, music, this, { 1_000L }, listener = listener)
        session.start(members, listOf(PlaylistEntry("isrc:AAA", listOf("a"))))
        session.update(scenery, CabinState.NORMAL, "phone", MusicPlayback(), false)
        runCurrent()
        session.stop()
        assertEquals(listOf("stopped@1000", "played:isrc:AAA@1000", "stopped@1000"), events)
    }

    @Test fun pausingOurSongMidwayDoesNotPickTheNextOne() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        // 曲の途中で一時停止した。曲の終わりではないので、次の曲を選ばない。
        fixture.tick(playbackOf(firstUri, 200_000, 60_000).copy(playing = false))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
        // 再開して曲の終わりが近づいたら、いつもどおり次を選ぶ。
        fixture.time += 20_000
        fixture.tick(playbackOf(firstUri, 200_000, 180_000))
        runCurrent()
        assertEquals(2, fixture.recommendations.requests.size)
    }

    @Test fun pausingSomeoneElsesSongDoesNotStartOurs() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        fixture.tick(playbackOf("spotify:track:9123456789012345678901", 200_000, 50_000).copy(playing = false))
        runCurrent()
        assertEquals(1, fixture.recommendations.requests.size)
    }

    @Test fun aSongThatEndedIsFollowedByTheNextOne() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.time += 20_000
        // 最後まで流れて止まると、Spotify は位置を 0 に戻す。
        fixture.tick(playbackOf(firstUri, 200_000, 0).copy(playing = false))
        runCurrent()
        assertEquals(2, fixture.recommendations.requests.size)
    }

    @Test fun stoppingWhileMatchingTheSongDoesNotPlayIt() = runTest {
        val fixture = Fixture(this)
        fixture.recommendations.linkGate = kotlinx.coroutines.CompletableDeferred()
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        fixture.session.stop()
        fixture.recommendations.linkGate!!.complete(Unit)
        runCurrent()
        assertTrue(fixture.music.played.isEmpty())
    }

    @Test fun startingAgainPicksRightAwayEvenWhileAnotherSongPlays() = runTest {
        val fixture = Fixture(this)
        fixture.session.start(members, emptyList())
        fixture.tick()
        runCurrent()
        assertEquals(listOf(firstUri), fixture.music.played)
        fixture.session.stop()
        // 利用者が別の曲を流している間に、案内の開始などでもう一度始めた。
        fixture.time += 2_000
        fixture.recommendations.response = BgmRecommendation(second)
        fixture.session.start(members, emptyList())
        fixture.tick(playbackOf("spotify:track:9123456789012345678901", 200_000, 30_000))
        runCurrent()
        assertEquals(listOf(firstUri, secondUri), fixture.music.played)
        // 履歴は残っているので、同じドライブで流した曲は次の要求でも送られる。
        assertEquals(listOf(first.trackId), fixture.recommendations.requests.last().history.map { it.trackId })
    }
}
