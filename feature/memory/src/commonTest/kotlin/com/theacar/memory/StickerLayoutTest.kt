package com.theacar.memory

import com.theacar.model.Coordinate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerLayoutTest {
    private fun assertNear(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-6, "expected $expected but was $actual")

    @Test fun wideRouteFillsWidthWithMarginAndStaysCentered() {
        // 赤道付近で東西に長い経路。横幅いっぱい（余白 12%）に広がり、縦は中央に寄る
        val route = listOf(Coordinate(0.0, 0.0), Coordinate(0.1, 1.0))
        val projection = projectRoute(route, aspect = 1.0)
        assertNear(0.12, projection.route[0].x)
        assertNear(0.88, projection.route[1].x)
        assertNear(0.5 + 0.05 * 0.76, projection.route[0].y)
        assertNear(0.5 - 0.05 * 0.76, projection.route[1].y)
        assertNull(projection.photoSpot)
    }

    @Test fun tallRouteFillsHeightAndKeepsAspect() {
        val route = listOf(Coordinate(0.0, 0.0), Coordinate(1.0, 0.0))
        val projection = projectRoute(route, aspect = 1.0)
        assertNear(0.88, projection.route[0].y)
        assertNear(0.12, projection.route[1].y)
        assertNear(0.5, projection.route[0].x)
    }

    @Test fun longitudeIsCompressedByLatitude() {
        // 北緯 60 度では経度 1 度は緯度 0.5 度分の長さ。横 1 度・縦 0.5 度の経路は正方形に収まる
        val route = listOf(Coordinate(59.75, 0.0), Coordinate(60.25, 1.0))
        val projection = projectRoute(route, aspect = 1.0)
        val width = projection.route[1].x - projection.route[0].x
        val height = projection.route[0].y - projection.route[1].y
        assertTrue(abs(width - height) < 1e-3, "width=$width height=$height")
    }

    @Test fun photoOutsideRouteBoundsExtendsTheBox() {
        val route = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.5))
        val projection = projectRoute(route, Coordinate(0.0, 1.0), aspect = 1.0)
        assertNotNull(projection.photoSpot)
        assertNear(0.12, projection.route[0].x)
        assertNear(0.5, projection.route[1].x)
        assertNear(0.88, projection.photoSpot!!.x)
        projection.route.plus(projection.photoSpot!!).forEach { assertTrue(it.x in 0.12..0.88 && it.y in 0.12..0.88) }
    }

    @Test fun singlePointSitsInTheCenter() {
        val projection = projectRoute(listOf(Coordinate(43.0, 141.0)))
        assertNear(0.5, projection.route.single().x)
        assertNear(0.5, projection.route.single().y)
        assertEquals(StickerProjection(emptyList(), null), projectRoute(emptyList()))
    }
}
