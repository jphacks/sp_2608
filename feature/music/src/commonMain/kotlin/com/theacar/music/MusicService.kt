package com.theacar.music

data class MusicTrack(val uri: String, val title: String, val artist: String, val url: String, val durationMillis: Int? = null) {
    init { require(durationMillis == null || durationMillis > 0) }
}
data class MusicDevice(val id: String, val name: String, val active: Boolean)
data class MusicPlayback(val track: MusicTrack? = null, val playing: Boolean = false, val deviceId: String? = null,
    val shuffleEnabled: Boolean? = null, val positionMillis: Int? = null) {
    init { require(positionMillis == null || positionMillis >= 0) }
}

enum class MusicFailure { AUTHORIZATION, ACCESS_DENIED, NO_DEVICE, RATE_LIMIT, NETWORK, STORAGE, WEATHER_UNAVAILABLE, NO_RESULTS, UNKNOWN }
class MusicException(val failure: MusicFailure) : Exception(failure.name)

interface MusicService {
    suspend fun search(query: String): List<MusicTrack>
    suspend fun devices(): List<MusicDevice>
    suspend fun playback(): MusicPlayback
    suspend fun play(trackUri: String?, deviceId: String)
    suspend fun playMix(tracks: List<MusicTrack>, deviceId: String)
    suspend fun enqueue(trackUri: String, deviceId: String) {
        throw MusicException(MusicFailure.ACCESS_DENIED)
    }
    suspend fun pause(deviceId: String)
    suspend fun next(deviceId: String)
    suspend fun previous(deviceId: String)
}
