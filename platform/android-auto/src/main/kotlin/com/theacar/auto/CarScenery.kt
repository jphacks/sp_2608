package com.theacar.auto

import com.theacar.android.navigation.categoryLabel
import com.theacar.android.navigation.scenicCategoryDistances
import com.theacar.android.navigation.scenicDistanceLabel
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.RouteExplanation
import com.theacar.route.ScenicRange
import com.theacar.route.ScenicReason
import com.theacar.route.ScenicRoadRank

internal data class CarSceneryBlock(val category: String, val range: ScenicRange)

internal data class CarSceneryRoad(val name: String, val rank: ScenicRoadRank)

/** 車載画面へ出す景観。地図を塗る分と、知らせる種だけを持つ。 */
internal data class CarScenery(
    val blocks: List<CarSceneryBlock>,
    val totalMeters: Double,
    val positionMeters: Double,
    val road: CarSceneryRoad?,
    /** 次に海が開ける地点。同じ地点を二度知らせないよう、距離ではなく位置で持つ。 */
    val revealAtMeters: Double?,
) {
    val revealMeters: Double? get() = revealAtMeters?.minus(positionMeters)
}

/** これより近づいたら海が開けることを予告する。身構えられて、早すぎない距離。 */
internal const val REVEAL_NOTICE_METERS = 1500.0

internal fun carScenery(state: NavigationState): CarScenery? {
    if (state.phase != NavigationPhase.GUIDING) return null
    val route = state.route ?: return null
    val explanation = state.explanation() ?: return null
    val timeline = explanation.timeline.filter { it.range.endMeters > it.range.startMeters }
    if (timeline.isEmpty()) return null
    val total = maxOf(timeline.maxOf { it.range.endMeters }, route.distanceMeters.toDouble())
    if (total <= 0) return null
    val remaining = state.progress?.remainingMeters ?: route.distanceMeters
    val position = (route.distanceMeters - remaining).toDouble().coerceIn(0.0, total)
    val here = timeline.lastOrNull { position >= it.range.startMeters && position < it.range.endMeters }
    val road = here?.recommendedRoads?.minByOrNull { it.rank.ordinal }?.let { CarSceneryRoad(it.name, it.rank) }
    val reveal = explanation.reasons.filter { it.template == ScenicReason.SEA_REVEAL }
        .flatMap { it.ranges }
        .map { it.startMeters }
        .filter { it > position && it - position <= REVEAL_NOTICE_METERS }
        .minOrNull()
    return CarScenery(timeline.map { CarSceneryBlock(it.category, it.range) }, total, position, road, reveal)
}

internal data class CarSceneryShare(val category: String, val meters: Double)

/** これより短い区間は内訳に出さない。読む手間に見合わない。 */
private const val SHARE_FLOOR_METERS = 500.0

/** 確認中の経路に、どの景色がどれだけ含まれるか。長い順に返す。現在位置は要らない。 */
internal fun carSceneryShares(state: NavigationState): List<CarSceneryShare> {
    val explanation = state.explanation() ?: return emptyList()
    return scenicCategoryDistances(explanation.timeline)
        .map { (category, meters) -> CarSceneryShare(category, meters) }
        .filter { it.meters >= SHARE_FLOOR_METERS }
}

/** 再探索で経路が入れ替わると、その経路の景観は手元に無い。 */
private fun NavigationState.explanation(): RouteExplanation? {
    if (rerouting) return null
    val id = route?.id ?: return null
    return alternatives.firstOrNull { it.plan.id == id }?.explanation
}

internal const val CAR_SCENERY_BREAKDOWN_TITLE = "主な景観の内訳（推定）"

/** 行の中へ収めるときの見出し。長いと距離が端で切られる。 */
internal const val CAR_SCENERY_BREAKDOWN_LABEL = "主な景観（推定）"

/** 車載の行は文字列を2つしか持てない。[perLine] 件ずつ [lines] 行に収め、入りきらない分は落とす。 */
internal fun carSceneryBreakdownLines(shares: List<CarSceneryShare>, lines: Int, perLine: Int): List<String> =
    shares.take(lines * perLine).chunked(perLine).map { chunk ->
        chunk.joinToString(" ・ ") { "${categoryLabel(it.category)} ${scenicDistanceLabel(it.meters)}" }
    }

/** ルート線の色。スマホ側（core/ui の TheaCarTheme.kt）と同じ値を使って見た目を揃える。 */
internal fun carSceneryColor(category: String): Int = when (category) {
    "sea" -> 0xFF6FA6D6.toInt()
    "forest" -> 0xFF75B1A9.toInt()
    "inland_water", "lake", "river" -> 0xFF8FD0D8.toInt()
    "valley" -> 0xFF4F6457.toInt()
    "open_nature" -> 0xFFB9C97A.toInt()
    "farmland" -> 0xFFD9B44A.toInt()
    "townscape", "urban" -> 0xFF5C6B63.toInt()
    else -> 0x665C6B63
}

