package com.theacar.android.navigation

import androidx.compose.material.icons.rounded.Map

import androidx.compose.material.icons.rounded.Landscape

import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.ui.graphics.drawscope.clipPath

import androidx.compose.ui.geometry.RoundRect

import androidx.compose.ui.graphics.Path



import androidx.compose.material.icons.Icons

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.theacar.route.RouteAlternative
import com.theacar.route.ScenicPreference
import com.theacar.route.ScenicOptions
import com.theacar.route.ScenicRange
import com.theacar.route.ScenicTimelineBlock
import com.theacar.route.ScenicEnvironment
import com.theacar.route.ScenicRoadRank
import com.theacar.ui.CardCorner
import com.theacar.ui.ChipCorner
import com.theacar.ui.Ink
import com.theacar.ui.Navajo
import com.theacar.ui.Spruce
import com.theacar.ui.Muted
import com.theacar.ui.Regent
import com.theacar.ui.Sea
import com.theacar.ui.Meadow
import com.theacar.ui.Lake
import com.theacar.ui.Surface as SurfaceColor
import com.theacar.ui.Sunglow
import com.theacar.ui.Forest
import com.theacar.ui.SunsetDeep


@Composable
internal fun ScenicSearchControls(
    preference: ScenicPreference?,
    extraMinutes: Int,
    onPreference: (ScenicPreference?) -> Unit,
    onExtraMinutes: (Int) -> Unit,
    enabled: Boolean = true,
    settings: ScenicOptions = ScenicOptions(),
    onSettings: (ScenicOptions) -> Unit = {},
    compact: Boolean = false,
    onQuickSearch: ((ScenicOptions) -> Unit)? = null,
    showButtons: Boolean = true,
) {
    var showPreferenceEditor by rememberSaveable { mutableStateOf(false) }
    var showQuickSearch by rememberSaveable { mutableStateOf(false) }
    if (showButtons) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = { showPreferenceEditor = true }, modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, Spruce), colors = ButtonDefaults.outlinedButtonColors(contentColor = Spruce)) {
            Text(if (preference != null && settings.customWeights != null) "好みを調整 · カスタム" else "好みを調整")
        }
        if (onQuickSearch != null) FilledTonalButton(enabled = enabled, onClick = { showQuickSearch = true }, colors = mistButtonColors()) {
            Text("かんたん探索")
        }
    }
    if (preference != null && settings.customWeights != null) {
        Text(scenicWeightSummary(settings), style = MaterialTheme.typography.labelSmall)
    }
    if (showPreferenceEditor) ScenicPreferenceEditor(settings, enabled, onQuickSearch != null,
        onApply = { updated, search ->
            onPreference(updated.preference)
            onSettings(updated)
            showPreferenceEditor = false
            if (search) onQuickSearch?.invoke(updated)
        }, onDismiss = { showPreferenceEditor = false })
    if (showQuickSearch) ScenicQuickSearch(settings, enabled, onSelect = { updated ->
        onPreference(updated.preference)
        onSettings(updated)
        showQuickSearch = false
        onQuickSearch?.invoke(updated)
    }, onDismiss = { showQuickSearch = false })
    if (preference == ScenicPreference.WATER || preference == ScenicPreference.VALLEY) {
        Text("水辺の近さと、登録情報・利用可能な標高データから谷の地形を評価します。実際の眺望や渓谷の検出精度は未確認です。",
            style = MaterialTheme.typography.bodySmall)
    }
    if (preference == ScenicPreference.URBAN) {
        Text("建物密度を評価します。美しい街並みや賑わいの評価ではありません。", style = MaterialTheme.typography.bodySmall)
    }
    if (preference != null) {
        if (!settings.roundTrip) {
            DetourSlider(extraMinutes, onExtraMinutes, enabled)
            if (!compact) Text("同じ道路条件の通常ルートと比較します。休憩時間は別です。", style = MaterialTheme.typography.bodySmall)
        } else {
            RoundTripSlider(settings.totalSeconds / 60, { onSettings(settings.copy(totalSeconds = it.coerceIn(RoundTripMin, RoundTripMax) * 60)) }, enabled)
        }
    }
    if (preference != null && !compact) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        DisclosureButton("休憩・道路条件", expanded, { expanded = !expanded })
        if (expanded) {
            Row {
                FilterChip(colors = tabiChipColors(), border = tabiChipBorder(settings.desiredStops == 0), shape = RoundedCornerShape(ChipCorner), selected = settings.desiredStops == 0, enabled = enabled,
                    onClick = { onSettings(settings.copy(desiredStops = 0)) }, label = { Text("休憩指定なし") })
                Spacer(Modifier.width(8.dp))
                FilterChip(colors = tabiChipColors(), border = tabiChipBorder(settings.desiredStops == 1), shape = RoundedCornerShape(ChipCorner), selected = settings.desiredStops == 1, enabled = enabled,
                    onClick = { onSettings(settings.copy(desiredStops = 1)) }, label = { Text("10分休憩を希望") })
            }
            Text("施設データがない場合、休憩を確保したと表示しません。", style = MaterialTheme.typography.bodySmall)
            Text("景色の変化を楽しむ ${(settings.variety * 100).toInt()}%")
            Slider(value = settings.variety.toFloat(), onValueChange = { onSettings(settings.copy(variety = it.toDouble())) }, enabled = enabled)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("未舗装を許可", modifier = Modifier.weight(1f))
                Switch(checked = settings.allowUnpaved, onCheckedChange = { onSettings(settings.copy(allowUnpaved = it)) }, enabled = enabled)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("幅員確認済みの道に限定", modifier = Modifier.weight(1f))
                Switch(checked = settings.requireVerifiedWidth, onCheckedChange = { onSettings(settings.copy(requireVerifiedWidth = it)) }, enabled = enabled)
            }
            Text("厳格な条件では、情報不足のため経路が見つからない場合があります。", style = MaterialTheme.typography.bodySmall)
        }
        Text("海・森などの地理的特徴を評価します。実際の眺望や現在の通行可否を保証するものではありません。手動の寄り道とは併用できません。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ScenicAlternatives(alternatives: List<RouteAlternative>, selectedId: String, onSelect: (String) -> Unit,
    onHighlight: (List<ScenicRange>) -> Unit = {}, compact: Boolean = false,
    onHighlightRoad: (ScenicRoadHighlight) -> Unit = { onHighlight(it.ranges) }, detailRequest: Int = 0) {
    if (alternatives.none { it.scenic != null }) return
    val detailFocus = remember { BringIntoViewRequester() }
    LaunchedEffect(detailRequest, compact, selectedId) {
        if (detailRequest > 0 && !compact) {
            withFrameNanos { }
            detailFocus.bringIntoView()
        }
    }
    ScenicRouteChoices(alternatives, selectedId, onSelect, onHighlightRoad)
    if (!compact) {
        val alternative = alternatives.firstOrNull { it.plan.id == selectedId }
        val explanation = alternative?.explanation
        if (explanation != null) {
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(detailFocus)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    explanation.reasons.take(2).forEach { reason -> ReasonRow(shortReason(reason.text)) { onHighlight(reason.ranges) } }
                }
                Spacer(Modifier.height(12.dp))
                ScenicTimeline(explanation.timeline, onHighlight, environment = explanation.environment)
                var details by rememberSaveable(alternative?.plan?.id) { mutableStateOf(false) }
                TextButton(onClick = { details = !details }) { Text(if (details) "根拠・注意を閉じる" else "根拠・注意を確認") }
                if (details) {
                    explanation.reasons.drop(2).forEach { reason -> ReasonRow(shortReason(reason.text)) { onHighlight(reason.ranges) } }
                    if (explanation.searchLimited) Text("計算予算内で探索した候補です。全組み合わせの最適解ではありません。", style = MaterialTheme.typography.bodySmall)
                    Text("景観の根拠と運転負荷は別の推定です。負荷指標 ${"%.2f".format(explanation.load)}", style = MaterialTheme.typography.bodySmall)
                    explanation.warnings.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    explanation.sources.forEach { Text("${it.id} · ${it.license}\n${it.url}", style = MaterialTheme.typography.bodySmall) }
                    explanation.comparisons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("${explanation.snapshotId} / revision ${explanation.revision}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private val RankGold = Color(0xFF7A5C00)
private val RankSea = Color(0xFF1F5F8B)
private val RankViolet = Color(0xFF6A4C93)

internal fun categoryColor(category: String) = when (category) {
    "sea" -> Sea
    "forest" -> Forest
    "inland_water", "lake", "river" -> Lake
    "valley" -> Forest
    "open_nature" -> Meadow
    "farmland" -> Sunglow
    "townscape", "urban" -> Muted
    else -> Muted.copy(alpha = .4f)
}

/** 景観の分類の呼び方。スマホと車載で同じ言い回しを使うため、ここだけが持つ。 */
fun categoryLabel(category: String) = when (category) {
    "sea" -> "海に近い"; "forest" -> "森林"; "inland_water" -> "水辺"; "open_nature" -> "草地など"
    "lake" -> "湖・池"; "river" -> "川"; "valley" -> "渓谷付近"
    "farmland" -> "田園"; "townscape" -> "街並み"; "urban" -> "市街地"; "unclassified" -> "分類なし"; else -> "未評価"
}

internal fun rankColor(rank: ScenicRoadRank) = when (rank) {
    // 経路線の青と見分けられる色相にする。文字にも使うので白地で4.5:1以上
    ScenicRoadRank.SIGNATURE -> SunsetDeep
    ScenicRoadRank.PANORAMA -> RankGold
    ScenicRoadRank.RECOMMENDED -> RankSea
    ScenicRoadRank.SCENIC -> RankViolet
}

@Composable
private fun ScenicTimelineBar(blocks: List<ScenicTimelineBlock>, total: Double,
    onHighlight: (List<ScenicRange>) -> Unit, compact: Boolean) {
    val sections = remember(blocks, compact) { scenicTimelineOverview(blocks, if (compact) 24 else 48) }
    val roads = remember(blocks) { scenicRoadHighlights(blocks) }
    Canvas(Modifier.fillMaxWidth().height(32.dp).semantics {
        contentDescription = "主な景観の流れ。区間ごとに最も長い景観を表示します。タップで地図を表示。"
        customActions = sections.filter { it.sourceRanges.isNotEmpty() }.take(6).map { section ->
            CustomAccessibilityAction("${categoryLabel(section.category)}中心の区間を地図で確認") {
                onHighlight(section.sourceRanges); true
            }
        }
    }.pointerInput(sections, roads, total) {
        detectTapGestures { tap ->
            val distance = tap.x / size.width.coerceAtLeast(1) * total
            roads.firstOrNull { road -> road.ranges.any { distance >= it.startMeters && distance <= it.endMeters } }?.let {
                onHighlight(it.ranges)
                return@detectTapGestures
            }
            sections.firstOrNull { distance >= it.range.startMeters && distance <= it.range.endMeters }
                ?.takeIf { it.sourceRanges.isNotEmpty() }?.let { onHighlight(it.sourceRanges) }
        }
    }) {
        val barHeight = (if (compact) 14.dp else 20.dp).toPx()
        val top = (size.height - barHeight) / 2
        // 区間ごとの角丸と隙間をやめ、一続きの帯にする（細かく区切れて見えて落ち着かなかった）
        clipPath(Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + barHeight, CornerRadius(barHeight / 2))) }) {
            sections.forEach { section ->
                val left = (section.range.startMeters / total * size.width).toFloat()
                val width = ((section.range.endMeters - section.range.startMeters) / total * size.width).toFloat()
                drawRect(categoryColor(section.category), Offset(left, top), Size(width + 1f, barHeight))
            }
        }
        // 名道の区間は帯の上に重ねる
        drawRoadSegments(roads, total, size.height / 2, outline = true)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ScenicTimeline(blocks: List<ScenicTimelineBlock>, onHighlight: (List<ScenicRange>) -> Unit,
    compact: Boolean = false, environment: ScenicEnvironment? = null) {
    val total = blocks.maxOfOrNull { it.range.endMeters } ?: return
    if (total <= 0) return
    val roads = blocks.flatMap { it.recommendedRoads }.distinctBy { it.id }.sortedBy { it.rank.ordinal }
    // 分類できなかった区間と1km未満の景色は、凡例に並べても読み取れないので外す
    val distances = remember(blocks) {
        scenicCategoryDistances(blocks).filter { it.first !in setOf("unclassified", "unknown") && it.second >= 1000 }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("道中の景色", style = MaterialTheme.typography.labelMedium)
            Text("全${scenicDistanceLabel(total)}", style = MaterialTheme.typography.labelSmall)
        }
        ScenicTimelineBar(blocks, total, onHighlight, compact)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()) {
            distances.forEach { (category, meters) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(categoryColor(category)) }
                    Text("${categoryLabel(category)} ${scenicDistanceLabel(meters)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (roads.isNotEmpty()) ScenicRoadRankLegend(roads.map { it.rank }.distinct())
        ScenicEnvironmentTimeline(environment, total, onHighlight, compact)
        if (!compact) {
            val scenic = blocks.filter { it.range.endMeters - it.range.startMeters >= 300 && it.category !in setOf("urban", "unclassified", "unknown") }
            if (scenic.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(scenic.take(12)) { block ->
                    Surface(onClick = { onHighlight(listOf(block.range)) }, shape = RoundedCornerShape(50),
                        color = categoryColor(block.category).copy(alpha = .15f)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Canvas(Modifier.size(8.dp)) { drawCircle(categoryColor(block.category)) }
                            Text("${categoryLabel(block.category)} ${(block.range.startMeters / 100).toInt() / 10.0}〜${(block.range.endMeters / 100).toInt() / 10.0}km",
                                style = MaterialTheme.typography.labelMedium, color = Ink)
                        }
                    }
                }
            }
        }
    }
}

/** 推薦の理由の1行。文と、その区間を地図で見るボタン */
@Composable
private fun ReasonRow(text: String, onMap: () -> Unit) {
    Surface(onClick = onMap, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .45f)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Landscape, contentDescription = null, tint = Spruce, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Map, contentDescription = "地図で見る", tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}
