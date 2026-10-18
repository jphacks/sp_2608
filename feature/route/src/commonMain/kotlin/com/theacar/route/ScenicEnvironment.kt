package com.theacar.route

data class ScenicEnvironmentSection(
    val range: ScenicRange,
    val startTime: String,
    val endTime: String,
    val arrivalTime: String,
    val elapsedSeconds: Int,
    val solarAltitude: Double,
    val solarAzimuth: Double,
    val lightPhase: String,
    val sky: String,
    val cloudCover: Double?,
    val precipitation: Double?,
    val features: Map<String, Double?>,
    val urbanRatio: Double?,
    val tunnel: Boolean,
    val elevationMeters: Double? = null,
    val terrainDerived: Boolean = false,
    val weatherDetails: Map<String, Double> = emptyMap(),
)

data class ScenicBestSpot(
    val range: ScenicRange,
    val distanceMeters: Double,
    val arrivalTime: String,
    val category: String,
    val reasons: List<String>,
    val confidence: String,
)

data class ScenicEnvironment(
    val sections: List<ScenicEnvironmentSection>,
    val bestSpots: List<ScenicBestSpot>,
    val samplingMeters: Int,
    val landscapeAvailable: Boolean,
    val warning: String,
)
