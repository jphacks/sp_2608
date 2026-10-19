package com.theacar.route

import com.theacar.model.Coordinate

class RouteGeometryIndex(private val route: RoutePlan) {
    private val timing = RouteTiming(route)
    val totalDistanceMeters: Double get() = timing.totalDistanceMeters

    fun slice(range: ScenicRange): List<Coordinate> {
        val start = range.startMeters.coerceAtMost(timing.totalDistanceMeters)
        val end = range.endMeters.coerceAtMost(timing.totalDistanceMeters)
        val first = precedingIndex(start)
        val last = precedingIndex(end)
        return buildList {
            add(interpolate(first, start))
            for (index in first + 1..last) add(route.geometry[index])
            add(interpolate(last, end))
        }.distinct()
    }

    private fun precedingIndex(meters: Double): Int {
        var low = 0
        var high = route.geometry.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (timing.distanceAtIndex(middle) <= meters) low = middle else high = middle
        }
        return low
    }

    private fun interpolate(index: Int, meters: Double): Coordinate {
        val a = route.geometry[index]
        val b = route.geometry[index + 1]
        val length = timing.distanceAtIndex(index + 1) - timing.distanceAtIndex(index)
        val fraction = if (length > 0) ((meters - timing.distanceAtIndex(index)) / length).coerceIn(0.0, 1.0) else 0.0
        return Coordinate(a.latitude + (b.latitude - a.latitude) * fraction,
            a.longitude + (b.longitude - a.longitude) * fraction)
    }
}
