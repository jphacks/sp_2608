package com.theacar.auto

import com.theacar.model.Coordinate
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationProgress
import com.theacar.navigation.NavigationState
import com.theacar.route.Destination
import com.theacar.route.RouteAlternative
import com.theacar.route.RouteExplanation
import com.theacar.route.RoutePlan
import com.theacar.route.ScenicRange
import com.theacar.route.ScenicReason
import com.theacar.route.ScenicRecommendedRoad
import com.theacar.route.ScenicRoadRank
import com.theacar.route.ScenicTimelineBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CarSceneryTest {
    private val points = List(4) { Coordinate(it * 0.01, 0.0) }
    private val route = RoutePlan("route", Destination("end", "End", points.last()), 40_000, 2_400, points)
    private val explanation = RouteExplanation(
        role = "海を楽しむ", snapshotId = "snap", revision = 1, totalSeconds = 2_400, stopSeconds = 0,
        reasons = listOf(
            ScenicReason("r1", ScenicReason.SEA_REVEAL, "海が見え始める", listOf("e1"), listOf(ScenicRange(6_000.0, 6_300.0))),
        ),
        timeline = listOf(
            ScenicTimelineBlock("forest", ScenicRange(0.0, 6_000.0), .3),
            ScenicTimelineBlock("sea", ScenicRange(6_000.0, 20_000.0), .6,
                listOf(ScenicRecommendedRoad("road-2", "景観ロードの名前", ScenicRoadRank.SCENIC, listOf("osm")),
                    ScenicRecommendedRoad("road-1", "秀逸な道の名前", ScenicRoadRank.SIGNATURE, listOf("osm")))),
            ScenicTimelineBlock("townscape", ScenicRange(36_000.0, 40_000.0), .3),
        ),
        warnings = emptyList(), sources = emptyList(), searchLimited = false, baselineSeconds = 2_000, load = .4,
    )

    private fun guiding(remainingMeters: Int) = NavigationState(
        phase = NavigationPhase.GUIDING, route = route, sessionId = 1,
        progress = NavigationProgress(remainingMeters, 2_400),
        alternatives = listOf(RouteAlternative(route, null, explanation)))

    @Test fun takesTheTimelineAndThePositionAlongTheRoute() {
        val scenery = assertNotNull(carScenery(guiding(30_000)))
        assertEquals(40_000.0, scenery.totalMeters)
        assertEquals(10_000.0, scenery.positionMeters)
        assertEquals(listOf("forest", "sea", "townscape"), scenery.blocks.map { it.category })
    }

    @Test fun namesOnlyTheHighestRankedRoadOfTheSectionBeingDriven() {
        val scenery = assertNotNull(carScenery(guiding(30_000)))
        val road = assertNotNull(scenery.road)
        assertEquals("秀逸な道の名前", road.name)
        assertEquals(ScenicRoadRank.SIGNATURE, road.rank)
        // 森の区間には認定道路が無いので名前は出ない
        assertNull(assertNotNull(carScenery(guiding(38_000))).road)
    }

    @Test fun announcesTheSeaRevealOnlyOnceItIsClose() {
        // 残り 40km のときは地点まで 6km あり、まだ予告しない
        assertNull(assertNotNull(carScenery(guiding(40_000))).revealAtMeters)
        // 残り 35km なら地点まで 1km で、予告する
        val close = assertNotNull(carScenery(guiding(35_000)))
        assertEquals(6_000.0, close.revealAtMeters)
        assertEquals(1_000.0, close.revealMeters)
        // 通り過ぎたら出さない
        assertNull(assertNotNull(carScenery(guiding(30_000))).revealAtMeters)
    }

    @Test fun staysAwayWhileThereIsNoSceneryToShow() {
        assertNull(carScenery(guiding(30_000).copy(phase = NavigationPhase.PREVIEW)))
        assertNull(carScenery(guiding(30_000).copy(rerouting = true)))
        // 景観の分からない経路に入れ替わったとき
        assertNull(carScenery(guiding(30_000).copy(alternatives = listOf(RouteAlternative(route, null, null)))))
    }

    @Test fun addsUpEachLandscapeBeforeTheDriveStarts() {
        // 走り出す前なので、現在位置は無い。確認中の経路に何がどれだけ含まれるかだけを見る。
        val preview = guiding(40_000).copy(phase = NavigationPhase.PREVIEW, progress = null)
        val shares = carSceneryShares(preview)
        assertEquals(listOf("sea", "forest", "townscape"), shares.map { it.category })
        assertEquals(listOf(14_000.0, 6_000.0, 4_000.0), shares.map { it.meters })
        assertEquals(listOf("海に近い 14km ・ 森林 6km", "街並み 4km"), carSceneryBreakdownLines(shares, 2, 2))
        // 1行しか置けない画面では、長い区間だけが残る
        assertEquals(listOf("海に近い 14km ・ 森林 6km"), carSceneryBreakdownLines(shares, 1, 2))
    }

    @Test fun leavesOutSectionsTooShortToBeWorthReading() {
        val short = explanation.copy(timeline = explanation.timeline + ScenicTimelineBlock("river", ScenicRange(20_000.0, 20_300.0), .4))
        val preview = guiding(40_000).copy(phase = NavigationPhase.PREVIEW, progress = null,
            alternatives = listOf(RouteAlternative(route, null, short)))
        assertEquals(listOf("sea", "forest", "townscape"), carSceneryShares(preview).map { it.category })
    }

    @Test fun keepsQuietWhileTheLandscapeOfTheRouteIsUnknown() {
        val preview = guiding(40_000).copy(phase = NavigationPhase.PREVIEW, progress = null)
        assertEquals(emptyList(), carSceneryShares(preview.copy(rerouting = true)))
        assertEquals(emptyList(), carSceneryShares(preview.copy(alternatives = listOf(RouteAlternative(route, null, null)))))
        assertEquals(emptyList(), carSceneryBreakdownLines(emptyList(), 2, 2))
    }


    @Test fun paintsTheSeaAndTheForestApart() {
        assertEquals(0xFF6FA6D6.toInt(), carSceneryColor("sea"))
        assertEquals(0xFF75B1A9.toInt(), carSceneryColor("forest"))
    }
}
