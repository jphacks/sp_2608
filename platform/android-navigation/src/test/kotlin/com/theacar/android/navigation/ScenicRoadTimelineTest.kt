package com.theacar.android.navigation

import com.theacar.route.*
import kotlin.test.*

class ScenicRoadTimelineTest {
    private val signature = ScenicRecommendedRoad("signature", "認定区間", ScenicRoadRank.SIGNATURE, listOf("source"))
    private val panorama = ScenicRecommendedRoad("panorama", "海岸道路", ScenicRoadRank.PANORAMA, listOf("source"))
    private fun block(start: Double, end: Double, vararg roads: ScenicRecommendedRoad) =
        ScenicTimelineBlock("sea", ScenicRange(start, end), .3, roads.toList())

    @Test fun contiguousBlocksMergeButMissingRoadSectionsRemainSeparate() {
        val roads = scenicRoadHighlights(listOf(block(0.0, 100.0, signature), block(100.0, 200.0, signature),
            block(200.0, 300.0), block(300.0, 400.0, signature)))
        assertEquals(listOf(ScenicRange(0.0, 200.0), ScenicRange(300.0, 400.0)), roads.single().ranges)
        assertTrue(scenicRoadHits(roads, ScenicRoadRank.SIGNATURE, 250.0, 0.0).isEmpty())
    }

    @Test fun overlapsKeepAllNamesWhenShownOnASingleLine() {
        val other = panorama.copy(id = "other", name = "別の道路")
        val roads = scenicRoadHighlights(listOf(block(0.0, 100.0, panorama, other, signature)))
        assertEquals(listOf("signature", "other", "panorama"), roads.map { it.road.id })
        assertEquals(2, scenicRoadHits(roads, ScenicRoadRank.PANORAMA, 50.0, 0.0).size)
        assertEquals(signature, scenicRoadHits(roads, ScenicRoadRank.SIGNATURE, 50.0, 0.0).single().road)
        assertEquals(3, scenicRoadHits(roads, 50.0, 0.0).size)
    }

    @Test fun touchToleranceDoesNotChangeGeometryOrMatchDistantRoads() {
        val roads = scenicRoadHighlights(listOf(block(1000.0, 1100.0, signature)))
        assertEquals(1, scenicRoadHits(roads, ScenicRoadRank.SIGNATURE, 990.0, 12.0).size)
        assertTrue(scenicRoadHits(roads, ScenicRoadRank.SIGNATURE, 900.0, 12.0).isEmpty())
        assertEquals(listOf(ScenicRange(1000.0, 1100.0)), roads.single().ranges)
    }

    @Test fun emptyAndZeroLengthRoadsDoNotCreateMarkers() {
        assertTrue(scenicRoadHighlights(emptyList()).isEmpty())
        assertTrue(scenicRoadHighlights(listOf(block(0.0, 0.0, signature))).isEmpty())
    }
}
