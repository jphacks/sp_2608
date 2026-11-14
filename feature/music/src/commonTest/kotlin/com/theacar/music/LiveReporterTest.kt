package com.theacar.music

import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveReporterTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUv"
    private val track = RecommendedTrack("isrc:AAA", "海の曲", "A", 200_000, message = "海が近いので")

    private class Sender : LiveSender {
        val sent = mutableListOf<Pair<LiveSnapshot, List<Pair<Double, Double>>?>>()
        var ok = true
        override suspend fun send(code: String, snapshot: LiveSnapshot, geometry: List<Pair<Double, Double>>?): Boolean {
            sent += snapshot to geometry
            return ok
        }
    }

    private inner class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        var time = 1_800_000_000_000
        var route: LiveRoute? = route("r1")
        var evaluation: LiveEvaluation? = null
        val sender = Sender()
        val reporter = LiveReporter(sender, scope, { time }, { route }, { LiveCar(35.3, 139.48, 40.0, 90.0) },
            { CabinState.NORMAL }, { null }, { evaluation })
    }

    private fun route(key: String, position: Double = 1_000.0, arrived: Boolean = false) = LiveRoute(key, "江の島", 10_000.0, position,
        listOf(LiveSegment(0.0, 10_000.0, "sea")), listOf(139.4 to 35.3, 139.5 to 35.31), arrived)

    @Test fun sendsTheRouteShapeOnlyWhenTheRouteChanges() = runTest {
        val f = Fixture(this)
        f.reporter.tick(code)
        f.reporter.tick(code)
        assertEquals(2, f.sender.sent[0].second?.size)
        assertNull(f.sender.sent[1].second)
        f.route = route("r2", position = 0.0)
        f.reporter.tick(code)
        assertNotNull(f.sender.sent[2].second)
    }

    @Test fun retriesTheShapeWhenTheFirstSendFailed() = runTest {
        val f = Fixture(this)
        f.sender.ok = false
        f.reporter.tick(code)
        assertTrue(f.reporter.state.value.failing)
        f.sender.ok = true
        f.reporter.tick(code)
        assertNotNull(f.sender.sent[1].second)
        assertFalse(f.reporter.state.value.failing)
    }

    @Test fun sendsNothingOutsideGuidanceAndArrivalOnlyOnce() = runTest {
        val f = Fixture(this)
        f.route = null
        f.reporter.tick(code)
        assertTrue(f.sender.sent.isEmpty())
        f.route = route("r1", 10_000.0, arrived = true)
        f.reporter.tick(code)
        f.reporter.tick(code)
        assertEquals(1, f.sender.sent.size)
        assertTrue(f.sender.sent.single().first.arrived)
    }

    @Test fun keepsPlayedSongsWithTheSceneAndThemeOfTheDecision() = runTest {
        val f = Fixture(this)
        f.evaluation = LiveEvaluation(f.time, true, BgmEvaluation(shares = mapOf("urban" to .3, "sea" to .7),
            candidates = listOf(BgmCandidateScore(track.trackId, track.title, track.artist, -.1, .4, .2, "sea", .8))), track)
        f.reporter.onBgmTrackStarted(track, track.message)
        f.route = route("r1", position = 3_000.0)
        f.reporter.onBgmTrackStarted(RecommendedTrack("isrc:BBB", "次の曲", "B"), null)
        f.reporter.tick(code)
        val played = f.sender.sent.single().first.played
        assertEquals(listOf("次の曲", "海の曲"), played.map { it.title })
        assertEquals("sea", played[1].scene)
        assertEquals("sea", played[1].theme)
        assertEquals(1_000.0, played[1].startMeters)
        assertEquals(3_000.0, played[1].endMeters)
        // 決めたときの評価の曲でなければ、景色も題材も分からない
        assertNull(played[0].scene)
    }

    @Test fun forgetsSongPositionsWhenTheRouteIsReplaced() = runTest {
        val f = Fixture(this)
        f.reporter.tick(code)
        f.reporter.onBgmTrackStarted(track, null)
        f.route = route("r2", position = 0.0)
        f.reporter.tick(code)
        val played = f.sender.sent.last().first.played.single()
        assertNull(played.startMeters)
    }

    @Test fun reportsTheShareOfSecondsWithSpeech() = runTest {
        val f = Fixture(this)
        repeat(3) { f.reporter.observeSpeech(true); f.time += 1_000 }
        f.reporter.observeSpeech(false)
        f.reporter.tick(code)
        val cabin = assertNotNull(f.sender.sent.single().first.cabin)
        assertEquals(.75, cabin.speechRatio)
        assertFalse(cabin.speaking)
    }

    @Test fun sendsEveryIntervalUntilStopped() = runTest {
        val f = Fixture(backgroundScope)
        f.reporter.start(code)
        runCurrent()
        advanceTimeBy(4_500)
        assertEquals(3, f.sender.sent.size)
        f.reporter.stop()
        advanceTimeBy(10_000)
        assertEquals(3, f.sender.sent.size)
        assertFalse(f.reporter.state.value.enabled)
    }
}
