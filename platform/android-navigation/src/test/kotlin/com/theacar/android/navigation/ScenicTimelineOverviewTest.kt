package com.theacar.android.navigation

import com.theacar.route.ScenicRange
import com.theacar.route.ScenicTimelineBlock
import kotlin.test.*

class ScenicTimelineOverviewTest {
    private fun block(category: String, start: Double, end: Double) =
        ScenicTimelineBlock(category, ScenicRange(start, end), .3)

    @Test fun frequentShortChangesAreSummarizedWithoutChangingCategoryTotals() {
        val blocks = (0 until 1000).flatMap { index ->
            val start = index * 100.0
            listOf(block("forest", start, start + 90), block("sea", start + 90, start + 100))
        }
        val sections = scenicTimelineOverview(blocks)
        assertTrue(sections.size <= 24)
        assertTrue(sections.all { it.category == "forest" })
        assertEquals(ScenicRange(0.0, 100000.0), sections.single().range)
        assertEquals(listOf(ScenicRange(0.0, 100000.0)), sections.single().sourceRanges)
        assertEquals(listOf("forest" to 90000.0, "sea" to 10000.0), scenicCategoryDistances(blocks))
    }

    @Test fun longSceneryChangesRemainInDistanceOrder() {
        val sections = scenicTimelineOverview(listOf(block("forest", 0.0, 24000.0), block("sea", 24000.0, 48000.0)))
        assertEquals(listOf("forest", "sea"), sections.map { it.category })
        assertEquals(listOf(ScenicRange(0.0, 24000.0), ScenicRange(24000.0, 48000.0)), sections.map { it.range })
    }

    @Test fun shortSeaAccentsKeepExactPositionsWithoutFillingGaps() {
        val blocks = listOf(block("forest", 0.0, 900.0), block("sea", 900.0, 910.0),
            block("sea", 910.0, 920.0), block("sea", 950.0, 960.0), block("sea", 990.0, 990.0))
        assertEquals(listOf(ScenicRange(900.0, 920.0), ScenicRange(950.0, 960.0)), scenicSeaRanges(blocks))
        assertTrue(scenicSeaRanges(emptyList()).isEmpty())
        assertEquals("forest", scenicTimelineOverview(blocks, 1).single().category)
    }

    @Test fun missingGeometryIsNotInventedForMapHighlights() {
        val sections = scenicTimelineOverview(listOf(block("forest", 0.0, 100.0), block("forest", 900.0, 1000.0)), 1)
        assertEquals("unknown", sections.single().category)
        assertEquals(listOf(ScenicRange(0.0, 100.0), ScenicRange(900.0, 1000.0)), sections.single().sourceRanges)
    }

    @Test fun emptyAndZeroLengthTimelinesHaveNoSections() {
        assertTrue(scenicTimelineOverview(emptyList()).isEmpty())
        assertTrue(scenicTimelineOverview(listOf(block("forest", 0.0, 0.0))).isEmpty())
        assertFailsWith<IllegalArgumentException> { scenicTimelineOverview(emptyList(), 0) }
    }

    @Test fun subKilometerSceneryDoesNotDisplayAsZero() {
        assertEquals("1km未満", scenicDistanceLabel(450.0))
        assertEquals("12km", scenicDistanceLabel(12340.0))
    }
}
