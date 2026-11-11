package com.theacar.android.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theacar.ui.Ink
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Sky
import com.theacar.ui.Sunset

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

/** 寄り道の長さを選ぶ無段階のスライダー。つまみは右へ走る車で、バーは道路 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetourSlider(minutes: Int, onMinutes: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val stage = stages.first { minutes <= it.upTo }
    val last = scenicDetourMinutes.lastIndex.toFloat()
    val position = scenicDetourPosition(minutes)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(stage.icon, contentDescription = null, tint = Mountains)
            Text(stage.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (minutes == 0) "寄り道なし" else "＋${scenicDetourLabel(minutes)}まで",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Slider(value = position, onValueChange = { onMinutes(scenicDetourMinutesAt(it)) }, valueRange = 0f..last,
            enabled = enabled,
            thumb = {
                Icon(SideCar, contentDescription = "寄り道 ${stage.name}", tint = if (enabled) Sunset else PlainRoad,
                    modifier = Modifier.size(40.dp))
            },
            track = { state -> RoadTrack((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)) })
    }
}

/** アスファルトの道に中央の白い破線。車が通った所は、道の縁が景色の色になる */
@Composable
private fun RoadTrack(passed: Float) {
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Asphalt, cornerRadius = r)
        val verge = 2.5.dp.toPx()
        clipRect(right = size.width * passed) {
            drawRoundRect(Sky, cornerRadius = r)
            drawRoundRect(Asphalt, topLeft = Offset(0f, verge), size = Size(size.width, size.height - verge * 2), cornerRadius = r)
        }
        drawLine(Color.White.copy(alpha = .85f), Offset(size.height / 2, size.height / 2), Offset(size.width - size.height / 2, size.height / 2),
            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())))
    }
}

private val Asphalt = Color(0xFF555C58)

// 右を向いた横向きの車（Phosphor Icons「car-profile」fill、MIT）
private val SideCar: ImageVector = ImageVector.Builder("SideCar", 40.dp, 40.dp, 256f, 256f)
    .addPath(addPathNodes("M240,112H211.31L168,68.69A15.86,15.86,0,0,0,156.69,64H44.28A16,16,0,0,0,31,71.12L1.34,115.56A8.07,8.07,0,0,0,0,120v48a16,16,0,0,0,16,16H33a32,32,0,0,0,62,0h66a32,32,0,0,0,62,0h17a16,16,0,0,0,16-16V128A16,16,0,0,0,240,112ZM44.28,80H156.69l32,32H23ZM64,192a16,16,0,1,1,16-16A16,16,0,0,1,64,192Zm128,0a16,16,0,1,1,16-16A16,16,0,0,1,192,192Z"), fill = SolidColor(Color.Black))
    .build()
