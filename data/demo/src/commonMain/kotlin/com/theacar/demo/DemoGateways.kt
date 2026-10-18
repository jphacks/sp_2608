package com.theacar.demo

import com.theacar.music.PlaybackGateway
import com.theacar.music.TrackSuggestion
import com.theacar.route.NavigationGateway
import com.theacar.route.RouteIdea

class DemoPlaybackGateway : PlaybackGateway {
    override suspend fun play(track: TrackSuggestion) = Unit
    override suspend fun pause() = Unit
    override suspend fun setVolume(volume: Float) = Unit
}

class DemoNavigationGateway : NavigationGateway {
    override suspend fun start(route: RouteIdea) = Unit
    override suspend fun stop() = Unit
}
