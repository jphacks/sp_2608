package com.theacar.memory.records

import kotlin.test.Test
import kotlin.test.assertEquals

class RoadRecordBookTest {
    private val song = RecordSong("spotify:track:abc", "海辺の曲", "だれか", "https://open.spotify.com/track/abc")

    @Test fun firstUnlockWaitsForCelebrationAndKeepsTheFirstSong() {
        val book = RoadRecordBook()
            .unlock("golden-road", 100, song)
            .unlock("golden-road", 200, RecordSong("spotify:track:other", "ほかの曲", "べつのひと", "https://open.spotify.com/track/other"))
        val entry = book.entries.getValue("golden-road")
        assertEquals(100, entry.firstUnlockedAtEpochMillis)
        assertEquals(200, entry.lastUnlockedAtEpochMillis)
        assertEquals(2, entry.timesDriven)
        assertEquals(song, entry.song)
        assertEquals(listOf("golden-road"), book.pendingCelebration)
        assertEquals(emptyList(), book.celebrated(listOf("golden-road")).pendingCelebration)
    }

    @Test fun songIsAddedLaterIfTheFirstDriveHadNone() {
        val book = RoadRecordBook().unlock("ororon", 1, null).unlock("ororon", 2, song)
        assertEquals(song, book.entries.getValue("ororon").song)
    }

    @Test fun roundTripsThroughJson() {
        val book = RoadRecordBook().unlock("golden-road", 100, song).unlock("ororon", 300, null).celebrated(listOf("ororon"))
        assertEquals(book, RoadRecordBook.parse(book.toJson()))
    }

    @Test fun everyRecordHasAUniqueRegistryId() {
        assertEquals(15, RoadRecords.map { it.id }.toSet().size)
    }
}
