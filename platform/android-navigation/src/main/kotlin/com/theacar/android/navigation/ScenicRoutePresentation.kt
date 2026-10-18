package com.theacar.android.navigation

import com.theacar.route.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun routeDistance(meters: Int): String = String.format(Locale.JAPAN, "%.1fkm", meters / 1000.0)
internal fun routeMinutes(seconds: Int): String = "${(seconds + 59) / 60}分"
internal fun routeTimeDelta(seconds: Int): String = when {
    seconds == 0 -> "同じ"
    else -> "${if (seconds > 0) "+" else "−"}${(abs(seconds) + 59) / 60}分"
}
internal fun routeDistanceDelta(meters: Int): String =
    if (meters == 0) "同じ" else "${if (meters > 0) "+" else "−"}${routeDistance(abs(meters))}"

internal fun routeDrivingDifference(route: RouteAlternative): Int =
    route.explanation?.let { route.plan.durationSeconds - (it.reference?.drivingSeconds ?: it.baselineSeconds) }
        ?: route.scenic?.extraSeconds ?: 0

internal fun routeLandscapeSummary(metrics: ScenicMetrics): String =
    (metrics.categoryMeters + mapOf("sea" to metrics.seaMeters, "forest" to metrics.forestMeters))
        .filterValues { it >= 100 }.entries.sortedByDescending { it.value }.take(3)
        .joinToString(" / ") { "${categoryLabel(it.key)} ${routeDistance(it.value)}" }.ifEmpty { "評価された景観区間なし" }

internal fun routeChoiceTitle(route: RouteAlternative, all: List<RouteAlternative>): String {
    val roads = route.scenicSummary()?.recommendedRoads().orEmpty()
    val frequency = all.flatMap { it.scenicSummary()?.recommendedRoads().orEmpty().map { road -> road.id } }
        .groupingBy { it }.eachCount()
    val distinctive = roads.sortedWith(compareBy<ScenicRecommendedRoad> { frequency[it.id] ?: 0 }.thenBy { it.rank.ordinal }).firstOrNull()
    if (distinctive != null) return distinctive.name
    val metrics = route.scenic ?: return "ドライブルート"
    val other = metrics.categoryMeters.maxByOrNull { it.value }
    if (other != null && other.value >= 300 && other.value > maxOf(metrics.seaMeters, metrics.forestMeters)) {
        return "${categoryLabel(other.key)}の特徴を楽しむルート"
    }
    return when {
        metrics.seaMeters >= metrics.forestMeters && metrics.seaMeters >= 300 -> "海の近くを走るルート"
        metrics.forestMeters >= 300 -> "森林を走るルート"
        else -> "景色を組み合わせるルート"
    }
}

internal fun routeChoiceTraits(route: RouteAlternative, all: List<RouteAlternative>): List<String> {
    val metrics = route.scenic ?: return emptyList()
    val traits = mutableListOf<String>()
    if (all.size > 1) {
        if (metrics.seaMeters >= 1000 && metrics.seaMeters == all.maxOf { it.scenic?.seaMeters ?: 0 }) traits += "海に近い区間が最多"
        if (metrics.forestMeters >= 1000 && metrics.forestMeters == all.maxOf { it.scenic?.forestMeters ?: 0 }) traits += "森林区間が最多"
        metrics.categoryMeters.entries.sortedByDescending { it.value }.forEach { (category, meters) ->
            if (meters >= 1000 && meters == all.maxOf { it.scenic?.categoryMeters?.get(category) ?: 0 }) {
                traits += "${categoryLabel(category)}の特徴が最多"
            }
        }
        if (route.plan.durationSeconds == all.minOf { it.plan.durationSeconds }) traits += "走行時間が最短（候補内）"
        if (metrics.continuousMeters >= 1000 && metrics.continuousMeters == all.maxOf { it.scenic?.continuousMeters ?: 0 }) traits += "景観の連続区間が最長"
    }
    route.scenicSummary()?.recommendedRoads()?.firstOrNull()?.let { traits += it.rank.label + "を走る" }
    if (traits.size < 2) {
        traits += routeLandscapeSummary(metrics)
    }
    return traits.distinct().take(3)
}

internal fun routeSceneryDescription(blocks: List<ScenicTimelineBlock>): String {
    val total = blocks.maxOfOrNull { it.range.endMeters } ?: return "景観データなし"
    if (total <= 0) return "景観データなし"
    return scenicTimelineOverview(blocks, 12).joinToString("、") {
        "${(it.range.startMeters / total * 100).roundToInt()}〜${(it.range.endMeters / total * 100).roundToInt()}%は${categoryLabel(it.category)}"
    }
}

internal fun sceneryAxisMeters(blocks: List<ScenicTimelineBlock>, normalized: Boolean, commonAxis: Double): Double {
    val total = blocks.maxOfOrNull { it.range.endMeters } ?: 0.0
    return if (normalized) total.coerceAtLeast(1.0) else maxOf(commonAxis, total, 1.0)
}
