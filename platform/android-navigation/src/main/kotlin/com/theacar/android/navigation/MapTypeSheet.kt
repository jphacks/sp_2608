package com.theacar.android.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theacar.ui.Ink
import com.theacar.ui.Muted

/**
 * 地図の種類を選ぶ下からのシート。Google マップの「地図の種類」と同じく、見本の絵を並べ、押すとすぐ地図が替わる。
 * 選んでいる方は、見本の枠と名前を主色にする。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapTypeSheet(satellite: Boolean, onSatelliteChanged: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("地図の種類", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "閉じる", tint = Ink) }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MapTypeOption("デフォルト", !satellite, { onSatelliteChanged(false) }) { drawMapSample() }
                MapTypeOption("航空写真", satellite, { onSatelliteChanged(true) }) { drawSatelliteSample() }
            }
        }
    }
}

@Composable
private fun MapTypeOption(label: String, selected: Boolean, onSelect: () -> Unit, sample: DrawScope.() -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.selectable(selected, onClick = onSelect, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(14.dp)
        Surface(shape = shape, border = BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) accent else Muted.copy(alpha = .3f))) {
            Canvas(Modifier.size(76.dp).padding(if (selected) 3.dp else 0.dp).clip(RoundedCornerShape(11.dp))) { sample() }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) accent else Ink,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** 見本：ふだんの地図（MapTheme.kt の昼の色） */
private fun DrawScope.drawMapSample() {
    drawRect(Color(0xFFF3EFE6))
    drawCircle(Color(0xFFD3EBC8), size.width * .32f, Offset(size.width * .22f, size.height * .26f))
    drawPath(Path().apply {
        moveTo(size.width * .62f, size.height); lineTo(size.width, size.height * .48f); lineTo(size.width, size.height); close()
    }, Color(0xFFAAD3EE))
    drawRoad(Color(0xFFC9CDD2), Color.White, size.width * .1f)
}

/** 見本：航空写真（畑と森と海の色を、写真のように重ねる） */
private fun DrawScope.drawSatelliteSample() {
    drawRect(Color(0xFF4D6A3D))
    drawRect(Color(0xFF6E7F4C), Offset(0f, size.height * .55f), Size(size.width * .45f, size.height * .45f))
    drawRect(Color(0xFF8C8A5E), Offset(size.width * .05f, size.height * .6f), Size(size.width * .18f, size.height * .3f))
    drawCircle(Color(0xFF2E4A2B), size.width * .28f, Offset(size.width * .2f, size.height * .2f))
    drawPath(Path().apply {
        moveTo(size.width * .62f, size.height); lineTo(size.width, size.height * .48f); lineTo(size.width, size.height); close()
    }, Color(0xFF1D3B4F))
    drawRoad(Color(0xD9F7D26E), Color(0x80FFFFFF), size.width * .06f)
}

private fun DrawScope.drawRoad(major: Color, minor: Color, width: Float) {
    drawLine(major, Offset(0f, size.height * .72f), Offset(size.width, size.height * .18f), strokeWidth = width)
    drawPath(Path().apply {
        moveTo(size.width * .35f, 0f); quadraticTo(size.width * .5f, size.height * .5f, size.width * .3f, size.height)
    }, minor, style = Stroke(width * .55f))
}
