package com.theacar.memory.records

import com.theacar.location.LocationFix
import com.theacar.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadCoverageTest {
    // 北へまっすぐ 10km の道（緯度 0.0009 度 ≒ 100m）
    private val long = RoadShape("long", listOf(List(101) { Coordinate(42.0 + it * .0009, 143.0) }))
    // 東へ 2km の短い橋
    private val short = RoadShape("short", listOf(List(21) { Coordinate(41.8, 140.7 + it * .0012) }))

    private class Driver(val coverage: RoadCoverage) {
        var clock = 1_000_000L
        val unlocked = mutableListOf<String>()
        fun drive(points: List<Coordinate>, secondsApart: Int = 3) = points.forEach { p ->
            clock += secondsApart * 1_000L
            unlocked += coverage.onLocation(LocationFix(p, 8f, clock), clock).unlocked
        }
    }

    private fun northTo(fraction: Double) = (0..(fraction * 100).toInt()).map { Coordinate(42.0 + it * .0009, 143.0) }

    @Test fun drivingMostOfARoadUnlocksIt() {
        val driver = Driver(RoadCoverage(listOf(long)))
        driver.drive(northTo(.75))
        assertEquals(listOf("long"), driver.unlocked)
    }

    @Test fun drivingHalfOfARoadDoesNot() {
        val driver = Driver(RoadCoverage(listOf(long)))
        driver.drive(northTo(.5))
        assertTrue(driver.unlocked.isEmpty())
        assertTrue(driver.coverage.progress().getValue("long") in .45..0.55)
    }

    @Test fun shortRoadsNeedNearlyAll() {
        val driver = Driver(RoadCoverage(listOf(short)))
        driver.drive((0..16).map { Coordinate(41.8, 140.7 + it * .0012) }, secondsApart = 5)
        assertTrue(driver.unlocked.isEmpty(), "80% of a short road is not enough")
        driver.drive((17..20).map { Coordinate(41.8, 140.7 + it * .0012) }, secondsApart = 5)
        assertEquals(listOf("short"), driver.unlocked)
    }

    @Test fun sparseFixesAreJoinedButJumpsAreIgnored() {
        val driver = Driver(RoadCoverage(listOf(long)))
        // 位置が 20 秒に1回（約 500m おき）でも、間をつないで数える
        driver.drive((0..20).map { Coordinate(42.0 + it * .0045, 143.0) }, secondsApart = 20)
        assertEquals(listOf("long"), driver.unlocked)

        val jumpy = Driver(RoadCoverage(listOf(long)))
        // 1 秒で道の端から端へ飛ぶ位置は捨てる
        jumpy.drive(listOf(Coordinate(42.0, 143.0), Coordinate(42.09, 143.0)), secondsApart = 1)
        assertTrue(jumpy.unlocked.isEmpty())
    }

    @Test fun inaccurateOrStaleFixesDoNotCount() {
        val coverage = RoadCoverage(listOf(short))
        val update = coverage.onLocation(LocationFix(Coordinate(41.8, 140.7), 80f, 1_000), 1_000)
        assertTrue(update.near.isEmpty())
        val stale = coverage.onLocation(LocationFix(Coordinate(41.8, 140.7), 5f, 1_000), 60_000)
        assertTrue(stale.near.isEmpty())
    }

    @Test fun drivingNextToTheRoadDoesNotCount() {
        val driver = Driver(RoadCoverage(listOf(long)))
        // 100m 東の並行する道
        driver.drive((0..100).map { Coordinate(42.0 + it * .0009, 143.0012) })
        assertTrue(driver.unlocked.isEmpty())
    }

    @Test fun eachRoadUnlocksOncePerDrive() {
        val driver = Driver(RoadCoverage(listOf(long)))
        driver.drive(northTo(1.0))
        driver.drive(northTo(1.0).reversed())
        assertEquals(listOf("long"), driver.unlocked)
        driver.coverage.reset()
        driver.drive(northTo(1.0))
        assertEquals(listOf("long", "long"), driver.unlocked)
    }

    @Test fun readsTheRegistryGeoJson() {
        val shapes = parseRoadShapes("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"id":"golden-road"},
            "geometry":{"type":"MultiLineString","coordinates":[[[143.3,42.2],[143.31,42.21]],[[143.4,42.3],[143.41,42.31]]]}}]}""")
        assertEquals("golden-road", shapes.single().id)
        assertEquals(Coordinate(42.2, 143.3), shapes.single().parts.first().first())
        assertEquals(2, shapes.single().parts.size)
    }
}
