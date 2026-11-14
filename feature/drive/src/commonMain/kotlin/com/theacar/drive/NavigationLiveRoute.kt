package com.theacar.drive

import com.theacar.music.LiveRoute
import com.theacar.music.LiveSegment
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.RouteExplanation
import com.theacar.route.RoutePlan

/**
 * 案内中の経路を、Web のライブ表示に送る形へ変換する。
 *
 * 景色の区間は経路全体を送る（ページの景色の帯に使う）。隣り合う同じ景色はまとめる。
 * 認定道路の固有名と物理道路IDは含めない。
 * 案内中と到着直後のほかは null を返し、そのあいだは何も送らない。
 */
class NavigationLiveRoute(private val navigation: () -> NavigationState) {
    private var cachedRoute: RoutePlan? = null
    private var cachedExplanation: RouteExplanation? = null
    private var segments: List<LiveSegment> = emptyList()
    private var geometry: List<Pair<Double, Double>> = emptyList()

    fun current(): LiveRoute? {
        val state = navigation()
        val arrived = state.phase == NavigationPhase.ARRIVED
        if (state.phase !in LIVE_PHASES) return null
        val route = state.route ?: return null
        val explanation = state.alternatives.firstOrNull { it.plan.id == route.id }?.explanation
        if (cachedRoute !== route || cachedExplanation !== explanation) {
            segments = merge(explanation)
            geometry = route.geometry.map { it.longitude to it.latitude }
            cachedRoute = route
            cachedExplanation = explanation
        }
        val total = route.distanceMeters.toDouble()
        // 案内の始めは GPS の精度が上がるまで進み具合が無い。そのあいだも地図と景色は見せたいので、出発地点として送る
        val position = if (arrived) total else {
            val remaining = state.progress?.remainingMeters ?: route.distanceMeters
            (total - remaining).coerceIn(0.0, total)
        }
        // 再探索で経路が差し替わったら変わる値。ページはこれが変わったときだけ経路の形を読み直す
        val key = "${state.sessionId}-${state.routeRevision}-${route.id.hashCode().toUInt().toString(36)}"
        return LiveRoute(key, route.destination.title, total, position, segments, geometry, arrived)
    }

    private companion object {
        val LIVE_PHASES = setOf(NavigationPhase.STARTING, NavigationPhase.GUIDING, NavigationPhase.ARRIVED)
    }

    private fun merge(explanation: RouteExplanation?): List<LiveSegment> {
        val out = mutableListOf<LiveSegment>()
        explanation?.timeline.orEmpty()
            .filter { it.range.endMeters > it.range.startMeters }
            .sortedBy { it.range.startMeters }
            .forEach { block ->
                val last = out.lastOrNull()
                if (last != null && last.category == block.category && block.range.startMeters <= last.endMeters + 1) {
                    out[out.lastIndex] = last.copy(endMeters = maxOf(last.endMeters, block.range.endMeters))
                } else {
                    out += LiveSegment(block.range.startMeters, block.range.endMeters, block.category)
                }
            }
        return out
    }
}
