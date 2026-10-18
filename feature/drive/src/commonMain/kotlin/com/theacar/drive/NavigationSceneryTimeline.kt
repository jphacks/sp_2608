package com.theacar.drive

import com.theacar.music.SceneryBlock
import com.theacar.music.SceneryRoute
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.RoutePlan
import com.theacar.route.RouteExplanation
import com.theacar.route.ScenicReason
import com.theacar.route.ScenicTimelineBlock

/**
 * 案内中のルートの景観タイムラインを、音楽側の表現へ変換する。
 *
 * 音楽機能はナビ・ルートの型に依存しないため、対応づけはここが持つ。
 * 認定道路の固有名と物理道路IDは渡さない。外部サービスへ送る値から経路を特定できないようにしている。
 * 再探索で経路が入れ替わると、その経路の景観は手元に無いので null を返す。
 */
class NavigationSceneryTimeline(private val navigation: () -> NavigationState) {
    private var cachedRoute: RoutePlan? = null
    private var cachedExplanation: RouteExplanation? = null
    private var blocks: List<SceneryBlock> = emptyList()
    private var reveals: List<Double> = emptyList()

    fun current(): SceneryRoute? {
        val state = navigation()
        if (state.phase != NavigationPhase.GUIDING || state.rerouting) {
            clear()
            return null
        }
        val route = state.route ?: return null
        val explanation = state.alternatives.firstOrNull { it.plan.id == route.id }?.explanation ?: run {
            clear()
            return null
        }
        if (cachedRoute !== route || cachedExplanation !== explanation) {
            blocks = explanation.timeline.mapNotNull(::block)
            reveals = explanation.reasons.filter { it.template == ScenicReason.SEA_REVEAL }
                .flatMap { reason -> reason.ranges.map { it.startMeters } }.distinct().sorted()
            cachedRoute = route
            cachedExplanation = explanation
        }
        if (blocks.isEmpty()) return null
        val remaining = state.progress?.remainingMeters ?: return null
        val position = (route.distanceMeters - remaining).toDouble().coerceIn(0.0, route.distanceMeters.toDouble())
        // 窓の計算に要るぶんだけ渡す。曲ごとに全区間を送らない。
        val from = position - BEHIND_METERS
        val to = position + AHEAD_METERS
        val nearby = blocks.filter { it.endMeters > from && it.startMeters < to }
        if (nearby.isEmpty()) return null
        // 景観エンジンの経路ならルート ID を添える。推薦サービスはそれでタイムラインを取れる。
        return SceneryRoute(position, nearby, reveals.filter { it in position..to },
            routeId = route.id.takeIf { SceneryRoute.ROUTE_ID.matches(it) })
    }

    private fun block(source: ScenicTimelineBlock): SceneryBlock? {
        if (source.range.endMeters <= source.range.startMeters) return null
        return SceneryBlock(
            startMeters = source.range.startMeters,
            endMeters = source.range.endMeters,
            category = source.category,
            confidence = source.confidence,
            openness = source.openness,
            visibilityVerified = source.visibilityVerified,
            recommendedRank = source.topRank?.code,
            arrivalTime = source.arrivalTime,
        )
    }

    private companion object {
        /** 速度を出すため、現在位置の手前のブロックも少しだけ含める。 */
        const val BEHIND_METERS = 2_000.0
        const val AHEAD_METERS = 30_000.0
    }

    private fun clear() {
        cachedRoute = null
        cachedExplanation = null
        blocks = emptyList()
        reveals = emptyList()
    }
}
