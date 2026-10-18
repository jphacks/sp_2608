package com.theacar.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.dashboard.components.AtmosphereControl
import com.theacar.drive.DriveState
import com.theacar.model.Scenery
import com.theacar.ui.CardCorner
import com.theacar.ui.Ink
import com.theacar.ui.Muted
import com.theacar.ui.Surface as SurfaceWhite

@Composable
fun SettingsScreen(
    state: DriveState,
    demoActions: DemoActions?,
    onOpenWindow: (() -> Unit)?,
    onOpenMusic: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp)) {
        Text("設定", Modifier.padding(top = 22.dp, bottom = 18.dp), color = Ink, style = MaterialTheme.typography.headlineSmall)
        if (onOpenMusic != null) {
            SectionTitle("音楽")
            Surface(onClick = onOpenMusic, color = SurfaceWhite, shape = RoundedCornerShape(CardCorner)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Spotify 連携", Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (onOpenWindow != null) {
            SectionTitle("オプション")
            Surface(onClick = onOpenWindow, color = SurfaceWhite, shape = RoundedCornerShape(CardCorner)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("窓の操作", Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (demoActions != null) {
            SectionTitle("開発用")
            Surface(color = SurfaceWhite, shape = RoundedCornerShape(CardCorner)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AtmosphereControl(
                        value = state.context.conversationLevel,
                        onValueChange = demoActions.setConversationLevel,
                        onCoverSound = demoActions.emitUnusualSound,
                    )
                    TextButton(onClick = demoActions.cycleScene) {
                        Text("景色テスト · ${state.context.scenery.label()}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(bottom = 8.dp), color = Muted, style = MaterialTheme.typography.titleSmall)
}

private fun Scenery.label(): String = when (this) {
    Scenery.UNKNOWN -> "未判定"
    Scenery.COASTAL -> "海辺"
    Scenery.FOREST -> "森"
    Scenery.URBAN -> "街"
}
