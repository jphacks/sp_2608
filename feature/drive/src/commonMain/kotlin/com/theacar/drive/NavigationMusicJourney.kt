package com.theacar.drive

import com.theacar.music.MusicCueKind
import com.theacar.music.MusicJourney
import com.theacar.music.MusicRouteCue
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.RoutePlan
import com.theacar.route.RouteTiming
import kotlin.math.max

class NavigationMusicJourney(private val navigation: () -> NavigationState) {
    private var cachedRoute: RoutePlan? = null
    private var timeline: List<MusicRouteCue> = emptyList()

    fun current(): MusicJourney? {
        val state = navigation()
        if (state.phase != NavigationPhase.GUIDING || state.rerouting) {
            cachedRoute = null
            timeline = emptyList()
            return null
        }
        val route = state.route ?: return null
        if (route.geometry.size < 2 || route.durationSeconds <= 0) return null
        if (cachedRoute !== route) {
            timeline = timeline(route)
            cachedRoute = route
        }
        val remaining = (state.progress?.remainingSeconds ?: route.durationSeconds).coerceIn(0, route.durationSeconds)
        val elapsed = route.durationSeconds - remaining
        return MusicJourney("${state.sessionId}:${route.id}:${state.routeRevision}", route.destination.title, remaining,
            timeline.filter { it.endSecondsFromNow >= elapsed }.map {
                it.copy(secondsFromNow = it.secondsFromNow - elapsed, endSecondsFromNow = it.endSecondsFromNow - elapsed)
            })
    }

    private fun timeline(route: RoutePlan): List<MusicRouteCue> {
        val timing = RouteTiming(route)
        val cues = mutableListOf<MusicRouteCue>()
        for (step in route.steps.filter { it.highway }) {
            val start = timing.secondsAtIndex(step.beginGeometryIndex).toInt()
            val end = timing.secondsAtIndex(step.endGeometryIndex).toInt()
            if (end <= start) continue
            val previous = cues.lastOrNull()
            if (previous?.kind == MusicCueKind.HIGHWAY && start <= previous.endSecondsFromNow + 1) {
                cues[cues.lastIndex] = previous.copy(endSecondsFromNow = max(previous.endSecondsFromNow, end))
            } else cues += MusicRouteCue(MusicCueKind.HIGHWAY, "高速道路", start, end)
        }
        route.waypoints.forEachIndexed { index, waypoint ->
            cues += MusicRouteCue(MusicCueKind.WAYPOINT, "経由地${index + 1}", timing.secondsAtIndex(waypoint.geometryIndex).toInt())
        }
        cues += MusicRouteCue(MusicCueKind.ARRIVAL, "目的地", route.durationSeconds)
        return cues.sortedBy { it.secondsFromNow }
    }
}
