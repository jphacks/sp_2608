package com.theacar.music

import kotlinx.serialization.json.*
import kotlin.test.*

class DriveMusicLogTest {
    private val a = RecommendedTrack("isrc:A", "A", "X")
    private val b = RecommendedTrack("isrc:B", "B", "Y")

    @Test fun closesThePreviousTrackWhenTheNextStarts() {
        val log = DriveMusicLog { "id1" }
        log.start(0)
        log.onTrackStarted(a, 1_000)
        log.onTrackStarted(b, 5_000)
        val record = log.finish(9_000)!!
        assertEquals("id1", record.id)
        assertEquals(listOf(1_000L to 5_000L, 5_000L to 9_000L),
            record.tracks.map { it.startedAtEpochMillis to it.endedAtEpochMillis })
        assertFalse(log.active)
    }

    @Test fun countsSpeechRatioPerMinute() {
        val log = DriveMusicLog()
        log.start(0)
        repeat(4) { log.onSecond(60_000L + it * 1_000, it < 1) }
        log.onSecond(120_000, true)
        val mood = log.finish(130_000)!!.mood
        assertEquals(listOf(60_000L, 120_000L), mood.map { it.atEpochMillis })
        assertEquals(.25, mood[0].speechRatio)
        assertEquals(1.0, mood[1].speechRatio)
    }

    @Test fun returnsNullWhenNothingWasRecordedOrNotStarted() {
        val log = DriveMusicLog()
        assertNull(log.finish(1))
        log.start(0)
        assertNull(log.finish(1))
    }

    @Test fun startDiscardsPreviousContent() {
        val log = DriveMusicLog()
        log.start(0)
        log.onTrackStarted(a, 1)
        log.start(10)
        assertNull(log.finish(20))
    }

    @Test fun dropsOldestBeyondTheLimits() {
        val log = DriveMusicLog()
        log.start(0)
        repeat(DriveMusicLog.MAX_TRACKS + 3) { log.onTrackStarted(a.copy(trackId = "t$it"), it.toLong()) }
        repeat(DriveMusicLog.MAX_MINUTES + 2) { log.onSecond(it * 60_000L, false) }
        val record = log.finish(10_000_000)!!
        assertEquals(DriveMusicLog.MAX_TRACKS, record.tracks.size)
        assertEquals("t3", record.tracks.first().trackId)
        assertEquals(DriveMusicLog.MAX_MINUTES, record.mood.size)
        assertEquals(2 * 60_000L, record.mood.first().atEpochMillis)
    }

    @Test fun jsonUsesTheFixedKeysAndRoundTrips() {
        val log = DriveMusicLog { "id1" }
        log.start(0)
        log.onTrackStarted(a, 1_000)
        log.onSecond(60_000, true)
        val record = log.finish(70_000)!!
        val json = Json.parseToJsonElement(record.toJson("dev")).jsonObject
        assertEquals("dev", json.getValue("deviceId").jsonPrimitive.content)
        val track = json.getValue("tracks").jsonArray[0].jsonObject
        assertEquals(JsonNull, track.getValue("genre"))
        assertEquals(setOf("trackId", "title", "artist", "genre", "startedAtEpochMillis", "endedAtEpochMillis"), track.keys)
        assertEquals(setOf("atEpochMillis", "speechRatio"), json.getValue("mood").jsonArray[0].jsonObject.keys)
        assertEquals(record, parseMusicLogRecord(record.toJson("dev")))
    }
}
