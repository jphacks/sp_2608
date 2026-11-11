package com.theacar.android.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theacar.ui.Ink
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Sky

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

/** 寄り道の長さを選ぶ無段階のスライダー。上に段階の呼び名、つまみは右へ走る車、バーは道路 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetourSlider(minutes: Int, onMinutes: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val stage = stages.first { minutes <= it.upTo }
    val last = scenicDetourMinutes.lastIndex.toFloat()
    val position = scenicDetourPosition(minutes)
    // 寄り道の段階はアイコンだけで見せる
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(stage.icon, contentDescription = null, tint = Mountains)
            Text(stage.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (minutes == 0) "寄り道なし" else "＋${scenicDetourLabel(minutes)}", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        val color = roadColor(position / last)
        Slider(value = position, onValueChange = { onMinutes(scenicDetourMinutesAt(it)) }, valueRange = 0f..last,
            enabled = enabled,
            thumb = {
                Image(rememberVectorPainter(sideCar(if (enabled) color else PlainRoad)),
                    contentDescription = "寄り道 ${stage.name}", modifier = Modifier.size(40.dp))
            },
            track = { RoadTrack(if (enabled) color else PlainRoad, position / last) }, modifier = Modifier.overhang(20.dp))
    }
}

/** 寄り道が増えるほど、道の色が灰色 → 緑 → 橙と変わる */
private fun roadColor(t: Float): Color =
    if (t < .5f) lerp(Asphalt, Green, t * 2) else lerp(Green, Orange, (t - .5f) * 2)

/** 道路に中央の白い破線。車が通った後ろ側だけ色を付ける */
@Composable
private fun RoadTrack(color: Color, passed: Float) {
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Asphalt, cornerRadius = r)
        clipRect(right = size.width * passed) { drawRoundRect(color, cornerRadius = r) }
        drawLine(Color.White.copy(alpha = .6f), Offset(size.height / 2, size.height / 2), Offset(size.width - size.height / 2, size.height / 2),
            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())))
    }
}

private val Asphalt = Color(0xFF7D8480)
// 動かしても目に障らないよう、くすませた緑と橙にする
private val Green = Color(0xFF7FA58A)
private val Orange = Color(0xFFCF9A74)

// 右を向いた横向きの車（Phosphor Icons「car-profile」fill、MIT）。車体は道と同じ色、窓とタイヤは白
private fun sideCar(body: Color): ImageVector = ImageVector.Builder("SideCar", 40.dp, 40.dp, 256f, 256f)
    .addPath(addPathNodes("M240,112H211.31L168,68.69A15.86,15.86,0,0,0,156.69,64H44.28A16,16,0,0,0,31,71.12L1.34,115.56A8.07,8.07,0,0,0,0,120v48a16,16,0,0,0,16,16H33a32,32,0,0,0,62,0h66a32,32,0,0,0,62,0h17a16,16,0,0,0,16-16V128A16,16,0,0,0,240,112Z"),
        fill = SolidColor(body), stroke = SolidColor(Color.White), strokeLineWidth = 10f)
    .addPath(addPathNodes("M44.28,80H156.69l32,32H23Z"), fill = SolidColor(Color.White))
    .addPath(addPathNodes("M64,152a24,24,0,1,1,0,48a24,24,0,1,1,0,-48ZM192,152a24,24,0,1,1,0,48a24,24,0,1,1,0,-48Z"), fill = SolidColor(Color.White))
    .build()

/** スライダーは車の半分の幅だけ道を内側に縮めるので、その分だけ左右にはみ出させて道を長く見せる */
private fun Modifier.overhang(each: Dp) = layout { measurable, constraints ->
    val extra = each.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
