package com.theacar.android.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Esp32LogSmellTest {
    @Test
    fun theEventLineCarriesTheDrop() {
        assertEquals(42.0, Esp32Log.smellDrop("EVENT smell drop=42"))
        assertEquals(20.0, Esp32Log.smellDrop("EVENT smell drop=20"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        // BleLines は改行で切るだけなので、前後に空白が残ることがある
        assertEquals(31.0, Esp32Log.smellDrop("  EVENT smell drop=31  "))
    }

    @Test
    fun theHumanReadableLineIsNotAnEvent() {
        // 表示用の文言を変えても検知が壊れないよう、EVENT 行だけを見る
        assertNull(Esp32Log.smellDrop("におい検知 → 窓を少し開ける"))
    }

    @Test
    fun otherLinesAreNotEvents() {
        listOf(
            "ガス 204.4kΩ 普段比 -0% 湿度 32.0%",
            "DOWN(auto) ON (1500 ms)",
            "--- stepwgn-window v3.0 ---",
            "EVENT smell",
            "EVENT smell drop=",
            "EVENT noise drop=42",
            "EVENT smell drop=42 ほか",
        ).forEach { assertNull(Esp32Log.smellDrop(it), "検知として扱ってはいけない: $it") }
    }

    @Test
    fun aMeasurementLineIsNotAnEventAndTheReverse() {
        assertNull(Esp32Log.air("EVENT smell drop=42"))
        assertNull(Esp32Log.smellDrop("ガス 160.1kΩ 普段比 -17% 湿度 34.3%"))
    }
}
