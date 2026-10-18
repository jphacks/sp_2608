package com.theacar.location

import com.theacar.model.Coordinate
import kotlinx.coroutines.flow.StateFlow

data class LocationFix(
    val coordinate: Coordinate,
    val accuracyMeters: Float,
    val elapsedRealtimeMillis: Long,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val roadSnapped: Boolean = false,
) {
    init {
        require(accuracyMeters.isFinite() && accuracyMeters >= 0)
        require(elapsedRealtimeMillis >= 0)
    }
}

enum class LocationStatus { WAITING, AVAILABLE, PERMISSION_REQUIRED, DISABLED, STALE }

data class LocationState(val status: LocationStatus = LocationStatus.WAITING, val fix: LocationFix? = null)

interface LocationSource {
    val location: StateFlow<LocationState>
}
