package com.theacar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// caption と color は各画面の見出し帯（DRIVE / RECORD）にそろえる。color は選んでいるタブの下地
enum class Tab(val label: String, internal val caption: String, internal val icon: ImageVector, internal val color: Color) {
    MAP("マップ", "DRIVE", Icons.Rounded.LocationOn, Sky),
    RECORD("旅の記録", "RECORD", Icons.Rounded.Album, Sunset),
    SETTINGS("設定", "SETTINGS", Icons.Rounded.Settings, Mist),
}

/** 全画面共通の下部タブバー。キーボード表示中は隠す。 */
@Composable
fun TabiBottomBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) return
    Column(modifier.fillMaxWidth().background(Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Mist))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    Modifier.weight(1f).height(64.dp)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { if (!isSelected) onSelect(tab) })
                        .semantics { contentDescription = tab.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(
                            Modifier.size(width = 56.dp, height = 32.dp)
                                .background(if (isSelected) tab.color else Surface, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(tab.icon, contentDescription = null, tint = if (isSelected) Ink else Muted, modifier = Modifier.size(24.dp))
                        }
                        Text(tab.caption, style = MaterialTheme.jost.label.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            color = if (isSelected) Ink else Muted)
                    }
                }
            }
        }
    }
}
