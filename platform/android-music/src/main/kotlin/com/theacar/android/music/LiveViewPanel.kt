package com.theacar.android.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.music.LiveReporterState

/**
 * 走行中の選曲の様子を Web のページで見せる。オンの間、案内中の位置・経路・景色・候補の点数を数秒ごとに送る。
 * ページの URL は受付番号を作り直すまで変わらないので、展示のモニターなどに開いたままにしておける。
 */
@Composable
internal fun LiveViewPanel(
    configured: Boolean,
    state: LiveReporterState,
    url: String?,
    onEnabled: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onRenew: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("選曲の様子をWebで見せる", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = onEnabled, enabled = configured)
            }
            if (!configured) {
                Text("Web版の送り先（MEMORY_WEB_URL と MEMORY_WEB_TOKEN）を設定して再ビルドすると使えます。",
                    style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text("ナビの案内中に、地図上の位置・これから通る景色・次の曲の候補と点数を、下のページで見られます。" +
                "車が走っていないときは、ページはデモの走行を流します。", style = MaterialTheme.typography.bodyMedium)
            if (url != null) {
                Text(url, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(url) }) { Text("開く") }
                    OutlinedButton(onClick = { onShare(url) }) { Text("共有") }
                    TextButton(onClick = onRenew) { Text("URLを作り直す") }
                }
            }
            if (state.enabled) {
                Text(
                    when {
                        state.failing -> "送れていません。電波の良いところで自動でやり直します。"
                        state.lastSentAtMillis != null -> "送信中（案内中だけ送ります）"
                        else -> "ナビの案内を始めると送り始めます。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("URLを知っている人は誰でも、案内中の現在地・目的地・流している曲を見られます。" +
                "音声・同乗者の名前・プレイリストの持ち主は送りません。見せたくなくなったら、オフにするかURLを作り直してください。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
