package com.theacar.music

import com.theacar.model.DriveContext
import com.theacar.model.DriveMode

data class TrackSuggestion(
    val title: String,
    val artist: String,
    val reason: String,
    val kind: TrackKind,
)

enum class TrackKind { COASTAL, CITY_POP, ACOUSTIC, PARTY, RAINY, COMEDY }


interface MusicRecommender {
    fun recommend(mode: DriveMode, context: DriveContext): TrackSuggestion
}

interface PlaybackGateway {
    suspend fun play(track: TrackSuggestion)
    suspend fun pause()
    suspend fun setVolume(volume: Float)
}
