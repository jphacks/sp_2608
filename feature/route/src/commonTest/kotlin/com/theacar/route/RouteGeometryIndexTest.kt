package com.theacar.route

import com.theacar.model.Coordinate
import kotlin.test.*

class RouteGeometryIndexTest {
    @Test fun evidenceRangeIsClippedOnTheSelectedRouteGeometry() {
        val points = listOf(Coordinate(43.0, 141.0), Coordinate(43.001, 141.0), Coordinate(43.002, 141.0))
        val route = RoutePlan("route", Destination("end", "目的地", points.last()), 222, 100, points)
        val index = RouteGeometryIndex(route)
        val slice = index.slice(ScenicRange(50.0, 150.0))
        assertEquals(3, slice.size)
        assertEquals(points[1], slice[1])
        assertTrue(slice.first().latitude in 43.0004..43.0005)
        assertTrue(slice.last().latitude in 43.0013..43.0014)
        assertEquals(points.last(), index.slice(ScenicRange(250.0, 300.0)).single())
    }
}
