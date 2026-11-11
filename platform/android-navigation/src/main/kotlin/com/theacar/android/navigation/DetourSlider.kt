package com.theacar.android.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theacar.ui.Ink
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Sky
import kotlin.math.roundToInt

private class DetourStage(val upTo: Int, val name: String, val icon: ImageVector)

// 寄り道の長さごとの呼び名。動かすたびに変わって、旅の気分が伝わるように
private val stages = listOf(
    DetourStage(0, "最短で行く", Icons.Rounded.DirectionsRun),
    DetourStage(15, "ちょっと寄り道", Icons.Rounded.DirectionsWalk),
    DetourStage(60, "景色を楽しむ", Icons.Rounded.Hiking),
    DetourStage(180, "ぶらり旅", Icons.Rounded.Landscape),
    DetourStage(Int.MAX_VALUE, "大冒険", Icons.Rounded.Explore),
)

private val PlainRoad = Color(0xFFC4C7C5)

/** 寄り道の長さを選ぶスライダー。つまみは車で、右へ行くほど道が景色の色になる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetourSlider(minutes: Int, onMinutes: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val stage = stages.first { minutes <= it.upTo }
    val index = scenicDetourIndex(minutes)
    val road = lerp(PlainRoad, Sky, index / scenicDetourMinutes.lastIndex.toFloat())
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(stage.icon, contentDescription = null, tint = Mountains)
            Text(stage.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (minutes == 0) "寄り道なし" else "＋${scenicDetourLabel(minutes)}まで",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Slider(value = index.toFloat(),
            onValueChange = { onMinutes(scenicDetourMinutes[it.roundToInt().coerceIn(scenicDetourMinutes.indices)]) },
            valueRange = 0f..scenicDetourMinutes.lastIndex.toFloat(), steps = scenicDetourMinutes.size - 2, enabled = enabled,
            colors = SliderDefaults.colors(activeTrackColor = road, inactiveTrackColor = PlainRoad.copy(alpha = .5f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            thumb = {
                Surface(shape = CircleShape, color = Mountains, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.DirectionsCar, contentDescription = "寄り道 ${stage.name}", tint = Color.White,
                        modifier = Modifier.padding(7.dp))
                }
            })
    }
}
