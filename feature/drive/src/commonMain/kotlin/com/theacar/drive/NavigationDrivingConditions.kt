package com.theacar.drive

import com.theacar.music.DrivingConditions
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.RoutePlan
import com.theacar.route.RouteTiming

/**
 * 案内中の経路の形から、景観BGMへ渡す運転条件を求める。
 *
 * 景観エンジンは曲率を経路の評価に使うが、区間ごとの値は応答に含めない。
 * そこで同じ計算（[curvatureProfile]）を手元の経路の点列で行い、この先 [LOOKAHEAD_METERS] が
 * カーブ続きなら `curvy` にする。景観ルートでない普通の経路でも求める。
 * 交差点の右左折はカーブではないので、案内の曲がり角の前後 [MANEUVER_MARGIN_METERS] は数えない。
 */
class NavigationDrivingConditions(private val navigation: () -> NavigationState) {
    private var cachedRoute: RoutePlan? = null
    private var sharpness: List<Double?> = emptyList()
    private var totalMeters = 0.0

    fun current(): DrivingConditions {
        val state = navigation()
        if (state.phase != NavigationPhase.GUIDING || state.rerouting) return DrivingConditions()
        val route = state.route ?: return DrivingConditions()
        if (route.geometry.size < 2) return DrivingConditions()
        if (cachedRoute !== route) prepare(route)
        val remaining = state.progress?.remainingMeters ?: return DrivingConditions()
        val position = (totalMeters - remaining).coerceIn(0.0, totalMeters)
        val from = (position / CURVATURE_SPACING_METERS).toInt().coerceAtLeast(0)
        val to = ((position + LOOKAHEAD_METERS) / CURVATURE_SPACING_METERS).toInt().coerceAtMost(sharpness.size)
        val ahead = if (from < to) sharpness.subList(from, to).filterNotNull() else emptyList()
        if (ahead.size < MIN_SAMPLES) return DrivingConditions()
        return DrivingConditions(curvy = ahead.average() >= CURVY_SHARPNESS)
    }

    private fun prepare(route: RoutePlan) {
        val timing = RouteTiming(route)
        totalMeters = timing.totalDistanceMeters
        val maneuvers = route.steps.map { it.beginGeometryIndex }.filter { it > 0 }.map(timing::distanceAtIndex)
        sharpness = curvatureProfile(route.geometry).mapIndexed { index, curvature ->
            val at = index * CURVATURE_SPACING_METERS
            curvature?.takeIf { maneuvers.none { m -> kotlin.math.abs(m - at) <= MANEUVER_MARGIN_METERS } }?.let(::sharpness)
        }
        cachedRoute = route
    }

    internal companion object {
        /** 次の曲が流れるあたりまで。4分の曲を時速30kmで走ると2km。 */
        const val LOOKAHEAD_METERS = 2_000.0
        const val MANEUVER_MARGIN_METERS = 30.0
        /**
         * 急カーブの度合い（0〜1）の平均がこれ以上なら curvy。初期値。走行テストで調整する。
         * 前後3点の平均で S 字の曲がりは打ち消し合うため、値は小さく出る。半径 40〜60m で 90° ずつ
         * 切り返す道が 0.09〜0.13、半径 40m のヘアピンが続く道が 0.37、半径 100m の道が 0.003 ほど。
         */
        const val CURVY_SHARPNESS = 0.08
        /** これより短い区間しか残っていなければ判定しない。 */
        const val MIN_SAMPLES = 8
    }
}
