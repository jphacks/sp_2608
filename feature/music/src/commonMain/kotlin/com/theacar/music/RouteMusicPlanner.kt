package com.theacar.music

import com.theacar.environment.LocalWeather
import com.theacar.model.Weather
import kotlin.math.abs

class RouteMusicPlanner(private val policy: DriveMusicPolicy = DriveMusicPolicy()) {
    suspend fun plan(
        weather: LocalWeather,
        journey: MusicJourney?,
        search: suspend (String) -> List<MusicTrack>,
    ): List<ScheduledMusicTrack> {
        val result = mutableListOf<ScheduledMusicTrack>()
        val used = mutableSetOf<String>()
        val candidates = mutableMapOf<String, List<MusicTrack>>()
        var offset = 0
        repeat(10) {
            if (journey != null && result.isNotEmpty() && offset >= journey.remainingSeconds) return result
            val theme = themeAt(weather, journey, offset)
            var selectedTheme = theme
            val tracks = candidates.getOrPut(theme.query) { search(theme.query).distinctBy { it.uri }.take(10) }
            var available = tracks.filter { it.uri !in used }
            if (available.isEmpty() && theme.query != policy.theme(weather).query) {
                val base = policy.theme(weather).query
                available = candidates.getOrPut(base) { search(base).distinctBy { it.uri }.take(10) }.filter { it.uri !in used }
                selectedTheme = policy.theme(weather).copy(reason = "候補が少ないため、現在の天気を優先して選曲。")
            }
            if (available.isEmpty()) return result
            val nextBoundary = journey?.cues?.flatMap {
                when (it.kind) {
                    MusicCueKind.HIGHWAY -> listOf(it.secondsFromNow, it.endSecondsFromNow)
                    MusicCueKind.WAYPOINT -> listOf((it.secondsFromNow - 60).coerceAtLeast(0), it.secondsFromNow + 60)
                    MusicCueKind.ARRIVAL -> listOf((it.secondsFromNow - 180).coerceAtLeast(0), it.secondsFromNow)
                }
            }?.filter { it > offset }?.minOrNull()
            val target = (listOfNotNull(nextBoundary?.minus(offset), journey?.remainingSeconds?.minus(offset))
                .filter { it > 0 }.minOrNull() ?: 210).coerceIn(60, 360)
            val track = available.minBy { abs(durationSeconds(it) - target) + if (it.durationMillis == null) 90 else 0 }
            result += ScheduledMusicTrack(track, offset, selectedTheme, track.durationMillis == null)
            used += track.uri
            offset += durationSeconds(track)
        }
        return result
    }

    private fun themeAt(weather: LocalWeather, journey: MusicJourney?, offset: Int): DriveMusicTheme {
        val base = policy.theme(weather)
        if (journey == null || weather.weather in setOf(Weather.RAIN, Weather.SNOW)) return base
        if (journey.remainingSeconds - offset <= 180) return DriveMusicTheme("到着前の、余韻", "目的地への到着見込みに合わせ、穏やかな曲を。", "mellow acoustic")
        journey.cues.firstOrNull { it.kind == MusicCueKind.WAYPOINT && offset in (it.secondsFromNow - 60)..(it.secondsFromNow + 60) }?.let {
            return DriveMusicTheme("寄り道の、ひと息", "${it.label}への到着前後に、軽やかな音楽を。", "acoustic chill")
        }
        journey.cues.firstOrNull { it.kind == MusicCueKind.HIGHWAY && offset in it.secondsFromNow until it.endSecondsFromNow }?.let {
            return DriveMusicTheme("高速道路の、クルーズ", "高速道路区間の通過見込みに合わせた選曲。", if (weather.isDay) "road trip pop" else "night drive")
        }
        return SceneryMusicPolicy().theme(weather, journey.scenery, offset) ?: base
    }

    private fun durationSeconds(track: MusicTrack) = track.durationMillis?.takeIf { it > 0 }?.let { ((it.toLong() + 999) / 1_000).toInt() } ?: 210
}
