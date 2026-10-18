package com.theacar.android.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.theacar.ui.Ink
import com.theacar.ui.Mist
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Sky
import com.theacar.ui.Surface as SurfaceColor
import com.theacar.ui.jost

// 地図の上に浮かぶ面は影を付けないため、縁を Ink の12%で補う
internal val FloatingBorder = BorderStroke(1.dp, Ink.copy(alpha = .12f))

@Composable
internal fun MapIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.size(48.dp), shape = CircleShape, border = FloatingBorder) {
        Icon(icon, contentDescription = description, tint = Ink, modifier = Modifier.size(48.dp).padding(12.dp))
    }
}

@Composable
internal fun tabiChipColors(selected: Color = Sky, selectedLabel: Color = Ink): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = SurfaceColor, labelColor = Ink,
    selectedContainerColor = selected, selectedLabelColor = selectedLabel,
)

// 選んでいないチップは白抜きに、選んだときの色の細い縁。選んだチップは塗りだけ
internal fun tabiChipBorder(selected: Boolean, color: Color = Sky): BorderStroke? = if (selected) null else BorderStroke(1.dp, color)

@Composable
internal fun mistButtonColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(containerColor = Mist, contentColor = Ink)

// 開閉の操作は文言を入れ替えず、同じ名前のまま矢印の向きで開閉を示す
@Composable
internal fun DisclosureButton(label: String, expanded: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onToggle, enabled = enabled) {
        Text(label)
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
    }
}

// オン・オフは文言を入れ替えるボタンにせず、名前とスイッチで状態を示す
@Composable
internal fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Ink)
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = Mountains, checkedThumbColor = SurfaceColor,
            uncheckedTrackColor = Mist, uncheckedThumbColor = Muted, uncheckedBorderColor = Mist))
    }
}

private val NumberRun = Regex("[0-9A-Za-z.,:+\\-%]+")

/** 数字・英字の連なりだけ Jost にする。日本語は既定のフォントのまま。 */
@Composable
internal fun jostNumbers(text: String): AnnotatedString {
    val family = MaterialTheme.jost.statUnit.fontFamily
    return buildAnnotatedString {
        var last = 0
        NumberRun.findAll(text).forEach { match ->
            append(text.substring(last, match.range.first))
            pushStyle(SpanStyle(fontFamily = family))
            append(match.value)
            pop()
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
}
