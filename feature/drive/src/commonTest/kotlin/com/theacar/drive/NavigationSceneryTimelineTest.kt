package com.theacar.drive

import com.theacar.model.Coordinate
import com.theacar.navigation.*
import com.theacar.route.*
import kotlin.test.*

class NavigationSceneryTimelineTest {
    private val points = List(4) { Coordinate(it * 0.01, 0.0) }
    private val route = RoutePlan("route", Destination("end", "End", points.last()), 40_000, 2_400, points)
    private val explanation = RouteExplanation(
        role = "海を楽しむ", snapshotId = "snap", revision = 1, totalSeconds = 2_400, stopSeconds = 0,
        reasons = listOf(
            ScenicReason("r1", "sea_proximity", "海に近い", listOf("e1"), listOf(ScenicRange(0.0, 5_000.0))),
            ScenicReason("r2", ScenicReason.SEA_REVEAL, "海が見え始める", listOf("e2"), listOf(ScenicRange(6_000.0, 6_300.0))),
        ),
        timeline = listOf(
            ScenicTimelineBlock("forest", ScenicRange(0.0, 6_000.0), .3),
            ScenicTimelineBlock("sea", ScenicRange(6_000.0, 20_000.0), .6,
                listOf(ScenicRecommendedRoad("road-2", "景観ロードの名前", ScenicRoadRank.SCENIC, listOf("osm")),
                    ScenicRecommendedRoad("road-1", "秀逸な道の名前", ScenicRoadRank.SIGNATURE, listOf("osm"))),
                openness = .8, visibilityVerified = true, arrivalTime = "2026-10-17T15:10:00+09:00"),
            ScenicTimelineBlock("townscape", ScenicRange(36_000.0, 40_000.0), .3),
        ),
        warnings = emptyList(), sources = emptyList(), searchLimited = false, baselineSeconds = 2_000, load = .4,
    )
    private val guiding = NavigationState(phase = NavigationPhase.GUIDING, route = route, sessionId = 1,
        progress = NavigationProgress(40_000, 2_400), alternatives = listOf(RouteAlternative(route, null, explanation)))

    @Test fun convertsBlocksWithoutRoadNamesAndKeepsOnlyTheTopRank() {
        val scenery = assertNotNull(NavigationSceneryTimeline { guiding }.current())
        assertEquals(0.0, scenery.positionMeters)
        val sea = scenery.blocks.single { it.category == "sea" }
        assertEquals("signature", sea.recommendedRank)
        assertEquals(.8, sea.openness)
        assertTrue(sea.visibilityVerified == true)
        assertEquals("2026-10-17T15:10:00+09:00", sea.arrivalTime)
        assertEquals(.6, sea.confidence)
    }

    @Test fun passesTheScenicRouteIdOnlyForScenicEngineRoutes() {
        assertNull(NavigationSceneryTimeline { guiding }.current()!!.routeId)
        val id = "route_" + "a".repeat(32)
        val scenic = route.copy(id = id)
        val state = guiding.copy(route = scenic, alternatives = listOf(RouteAlternative(scenic, null, explanation)))
        assertEquals(id, NavigationSceneryTimeline { state }.current()!!.routeId)
    }

    @Test fun positionIsTheDistanceAlreadyDriven() {
        val state = guiding.copy(progress = NavigationProgress(25_000, 1_500))
        assertEquals(15_000.0, NavigationSceneryTimeline { state }.current()!!.positionMeters)
    }

    @Test fun onlyBlocksAroundTheCurrentPositionAreHandedOver() {
        val near = NavigationSceneryTimeline { guiding }.current()!!
        assertTrue(near.blocks.none { it.category == "townscape" })
        val late = guiding.copy(progress = NavigationProgress(2_000, 120))
        assertTrue(NavigationSceneryTimeline { late }.current()!!.blocks.any { it.category == "townscape" })
    }

    @Test fun onlyRevealReasonsAheadOfThePositionAreHandedOver() {
        assertEquals(listOf(6_000.0), NavigationSceneryTimeline { guiding }.current()!!.revealStartMeters)
        val passed = guiding.copy(progress = NavigationProgress(30_000, 1_800))
        assertTrue(NavigationSceneryTimeline { passed }.current()!!.revealStartMeters.isEmpty())
    }

    @Test fun rerouteAndPreviewAndMissingExplanationHandOverNothing() {
        var state = guiding.copy(phase = NavigationPhase.PREVIEW)
        val source = NavigationSceneryTimeline { state }
        assertNull(source.current())
        state = guiding.copy(rerouting = true)
        assertNull(source.current())
        // 再探索で差し替わった経路の景観は手元に無い。
        state = guiding.copy(route = route.copy(id = "replacement"))
        assertNull(source.current())
        state = guiding
        assertNotNull(source.current())
    }

    @Test fun progressIsRequiredBeforeAnyRecommendation() {
        assertNull(NavigationSceneryTimeline { guiding.copy(progress = null) }.current())
    }
}
