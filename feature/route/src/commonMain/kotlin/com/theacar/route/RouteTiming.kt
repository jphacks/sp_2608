package com.theacar.route

import com.theacar.model.Coordinate
import kotlin.math.*

class RouteTiming(private val route: RoutePlan) {
    private val distances = DoubleArray(route.geometry.size)
    private val seconds: DoubleArray
    val totalDistanceMeters get() = distances.last()

    init {
        require(route.geometry.size >= 2)
        for (index in 1..route.geometry.lastIndex) distances[index] = distances[index - 1] + distance(route.geometry[index - 1], route.geometry[index])
        val total = totalDistanceMeters.coerceAtLeast(1.0)
        seconds = DoubleArray(distances.size) { distances[it] / total * route.durationSeconds }
        val complete = route.steps.isNotEmpty() && route.steps.all { it.durationSeconds != null } &&
            route.steps.first().beginGeometryIndex == 0 && route.steps.last().endGeometryIndex == route.geometry.lastIndex &&
            route.steps.zipWithNext().all { (a, b) -> a.endGeometryIndex == b.beginGeometryIndex }
        val stepTotal = route.steps.sumOf { (it.durationSeconds ?: 0).toDouble() }
        if (complete && stepTotal > 0) {
            var elapsed = 0.0
            val scale = route.durationSeconds / stepTotal
            for (step in route.steps) {
                val start = distances[step.beginGeometryIndex]
                val length = distances[step.endGeometryIndex] - start
                for (index in step.beginGeometryIndex..step.endGeometryIndex) {
                    val fraction = if (length > 0) (distances[index] - start) / length else 0.0
                    seconds[index] = (elapsed + requireNotNull(step.durationSeconds) * fraction) * scale
                }
                elapsed += requireNotNull(step.durationSeconds)
            }
        }
        seconds[seconds.lastIndex] = route.durationSeconds.toDouble()
    }

    fun distanceAtIndex(index: Int) = distances[index]
    fun secondsAtIndex(index: Int) = seconds[index]

    fun remainingSecondsAtDistance(traveledMeters: Double): Int {
        require(traveledMeters.isFinite())
        val traveled = traveledMeters.coerceIn(0.0, totalDistanceMeters)
        if (traveled >= totalDistanceMeters) return 0
        var low = 0
        var high = distances.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (distances[middle] <= traveled) low = middle else high = middle
        }
        val length = distances[high] - distances[low]
        val fraction = if (length > 0) (traveled - distances[low]) / length else 0.0
        val elapsed = seconds[low] + fraction * (seconds[high] - seconds[low])
        return (route.durationSeconds - elapsed).toInt().coerceAtLeast(0)
    }

    private fun distance(a: Coordinate, b: Coordinate): Double {
        val lat = (b.latitude - a.latitude) * PI / 180
        val lon = (b.longitude - a.longitude) * PI / 180
        val h = sin(lat / 2).pow(2) + cos(a.latitude * PI / 180) * cos(b.latitude * PI / 180) * sin(lon / 2).pow(2)
        return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
