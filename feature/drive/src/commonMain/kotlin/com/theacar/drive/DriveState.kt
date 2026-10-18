package com.theacar.drive

import com.theacar.cabin.CabinSuggestion
import com.theacar.model.DriveContext
import com.theacar.model.DriveMode
import com.theacar.music.TrackSuggestion
import com.theacar.route.RouteIdea

data class DriveState(
    val mode: DriveMode,
    val context: DriveContext,
    val suggestedRoute: RouteIdea?,
    val selectedTrack: TrackSuggestion,
    val volume: Float,
    val cabinSuggestion: CabinSuggestion?,
    val playingTrack: TrackSuggestion? = null,
    val activeRoute: RouteIdea? = null,
    val isPlaying: Boolean = false,
    val isRouteLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val failure: DriveFailure? = null,
)

enum class DriveFailure {
    ROUTE_RECOMMENDATION, PLAYBACK, NAVIGATION, ENVIRONMENT
}

interface DriveSessionOwner {
    val driveSession: DriveSession
    val isDemo: Boolean get() = false
}
