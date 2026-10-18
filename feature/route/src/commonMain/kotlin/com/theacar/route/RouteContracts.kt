package com.theacar.route

import com.theacar.model.Coordinate
import com.theacar.model.DriveMode
import com.theacar.model.Scenery
import com.theacar.model.Weather

data class RouteIdea(
    val title: String,
    val description: String,
    val durationMinutes: Int,
    val distanceKm: Double,
    val tags: List<String>,
)


data class RouteRequest(
    val mode: DriveMode,
    val coordinate: Coordinate,
    val weather: Weather,
    val coastDistanceKm: Double?,
    val localHour: Int,
    val scenery: Scenery = Scenery.UNKNOWN,
)

interface RouteRecommender {
    suspend fun recommend(request: RouteRequest): RouteIdea
}

interface NavigationGateway {
    suspend fun start(route: RouteIdea)
    suspend fun stop()
}
