package com.theacar.android.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleLinesTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun aWholeLineArrivesAtOnce() {
        val lines = BleLines()
        assertEquals(listOf("STOP"), lines.feed(bytes("STOP\n")))
    }

    @Test
    fun aLineSplitAcrossChunksIsJoined() {
        val lines = BleLines()
        assertTrue(lines.feed(bytes("ガス 204.4kΩ 普")).isEmpty())
        assertTrue(lines.feed(bytes("段比 -0% 湿度 ")).isEmpty())
        assertEquals(listOf("ガス 204.4kΩ 普段比 -0% 湿度 32.0%"), lines.feed(bytes("32.0%\n")))
    }

    @Test
    fun multiByteCharactersSurviveASplitInTheMiddle() {
        // 「お」は UTF-8 で3バイト。断片の境界が文字の途中に来ても壊れてはいけない。
        val whole = bytes("におい検知\n")
        val lines = BleLines()
        assertTrue(lines.feed(whole.copyOfRange(0, 4)).isEmpty())
        assertEquals(listOf("におい検知"), lines.feed(whole.copyOfRange(4, whole.size)))
    }

    @Test
    fun severalLinesInOneChunkAreAllReturned() {
        val lines = BleLines()
        assertEquals(
            listOf("UP ON (500 ms)", "UP OFF", "STOP"),
            lines.feed(bytes("UP ON (500 ms)\nUP OFF\nSTOP\n")),
        )
    }

    @Test
    fun aNewlineOnTheChunkBoundaryClosesTheLine() {
        val lines = BleLines()
        assertEquals(listOf("UP OFF"), lines.feed(bytes("UP OFF")) + lines.feed(bytes("\n")))
    }

    @Test
    fun carriageReturnsAreDropped() {
        val lines = BleLines()
        assertEquals(listOf("STOP"), lines.feed(bytes("STOP\r\n")))
    }

    @Test
    fun aLineWithoutAnEndingIsHeldBack() {
        val lines = BleLines()
        assertTrue(lines.feed(bytes("--- stepwgn-window v3.0 ---")).isEmpty())
    }

    @Test
    fun anEndlessLineDoesNotGrowForever() {
        val lines = BleLines(maxLineBytes = 16)
        repeat(100) { lines.feed(bytes("0123456789")) }
        assertEquals(listOf("0123456789012345"), lines.feed(bytes("\n")))
    }

    @Test
    fun theParserStillReadsAirFromAReassembledLine() {
        // 組み立て直した行がそのまま Esp32Log に渡せること（パーサは無変更で流用する）
        val lines = BleLines()
        lines.feed(bytes("ガス 160.1kΩ 普段比 "))
        val reassembled = lines.feed(bytes("-17% 湿度 34.3% (暖機中)\n")).single()
        val air = Esp32Log.air(reassembled)
        assertEquals(160.1, air?.gasKiloOhm)
        assertEquals(17.0, air?.dropPercent)
        assertEquals(34.3, air?.humidityPercent)
        assertTrue(air?.warmingUp == true)
    }
}
