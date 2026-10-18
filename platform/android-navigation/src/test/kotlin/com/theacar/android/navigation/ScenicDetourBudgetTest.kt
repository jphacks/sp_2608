package com.theacar.android.navigation

import com.theacar.route.ScenicOptions
import kotlin.test.*

class ScenicDetourBudgetTest {
    @Test fun preservesShortDetoursAndExtendsToSixHours() {
        assertEquals((0..30 step 5).toList(), scenicDetourMinutes.take(7))
        assertEquals(listOf(45, 60, 90, 120, 180, 240, 300, 360), scenicDetourMinutes.drop(7))
        assertTrue(scenicDetourMinutes.zipWithNext().all { (a, b) -> a < b })
        scenicDetourMinutes.forEach { minutes ->
            assertEquals(minutes, scenicDetourMinutes[scenicDetourIndex(minutes)])
            assertEquals(minutes * 60, ScenicOptions(extraSeconds = minutes * 60).extraSeconds)
        }
    }

    @Test fun sliderPositionStaysInRangeForRestoredValues() {
        assertEquals(0, scenicDetourIndex(-1))
        assertEquals(scenicDetourMinutes.lastIndex, scenicDetourIndex(500))
        assertEquals(60, scenicDetourMinutes[scenicDetourIndex(65)])
    }

    @Test fun longBudgetsUseHoursInTheirLabel() {
        assertEquals("0分", scenicDetourLabel(0))
        assertEquals("15分", scenicDetourLabel(15))
        assertEquals("1時間", scenicDetourLabel(60))
        assertEquals("1時間30分", scenicDetourLabel(90))
        assertEquals("6時間", scenicDetourLabel(360))
    }
}
