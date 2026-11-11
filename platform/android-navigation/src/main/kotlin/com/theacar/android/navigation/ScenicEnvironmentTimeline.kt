package com.theacar.android.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.theacar.ui.Ink
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.Icons
import com.theacar.ui.Muted
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.theacar.route.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun environmentClock(value: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("時刻不明")

internal fun environmentSkyLabel(value: String) = when (value) {
    "clear" -> "快晴予報"; "fair" -> "晴れ予報"; "cloudy" -> "曇り予報"
    "precipitation" -> "降水予報"; "low_visibility" -> "低視程予報"; "snowfall" -> "降雪予報"
    "fog" -> "霧予報"; "thunderstorm" -> "雷雨予報"; else -> "予報なし"
}

internal fun environmentLightLabel(value: String) = when (value) {
    "dawn" -> "朝焼けの時間帯（推定）"; "dusk" -> "夕焼けの時間帯（推定）"
    "night" -> "夜間"; else -> "日中"
}

internal fun environmentWeatherDetail(key: String, value: Double): String {
    val amount = String.format(Locale.JAPAN, "%.1f", value)
    return when (key) {
        "temperature_2m" -> "気温 ${amount}℃"; "snowfall" -> "降雪 ${amount}cm"
        "snow_depth" -> "地上の積雪 ${amount}m"; "wind_speed_10m" -> "風速 ${amount}m/s"
        "wind_gusts_10m" -> "突風 ${amount}m/s"; "precipitation_probability" -> "降水確率 ${amount}%"
        "relative_humidity_2m" -> "湿度 ${amount}%"; else -> ""
    }
}

private fun lightColor(value: String) = when (value) {
    "dawn" -> Color(0xFFE7A077); "dusk" -> Color(0xFFC07982); "night" -> Color(0xFF566580); else -> Color(0xFFC9AB5A)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScenicEnvironmentTimeline(environment: ScenicEnvironment?, axisMeters: Double,
    onHighlight: ((List<ScenicRange>) -> Unit)? = null, compact: Boolean = false) {
    val sections = environment?.sections.orEmpty()
    if (sections.isEmpty() || axisMeters <= 0) return
    var selected by remember(environment) { mutableStateOf<ScenicEnvironmentSection?>(null) }
    var spot by remember(environment) { mutableStateOf<ScenicBestSpot?>(null) }
    val first = sections.first()
    val middle = sections[sections.size / 2]
    val last = sections.last()
    val tap: (Float, Int) -> Unit = { x, width ->
        val at = x / width.coerceAtLeast(1) * axisMeters
        selected = sections.firstOrNull { at >= it.range.startMeters && at <= it.range.endMeters }
    }
    val onTap = Modifier.pointerInput(sections, axisMeters) { detectTapGestures { tap(it.x, size.width) } }
    fun DrawScope.band(s: ScenicEnvironmentSection, color: Color, y: Float = 0f, h: Float = size.height) =
        drawRect(color, Offset((s.range.startMeters / axisMeters * size.width).toFloat(), y),
            Size(((s.range.endMeters - s.range.startMeters) / axisMeters * size.width).toFloat() + 1f, h))
    // 左に短い見出し、右に帯の表。どの行も同じ横位置で、上の時刻と縦にそろう
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) Text("時刻ごとの環境", style = MaterialTheme.typography.labelMedium)
        EnvironmentRow("") {
            BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
                listOf(0.0 to first.startTime, ((middle.range.startMeters + middle.range.endMeters) / 2) to middle.arrivalTime,
                    last.range.endMeters to last.endTime).forEach { (meters, time) ->
                    val x = (maxWidth * (meters / axisMeters).toFloat() - if (meters == 0.0) 0.dp else 20.dp)
                        .coerceIn(0.dp, (maxWidth - 34.dp).coerceAtLeast(0.dp))
                    Text(environmentClock(time), modifier = Modifier.offset(x = x), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
        EnvironmentRow("日の光") {
            // 横線は太陽高度0°（山や建物の遮りは含まない）
            Canvas(Modifier.fillMaxWidth().height(if (compact) 20.dp else 28.dp).semantics {
                contentDescription = "太陽高度と朝夕の光。距離位置をタップして通過予定時刻を確認。"
            }.then(onTap)) {
                sections.forEach { band(it, lightColor(it.lightPhase).copy(alpha = .18f)) }
                fun y(altitude: Double) = ((1 - (altitude.coerceIn(-18.0, 90.0) + 18) / 108) * size.height).toFloat()
                drawLine(Color(0xFFADB8B3), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
                sections.zipWithNext().forEach { (a, b) ->
                    fun x(s: ScenicEnvironmentSection) = ((s.range.startMeters + s.range.endMeters) / 2 / axisMeters * size.width).toFloat()
                    drawLine(lightColor(b.lightPhase), Offset(x(a), y(a.solarAltitude)), Offset(x(b), y(b.solarAltitude)), 2.dp.toPx())
                }
            }
        }
        WeatherTable(sections) { selected = it }
        if (environment?.bestSpots?.isNotEmpty() == true) EnvironmentRow("見どころ") {
            BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)) {
                environment.bestSpots.forEachIndexed { index, item ->
                    val position = ((maxWidth * (item.distanceMeters / axisMeters).toFloat()) - 12.dp).coerceIn(0.dp, (maxWidth - 24.dp).coerceAtLeast(0.dp))
                    Surface(onClick = { spot = item }, shape = CircleShape, color = categoryColor(item.category).copy(alpha = .2f),
                        modifier = Modifier.offset(x = position).size(24.dp).semantics { contentDescription = "見どころ候補${index + 1}、${environmentClock(item.arrivalTime)}" }) {
                        Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        if (!compact && environment?.bestSpots?.isNotEmpty() == true) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            environment?.bestSpots?.forEachIndexed { index, item ->
                Text("${index + 1} ${categoryLabel(item.category)} ${environmentClock(item.arrivalTime)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    selected?.let { section ->
        val direction = listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")[((section.solarAzimuth + 22.5) / 45).toInt() % 8]
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("${environmentClock(section.arrivalTime)}ごろの環境") },
            text = { Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${environmentLightLabel(section.lightPhase)} · 太陽は$direction ${section.solarAzimuth.toInt()}° / 高度${section.solarAltitude}°")
                Text(environmentSkyLabel(section.sky) + (section.cloudCover?.let { " · 雲量${it.toInt()}%" } ?: ""))
                Text("街度（周辺の建物被覆率） ${section.urbanRatio?.let { "%.1f%%".format(it * 100) } ?: "未取得"}")
                Text("湖・池 ${section.features["lake"]?.let { if (it >= .35) "近い" else "対応する地物なし" } ?: "未取得"} / 川 ${section.features["river"]?.let { if (it >= .35) "近い" else "対応する地物なし" } ?: "未取得"}")
                Text("渓谷・谷 ${section.features["valley"]?.let { if (it >= .35) "特徴あり" else "特徴の検出なし" } ?: "未取得"}（${if (section.terrainDerived) "地形と地図から推定" else "地図の登録から推定"}）")
                section.elevationMeters?.let { Text("地表の標高 約${it.toInt()}m（道路・橋面の高さとは異なる場合があります）") }
                section.weatherDetails.forEach { (key, value) ->
                    val label = environmentWeatherDetail(key, value)
                    if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall)
                }
                if (section.weatherDetails.isNotEmpty()) Text("モデルの時間・地点補間値です。路面の積雪・凍結・車の安全性の判定ではありません。", style = MaterialTheme.typography.bodySmall)
                Text(environment?.warning.orEmpty(), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { onHighlight?.invoke(listOf(section.range)); selected = null }) { Text(if (onHighlight != null) "地図で確認" else "閉じる") } },
            dismissButton = { if (onHighlight != null) TextButton(onClick = { selected = null }) { Text("閉じる") } })
    }
    spot?.let { item ->
        AlertDialog(onDismissRequest = { spot = null }, title = { Text("${environmentClock(item.arrivalTime)} · ${categoryLabel(item.category)}の見どころ候補") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("出発から${scenicDistanceLabel(item.distanceMeters)} · ${if (item.confidence == "medium") "見通し推定あり" else "確信度は低め"}")
                item.reasons.forEach { reason -> Text(bestSpotReason(reason), style = MaterialTheme.typography.bodyMedium) }
                Text("走行区間の推定です。駐車・停車可能な場所を確認したものではありません。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { onHighlight?.invoke(listOf(item.range)); spot = null }) { Text(if (onHighlight != null) "地図で確認" else "閉じる") } },
            dismissButton = { if (onHighlight != null) TextButton(onClick = { spot = null }) { Text("閉じる") } })
    }
}

internal fun bestSpotReason(reason: String): String = when (reason) {
    "sea_proximity" -> "海岸線に近い区間"; "lake_proximity" -> "湖・池・ダム湖に近い区間"
    "river_proximity" -> "川に近い区間"; "valley_proximity" -> "地形・地図から谷や渓谷の特徴を推定した区間"
    "forest" -> "森林の特徴が強い区間"; "open_nature" -> "開けた自然の特徴がある区間"
    "visibility_estimate" -> "地形などによる見通しの推定あり"
    "sunrise_direction" -> "朝方の太陽と水辺の方向が重なる可能性"
    "sunset_direction" -> "夕方の太陽と水辺の方向が重なる可能性"
    "forecast_unavailable" -> "通過時刻の天気予報は取得できていません"
    else -> "地図の特徴からの推定"
}

private val RowLabelWidth = 52.dp

@Composable
private fun EnvironmentRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(RowLabelWidth))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 天気予報のように、通る時刻ごとに天気・気温・降水確率を並べた表。多いときは横に送る */
@Composable
private fun WeatherTable(sections: List<ScenicEnvironmentSection>, onSelect: (ScenicEnvironmentSection) -> Unit) {
    val columns = remember(sections) {
        val count = minOf(sections.size, 12)
        if (count <= 1) sections.take(1) else (0 until count).map { sections[it * (sections.size - 1) / (count - 1)] }.distinct()
    }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(RowLabelWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("時刻" to 16.dp, "天気" to 28.dp, "気温" to 18.dp, "降水" to 18.dp).forEach { (label, height) ->
                Box(Modifier.height(height), contentAlignment = Alignment.CenterStart) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
        LazyRow(Modifier.weight(1f)) {
            items(columns) { section ->
                Column(Modifier.width(52.dp).pointerInput(section) { detectTapGestures { onSelect(section) } },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                        Text(environmentClock(section.arrivalTime), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    Icon(weatherIcon(section), contentDescription = environmentSkyLabel(section.sky), tint = weatherTint(section),
                        modifier = Modifier.size(28.dp))
                    Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                        Text(section.weatherDetails["temperature_2m"]?.let { "${it.roundToInt()}℃" } ?: "–",
                            style = MaterialTheme.typography.labelLarge, color = Ink)
                    }
                    Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                        Text(section.weatherDetails["precipitation_probability"]?.let { "${it.roundToInt()}%" } ?: "–",
                            style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }
        }
    }
}

private fun weatherIcon(s: ScenicEnvironmentSection): ImageVector = when (s.sky) {
    "clear", "fair" -> if (s.lightPhase == "night") Icons.Rounded.Bedtime else Icons.Rounded.WbSunny
    "precipitation" -> Icons.Rounded.Umbrella
    "snowfall" -> Icons.Rounded.AcUnit
    "thunderstorm" -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Cloud
}

private fun weatherTint(s: ScenicEnvironmentSection): Color = when {
    s.sky in setOf("clear", "fair") && s.lightPhase != "night" -> Color(0xFFE0A93B)
    s.lightPhase == "night" -> Color(0xFF6D7A99)
    else -> Color(0xFF8A969D)
}
