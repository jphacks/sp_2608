package com.theacar.android.navigation

import com.theacar.route.ScenicRange
import com.theacar.route.ScenicTimelineBlock
import kotlin.math.roundToInt

internal data class ScenicOverviewSection(
    val category: String,
    val range: ScenicRange,
    val sourceRanges: List<ScenicRange>,
)

internal fun scenicSeaRanges(blocks: List<ScenicTimelineBlock>): List<ScenicRange> {
    val ranges = mutableListOf<ScenicRange>()
    blocks.filter { it.category == "sea" && it.range.endMeters > it.range.startMeters }
        .sortedBy { it.range.startMeters }.forEach { block ->
            val previous = ranges.lastOrNull()
            if (previous != null && block.range.startMeters <= previous.endMeters) {
                ranges[ranges.lastIndex] = ScenicRange(previous.startMeters, maxOf(previous.endMeters, block.range.endMeters))
            } else ranges += block.range
        }
    return ranges
}

internal fun scenicTimelineOverview(blocks: List<ScenicTimelineBlock>, maxSections: Int = 24): List<ScenicOverviewSection> {
    require(maxSections > 0)
    val total = blocks.maxOfOrNull { it.range.endMeters } ?: return emptyList()
    if (total <= 0) return emptyList()
    val result = mutableListOf<ScenicOverviewSection>()
    repeat(maxSections) { index ->
        val start = total * index / maxSections
        val end = total * (index + 1) / maxSections
        val lengths = linkedMapOf<String, Double>()
        val ranges = mutableListOf<ScenicRange>()
        blocks.forEach { block ->
            val a = maxOf(start, block.range.startMeters)
            val b = minOf(end, block.range.endMeters)
            if (b > a) {
                lengths[block.category] = (lengths[block.category] ?: 0.0) + b - a
                ranges += ScenicRange(a, b)
            }
        }
        val missing = (end - start - lengths.values.sum()).coerceAtLeast(0.0)
        if (missing > 0) lengths["unknown"] = (lengths["unknown"] ?: 0.0) + missing
        val category = lengths.maxByOrNull { it.value }?.key ?: "unknown"
        val previous = result.lastOrNull()
        if (previous?.category == category) {
            result[result.lastIndex] = previous.copy(range = ScenicRange(previous.range.startMeters, end),
                sourceRanges = previous.sourceRanges + ranges)
        } else result += ScenicOverviewSection(category, ScenicRange(start, end), ranges)
    }
    return result.map { section ->
        val ranges = mutableListOf<ScenicRange>()
        section.sourceRanges.sortedBy { it.startMeters }.forEach { range ->
            val previous = ranges.lastOrNull()
            if (previous != null && range.startMeters <= previous.endMeters) {
                ranges[ranges.lastIndex] = ScenicRange(previous.startMeters, maxOf(previous.endMeters, range.endMeters))
            } else ranges += range
        }
        section.copy(sourceRanges = ranges)
    }
}

/** 分類ごとの距離を長い順に。スマホと車載の内訳表示が共有する。 */
fun scenicCategoryDistances(blocks: List<ScenicTimelineBlock>): List<Pair<String, Double>> =
    blocks.groupBy { it.category }.map { (category, sections) ->
        category to sections.sumOf { it.range.endMeters - it.range.startMeters }
    }.sortedByDescending { it.second }

/** 距離の表し方。推定なので端数は出さない。スマホと車載で同じ。 */
fun scenicDistanceLabel(meters: Double): String =
    if (meters < 1000) "1km未満" else "${(meters / 1000).roundToInt()}km"
