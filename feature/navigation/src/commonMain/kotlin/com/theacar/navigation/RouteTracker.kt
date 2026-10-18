package com.theacar.navigation

import com.theacar.model.Coordinate
import com.theacar.route.RoutePlan
import com.theacar.route.RouteTiming
import kotlin.math.*

internal data class RoutePosition(val segmentIndex: Int, val traveledMeters: Double, val distanceFromRouteMeters: Double)

internal fun distanceMeters(a: Coordinate, b: Coordinate): Double {
    val radians = PI / 180
    val latitude = (b.latitude - a.latitude) * radians
    val longitude = (b.longitude - a.longitude) * radians
    val square = sin(latitude / 2).pow(2) + cos(a.latitude * radians) * cos(b.latitude * radians) * sin(longitude / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(square.coerceIn(0.0, 1.0)))
}

internal class RouteTracker(private val route: RoutePlan) {
    private val timing = RouteTiming(route)
    val distances = DoubleArray(route.geometry.size) { timing.distanceAtIndex(it) }
    private var previousSegment = 0
    private var traveled = 0.0
    val totalMeters get() = distances.last()

    init {
        require(route.geometry.size >= 2)
        require(totalMeters > 0)
    }

    fun remainingSeconds(traveledMeters: Double) = timing.remainingSecondsAtDistance(traveledMeters)

    fun locate(fix: Coordinate): RoutePosition {
        val start = (previousSegment - 3).coerceAtLeast(0)
        var end = previousSegment
        val lookAheadMeters = 1_500
        while (end < route.geometry.lastIndex - 1 && distances[end] - distances[previousSegment] < lookAheadMeters) end++
        var nearest: RoutePosition? = null
        for (index in start..end) {
            val a = route.geometry[index]
            val b = route.geometry[index + 1]
            val scale = cos(fix.latitude * PI / 180).coerceAtLeast(0.001)
            val ax = (a.longitude - fix.longitude) * scale * 111_195
            val ay = (a.latitude - fix.latitude) * 111_195
            val bx = (b.longitude - fix.longitude) * scale * 111_195
            val by = (b.latitude - fix.latitude) * 111_195
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val fraction = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(ax + fraction * dx, ay + fraction * dy)
            val position = RoutePosition(index, distances[index] + fraction * (distances[index + 1] - distances[index]), distance)
            if (nearest == null || distance < nearest.distanceFromRouteMeters) nearest = position
        }
        val match = requireNotNull(nearest)
        // Advance only for a nearby fix; a bad fix must not skip a loop or a waypoint.
        if (match.distanceFromRouteMeters < 60) {
            previousSegment = match.segmentIndex
            traveled = max(traveled, match.traveledMeters)
        }
        return match.copy(traveledMeters = traveled)
    }

    fun maneuver(position: RoutePosition): Maneuver? {
        val next = route.steps.indexOfFirst { it.type !in 1..3 && distances[it.beginGeometryIndex] + 10 >= position.traveledMeters }
        if (next < 0) return null
        val step = route.steps[next]
        return Maneuver(step.instruction, step.road,
            (distances[step.beginGeometryIndex] - position.traveledMeters).toInt().coerceAtLeast(0), step.voiceInstruction, next)
    }
}
