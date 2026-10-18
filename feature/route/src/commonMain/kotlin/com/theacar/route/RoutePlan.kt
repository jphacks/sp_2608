package com.theacar.route

import com.theacar.model.Coordinate

data class Destination(val id: String, val title: String, val coordinate: Coordinate) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0)
        require(coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0)
    }
}

data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = true,
    val viaPoints: List<Coordinate> = emptyList(),
    val scenic: ScenicOptions? = null,
) {
    init {
        require(viaPoints.size <= 126)
        viaPoints.forEach { require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0) }
    }
}

data class RouteStep(
    val instruction: String,
    val voiceInstruction: String,
    val road: String,
    val beginGeometryIndex: Int,
    val endGeometryIndex: Int,
    val type: Int,
    val durationSeconds: Int? = null,
    val highway: Boolean = false,
) {
    init { require(durationSeconds == null || durationSeconds >= 0) }
}

data class RouteWaypoint(val coordinate: Coordinate, val geometryIndex: Int)

data class RoutePlan(
    val id: String,
    val destination: Destination,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val geometry: List<Coordinate>,
    val steps: List<RouteStep> = emptyList(),
    val waypoints: List<RouteWaypoint> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(distanceMeters >= 0 && durationSeconds >= 0)
        geometry.forEach { require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0) }
        steps.forEach { require(it.beginGeometryIndex in geometry.indices && it.endGeometryIndex in it.beginGeometryIndex..geometry.lastIndex) }
        waypoints.forEach { require(it.geometryIndex in geometry.indices) }
    }
}

data class RouteQuery(val origin: Coordinate, val destination: Destination, val options: RouteOptions = RouteOptions())

interface RouteCalculator {
    suspend fun calculate(query: RouteQuery): RoutePlan
}

interface DestinationSearch {
    suspend fun search(query: String): List<Destination>
}
