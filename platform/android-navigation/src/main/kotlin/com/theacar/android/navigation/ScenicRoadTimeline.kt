package com.theacar.android.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.theacar.route.*

internal data class ScenicRoadHighlight(val road: ScenicRecommendedRoad, val ranges: List<ScenicRange>)

internal fun scenicRoadHighlights(blocks: List<ScenicTimelineBlock>): List<ScenicRoadHighlight> =
    blocks.flatMap { block -> block.recommendedRoads.map { it to block.range } }
        .groupBy { it.first.id }.values.mapNotNull { entries ->
            val ranges = mutableListOf<ScenicRange>()
            entries.map { it.second }.filter { it.endMeters > it.startMeters }.sortedBy { it.startMeters }.forEach { range ->
                val previous = ranges.lastOrNull()
                if (previous != null && range.startMeters <= previous.endMeters) {
                    ranges[ranges.lastIndex] = ScenicRange(previous.startMeters, maxOf(previous.endMeters, range.endMeters))
                } else ranges += range
            }
            ranges.takeIf { it.isNotEmpty() }?.let { ScenicRoadHighlight(entries.first().first, it) }
        }.sortedWith(compareBy({ it.road.rank.ordinal }, { it.ranges.first().startMeters }, { it.road.id }))

internal fun scenicRoadHits(roads: List<ScenicRoadHighlight>, rank: ScenicRoadRank, meters: Double, tolerance: Double): List<ScenicRoadHighlight> =
    roads.filter { it.road.rank == rank && it.ranges.any { range ->
        meters >= range.startMeters - tolerance && meters <= range.endMeters + tolerance
    } }

internal fun scenicRoadHits(roads: List<ScenicRoadHighlight>, meters: Double, tolerance: Double): List<ScenicRoadHighlight> =
    roads.filter { road -> road.ranges.any { meters >= it.startMeters - tolerance && meters <= it.endMeters + tolerance } }

@Composable
internal fun ScenicRoadTimeline(blocks: List<ScenicTimelineBlock>, axisMeters: Double,
    onHighlight: (ScenicRoadHighlight) -> Unit) {
    val roads = remember(blocks) { scenicRoadHighlights(blocks) }
    if (roads.isEmpty() || axisMeters <= 0) return
    var choices by remember(roads) { mutableStateOf<List<ScenicRoadHighlight>>(emptyList()) }
    Canvas(Modifier.fillMaxWidth().height(24.dp).semantics {
        contentDescription = "名道の位置。4ランクを一本の線に色分けし、重なる区間は上位ランクを表示。タップで全ての道路名を確認。"
        customActions = roads.map { road ->
            CustomAccessibilityAction("${road.road.rank.label}・${road.road.name}を地図で確認") {
                onHighlight(road); true
            }
        }
    }.pointerInput(roads, axisMeters) {
        detectTapGestures { tap ->
            val meters = tap.x / size.width.coerceAtLeast(1) * axisMeters
            val tolerance = 12.dp.toPx() / size.width.coerceAtLeast(1) * axisMeters
            choices = scenicRoadHits(roads, meters, tolerance)
        }
    }) {
        val y = size.height / 2
        drawLine(androidx.compose.ui.graphics.Color(0xFFD8DFDC), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        roads.reversed().forEach { road ->
            road.ranges.forEach { range ->
                val start = (range.startMeters / axisMeters * size.width).toFloat()
                val end = (range.endMeters / axisMeters * size.width).toFloat()
                drawLine(rankColor(road.road.rank), Offset(start, y), Offset(end, y), 3.dp.toPx())
                drawCircle(rankColor(road.road.rank), 4.dp.toPx(), Offset(start.coerceIn(4.dp.toPx(), size.width - 4.dp.toPx()), y))
            }
        }
    }
    if (choices.isNotEmpty()) AlertDialog(onDismissRequest = { choices = emptyList() },
        title = { Text("この区間のおすすめ道路") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { road ->
                    Text(road.road.rank.label, color = rankColor(road.road.rank), style = MaterialTheme.typography.labelLarge)
                    Text(road.road.name, style = MaterialTheme.typography.titleMedium)
                    Text("出発から ${routeDistance(road.ranges.first().startMeters.toInt())} · 対象区間 ${routeDistance(road.ranges.sumOf { it.endMeters - it.startMeters }.toInt())}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { choices = emptyList(); onHighlight(road) }) { Text("この道路を地図で見る") }
                }
            }
        }, confirmButton = { TextButton(onClick = { choices = emptyList() }) { Text("閉じる") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScenicRoadRankLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ScenicRoadRank.entries.forEach { rank ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(7.dp)) { drawCircle(rankColor(rank)) }
                Text(rank.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
