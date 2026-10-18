package com.theacar.music

enum class MusicCueKind { HIGHWAY, WAYPOINT, ARRIVAL }

data class MusicRouteCue(
    val kind: MusicCueKind,
    val label: String,
    val secondsFromNow: Int,
    val endSecondsFromNow: Int = secondsFromNow,
) {
    init { require(endSecondsFromNow >= secondsFromNow) }
}

data class MusicSceneryCue(
    val category: String,
    val startSeconds: Int,
    val endSeconds: Int,
    val confidence: Double? = null,
) {
    init { require(startSeconds >= 0 && endSeconds >= startSeconds) }
}

data class MusicJourney(
    val key: String,
    val destination: String,
    val remainingSeconds: Int,
    val cues: List<MusicRouteCue>,
    val scenery: List<MusicSceneryCue> = emptyList(),
) {
    init { require(remainingSeconds >= 0) }
}

data class ScheduledMusicTrack(
    val track: MusicTrack,
    val startsAfterSeconds: Int,
    val theme: DriveMusicTheme,
    val durationEstimated: Boolean,
)
