package com.theacar.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerProgressTest {
    private val progress = MiniPlayerProgress(positionMillis = 60_000, durationMillis = 200_000, anchorMillis = 1_000, playing = true)

    @Test fun playingMovesOnWithTheClockBetweenReports() {
        assertEquals(65_000, progress.positionAt(6_000))
    }

    @Test fun pausedStaysWhereItWas() {
        assertEquals(60_000, progress.copy(playing = false).positionAt(30_000))
    }

    @Test fun neverRunsPastTheEndOrBeforeTheReport() {
        assertEquals(200_000, progress.positionAt(1_000_000))
        assertEquals(60_000, progress.positionAt(0))
    }

    @Test fun timesAreMinutesAndSeconds() {
        assertEquals("0:00", formatSongTime(0))
        assertEquals("1:05", formatSongTime(65_999))
        assertEquals("63:20", formatSongTime(3_800_000))
    }
}
