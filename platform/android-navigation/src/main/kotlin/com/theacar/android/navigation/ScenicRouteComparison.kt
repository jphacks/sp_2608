package com.theacar.android.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theacar.route.*
import com.theacar.ui.CardCorner
import com.theacar.ui.ChipCorner
import com.theacar.ui.Ink
import com.theacar.ui.Mountains
import com.theacar.ui.Sky
import com.theacar.ui.jost

@Composable
internal fun ScenicRouteChoices(alternatives: List<RouteAlternative>, selectedId: String, onSelect: (String) -> Unit,
    onHighlightRoad: (ScenicRoadHighlight) -> Unit) {
    val routes = alternatives.filter { it.scenic != null }
    val titles = remember(routes) { routes.associate { it.plan.id to routeChoiceTitle(it, routes) } }
    val traits = remember(routes) { routes.associate { it.plan.id to routeChoiceTraits(it, routes) } }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var highlighted by remember(routes) { mutableStateOf<Pair<String, ScenicRoadHighlight>?>(null) }
    val showRoad: (String, ScenicRoadHighlight) -> Unit = { routeId, road ->
        onSelect(routeId)
        comparing = false
        onHighlightRoad(road)
        highlighted = routeId to road
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${routes.size}案の特徴", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { comparing = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("通常ルート・候補を比較")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(routes, key = { _, route -> route.plan.id }) { index, route ->
                val selected = route.plan.id == selectedId
                Surface(onClick = { onSelect(route.plan.id) }, shape = RoundedCornerShape(CardCorner),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(224.dp).semantics {
                        contentDescription = "候補${index + 1}。${if (selected) "選択中。" else ""}${titles.getValue(route.plan.id)}"
                    }) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1} · ${titles.getValue(route.plan.id)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge)
                        Text("合計${routeMinutes(route.explanation?.totalSeconds ?: route.plan.durationSeconds)} · 走行比 ${routeTimeDelta(routeDrivingDifference(route))}",
                            style = MaterialTheme.typography.bodySmall)
                        SceneryStrip(route.explanation?.timeline.orEmpty(), normalized = true, axisMeters = 1.0,
                            modifier = Modifier.fillMaxWidth().height(12.dp))
                        ScenicRoadTimeline(route.explanation?.timeline.orEmpty(),
                            route.explanation?.timeline?.maxOfOrNull { it.range.endMeters } ?: 0.0,
                            onHighlight = { showRoad(route.plan.id, it) })
                        Text(traits.getValue(route.plan.id).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (routes.any { route -> route.explanation?.timeline?.any { it.recommendedRoads.isNotEmpty() } == true }) {
            ScenicRoadRankLegend()
        }
        highlighted?.takeIf { it.first == selectedId }?.second?.let { road ->
            Text("${road.road.rank.label} · ${road.road.name}", color = rankColor(road.road.rank),
                style = MaterialTheme.typography.bodySmall)
        }
        Text("帯は全体を100%にそろえた景観の流れ。下端の青線は海に近い区間です", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (comparing) ScenicRouteComparisonDialog(routes, selectedId,
        onSelect = { onSelect(it); comparing = false }, onDismiss = { comparing = false }, onHighlightRoad = showRoad)
}

@Composable
private fun ScenicRouteComparisonDialog(routes: List<RouteAlternative>, selectedId: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit,
    onHighlightRoad: (String, ScenicRoadHighlight) -> Unit) {
    var normalized by rememberSaveable { mutableStateOf(false) }
    val reference = routes.firstNotNullOfOrNull { it.explanation?.reference }
    val axis = maxOf(reference?.timeline?.maxOfOrNull { it.range.endMeters } ?: 0.0,
        routes.maxOfOrNull { it.explanation?.timeline?.maxOfOrNull { block -> block.range.endMeters } ?: 0.0 } ?: 0.0,
        routes.maxOfOrNull { it.plan.distanceMeters.toDouble() } ?: 1.0).coerceAtLeast(1.0)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ルートを比べる", style = MaterialTheme.typography.titleLarge, color = Ink,
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                    TextButton(onClick = onDismiss) { Text("地図へ戻る") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(colors = tabiChipColors(), border = tabiChipBorder(!normalized), shape = RoundedCornerShape(ChipCorner), selected = !normalized, onClick = { normalized = false }, label = { Text("同じ距離の縮尺") })
                                FilterChip(colors = tabiChipColors(), border = tabiChipBorder(normalized), shape = RoundedCornerShape(ChipCorner), selected = normalized, onClick = { normalized = true }, label = { Text("全体を100%に") })
                            }
                            Text(if (normalized) "横軸は各ルートの進行率。到着地点をそろえて比較します。"
                                else "横軸は距離。すべての帯で右端が${routeDistance(axis.toInt())}です。", style = MaterialTheme.typography.bodySmall)
                            SceneryLegend(routes.flatMap { it.explanation?.timeline.orEmpty() } + reference?.timeline.orEmpty())
                            ScenicRoadRankLegend()
                            Text("景観帯の下の色付きレーンはおすすめ道路の位置です。タップで名前と区間を確認できます。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        AllSceneryComparison(routes, reference, selectedId, normalized, axis, onSelect, onHighlightRoad)
                    }
                    itemsIndexed(routes, key = { _, route -> route.plan.id }) { index, route ->
                        RouteComparisonCard(index, route, routes, reference, normalized, axis,
                            selected = selectedId == route.plan.id, onSelect = { onSelect(route.plan.id) },
                            onHighlightRoad = { onHighlightRoad(route.plan.id, it) })
                    }
                    item {
                        Text("景観は地図からの推定です。海の眺望や通行可否は保証しません。運転負荷は安全度ではありません。短い景観変化はまとめ、海に近い区間は下端の青線でも表示します。海・森林などの距離指標は重なるため、主な景観を示す帯の色の長さとは一致しない場合があります。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (routes.any { it.explanation?.searchLimited == true }) Text(
                            "探索時間内に見つかった候補です。全経路の最適解ではありません。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AllSceneryComparison(routes: List<RouteAlternative>, reference: ScenicRouteReference?, selectedId: String,
    normalized: Boolean, axis: Double, onSelect: (String) -> Unit,
    onHighlightRoad: (String, ScenicRoadHighlight) -> Unit) {
    Surface(shape = RoundedCornerShape(CardCorner), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("景観を並べて比較", style = MaterialTheme.typography.titleMedium)
            if (reference != null) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(jostNumbers("通常ルート · 走行${routeMinutes(reference.drivingSeconds)} · ${routeDistance(reference.distanceMeters)}"),
                        style = MaterialTheme.typography.labelLarge)
                    SceneryStrip(reference.timeline, normalized, axis, Modifier.fillMaxWidth().height(18.dp))
                    Text(jostNumbers(routeLandscapeSummary(reference.metrics)),
                        style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            } else Text("通常ルートの景観データはありません。周遊や旧APIでは候補同士のみ比較できます。",
                style = MaterialTheme.typography.bodySmall)
            routes.forEachIndexed { index, route ->
                val metrics = route.scenic ?: return@forEachIndexed
                Surface(onClick = { onSelect(route.plan.id) }, shape = RoundedCornerShape(CardCorner),
                    color = if (route.plan.id == selectedId) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1} · ${routeChoiceTitle(route, routes)}", style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 6.dp))
                            Text(jostNumbers(routeMinutes(route.plan.durationSeconds)), style = MaterialTheme.typography.labelLarge)
                        }
                        SceneryStrip(route.explanation?.timeline.orEmpty(), normalized, axis, Modifier.fillMaxWidth().height(18.dp))
                        val timeline = route.explanation?.timeline.orEmpty()
                        ScenicRoadTimeline(timeline, sceneryAxisMeters(timeline, normalized, axis),
                            onHighlight = { onHighlightRoad(route.plan.id, it) })
                        Text(jostNumbers("${routeLandscapeSummary(metrics)} / 通常比 ${routeTimeDelta(routeDrivingDifference(route))}"),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("候補をタップすると地図で確認できます。通常ルートは同じ道路条件での比較基準で、厳密な最短時間は保証しません。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteComparisonCard(index: Int, route: RouteAlternative, all: List<RouteAlternative>,
    reference: ScenicRouteReference?, normalized: Boolean, axis: Double, selected: Boolean, onSelect: () -> Unit,
    onHighlightRoad: (ScenicRoadHighlight) -> Unit) {
    val summary = route.scenicSummary() ?: return
    val difference = reference?.let(summary::differenceFrom)
    Surface(shape = RoundedCornerShape(CardCorner),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) Sky else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.jost.statUnit.fontFamily), color = Mountains,
                    modifier = Modifier.padding(end = 10.dp))
                Text(routeChoiceTitle(route, all), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selected) Text("選択中", style = MaterialTheme.typography.labelMedium, color = Mountains)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                routeChoiceTraits(route, all).forEach { trait ->
                    Surface(shape = RoundedCornerShape(ChipCorner), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(trait, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(jostNumbers("走行${routeMinutes(summary.drivingSeconds)} · 休憩${routeMinutes(summary.stopSeconds)} · ${routeDistance(summary.distanceMeters)}"),
                style = MaterialTheme.typography.bodyMedium)
            if (difference != null) Text(jostNumbers("通常ルートより ${routeTimeDelta(difference.drivingSeconds)} / ${routeDistanceDelta(difference.distanceMeters)}"),
                style = MaterialTheme.typography.bodySmall, color = Mountains)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (reference != null) {
                    Text("通常ルート", style = MaterialTheme.typography.labelSmall)
                    SceneryStrip(reference.timeline, normalized, axis, Modifier.fillMaxWidth().height(14.dp))
                }
                Text("この候補", style = MaterialTheme.typography.labelSmall)
                SceneryStrip(summary.timeline, normalized, axis, Modifier.fillMaxWidth().height(24.dp))
                ScenicRoadTimeline(summary.timeline, sceneryAxisMeters(summary.timeline, normalized, axis), onHighlightRoad)
                ScenicEnvironmentTimeline(summary.environment, sceneryAxisMeters(summary.timeline, normalized, axis), compact = true)
            }
            HorizontalDivider()
            ComparisonMeasure("海に近い区間", routeDistance(summary.metrics.seaMeters),
                difference?.seaMeters?.let(::routeDistanceDelta))
            ComparisonMeasure("森林の特徴", routeDistance(summary.metrics.forestMeters),
                difference?.forestMeters?.let(::routeDistanceDelta))
            (summary.metrics.categoryMeters.keys + reference?.metrics?.categoryMeters.orEmpty().keys).distinct().forEach { category ->
                val meters = summary.metrics.categoryMeters[category]
                val baselineMeters = reference?.metrics?.categoryMeters?.get(category)
                if ((meters ?: 0) > 0 || (baselineMeters ?: 0) > 0) {
                    ComparisonMeasure(categoryLabel(category) + "の特徴", meters?.let(::routeDistance) ?: "データ不足",
                        meters?.let { current -> baselineMeters?.let { routeDistanceDelta(current - it) } })
                }
            }
            ComparisonMeasure("好みに合う最長の連続区間", routeDistance(summary.metrics.continuousMeters),
                difference?.continuousMeters?.let(::routeDistanceDelta))
            ComparisonMeasure("休憩時間", routeMinutes(summary.stopSeconds), difference?.stopSeconds?.let(::routeTimeDelta))
            (summary.recommendedMeters.keys + reference?.recommendedMeters.orEmpty().keys).sortedBy { it.ordinal }
                .filter { (summary.recommendedMeters[it] ?: 0) > 0 || (reference?.recommendedMeters?.get(it) ?: 0) > 0 }
                .forEach { rank ->
                    val meters = summary.recommendedMeters[rank]
                    ComparisonMeasure(rank.label, meters?.let(::routeDistance) ?: "データなし",
                        meters?.let { current -> reference?.recommendedMeters?.get(rank)?.let { routeDistanceDelta(current - it) } })
            }
            ComparisonMeasure("道路の再通過（推定）", summary.repeatedMeters?.let(::routeDistance) ?: "データなし",
                difference?.repeatedMeters?.let(::routeDistanceDelta))
            if (route.explanation != null) ComparisonMeasure("運転負荷（推定・0〜100）", "${(summary.load * 100).toInt()}",
                difference?.load?.let { "${if (it > 0) "+" else ""}${(it * 100).toInt()}pt" })
            ComparisonMeasure("道路特徴の評価率", "${(summary.metrics.coverage * 100).toInt()}%", null)
            val roads = summary.recommendedRoads()
            if (roads.isNotEmpty()) Text(roads.take(3).joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (selected) "このルートを地図で確認" else "このルートを選ぶ")
            }
        }
    }
}

@Composable
private fun ComparisonMeasure(label: String, value: String, difference: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(jostNumbers(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (difference != null) Text("  ($difference)", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneryLegend(blocks: List<ScenicTimelineBlock>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.map { it.category }.distinct().forEach { category ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(8.dp)) { drawCircle(categoryColor(category)) }
                Text(categoryLabel(category), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun SceneryStrip(blocks: List<ScenicTimelineBlock>, normalized: Boolean, axisMeters: Double, modifier: Modifier = Modifier) {
    val sections = remember(blocks) { scenicTimelineOverview(blocks, 32) }
    val seaRanges = remember(blocks) { scenicSeaRanges(blocks) }
    val total = blocks.maxOfOrNull { it.range.endMeters } ?: 0.0
    val axis = sceneryAxisMeters(blocks, normalized, axisMeters)
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val endColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.semantics { contentDescription = routeSceneryDescription(blocks) }) {
        drawRoundRect(background, cornerRadius = CornerRadius(3.dp.toPx()))
        sections.forEach { section ->
            val x = (section.range.startMeters / axis * size.width).toFloat()
            val width = ((section.range.endMeters - section.range.startMeters) / axis * size.width).toFloat()
            drawRoundRect(categoryColor(section.category), topLeft = Offset(x, 0f),
                size = Size((width - 1.dp.toPx()).coerceAtLeast(0f), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
        }
        seaRanges.forEach { range ->
            drawRect(categoryColor("sea"),
                Offset((range.startMeters / axis * size.width).toFloat(), size.height - 3.dp.toPx()),
                Size(((range.endMeters - range.startMeters) / axis * size.width).toFloat(), 3.dp.toPx()))
        }
        if (!normalized && total > 0) {
            val end = (total / axis * size.width).toFloat().coerceIn(0f, (size.width - 1f).coerceAtLeast(0f))
            drawLine(endColor, Offset(end, 0f), Offset(end, size.height), strokeWidth = 1.dp.toPx())
        }
    }
}
