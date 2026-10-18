package com.theacar.android.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Esp32LogTest {
    @Test
    fun measurementLineBecomesAirReading() {
        val air = Esp32Log.air("ガス 204.4kΩ 普段比 -0% 湿度 32.0% ")
        assertEquals(204.4, air?.gasKiloOhm)
        assertEquals(0.0, air?.dropPercent)
        assertEquals(32.0, air?.humidityPercent)
        assertEquals(false, air?.warmingUp)
    }

    @Test
    fun warmupSuffixIsKept() {
        val air = Esp32Log.air("ガス 160.1kΩ 普段比 -17% 湿度 34.3% (暖機中)")
        assertEquals(17.0, air?.dropPercent)
        assertTrue(air?.warmingUp == true)
    }

    @Test
    fun otherLinesAreNotReadings() {
        assertNull(Esp32Log.air("--- stepwgn-window v2.1 ---"))
        assertNull(Esp32Log.air("におい検知 → 窓を少し開ける"))
        assertNull(Esp32Log.air("ガス kΩ 普段比 -% 湿度 %"))
    }
}
