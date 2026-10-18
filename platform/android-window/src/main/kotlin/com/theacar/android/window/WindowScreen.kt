package com.theacar.android.window

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.cabin.CabinAir
import com.theacar.cabin.WindowLink
import com.theacar.ui.CardCorner
import com.theacar.ui.Danger
import com.theacar.ui.Ink
import com.theacar.ui.Mist
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Paper
import com.theacar.ui.Tab
import com.theacar.ui.TabiBottomBar
import com.theacar.ui.Surface as SurfaceWhite

@Composable
fun WindowScreen(
    deviceName: String,
    permitted: Boolean,
    link: WindowLink,
    air: CabinAir?,
    log: List<String>,
    failure: String?,
    onRequestPermission: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onDismissFailure: () -> Unit,
    onBack: () -> Unit,
    onSelectTab: (Tab) -> Unit,
) {
    Scaffold(containerColor = Paper, bottomBar = { TabiBottomBar(Tab.SETTINGS, onSelectTab) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("戻る") }
                Spacer(Modifier.weight(1f))
                Text("窓の操作", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(64.dp))
            }

            LinkRow(deviceName, link)
            Spacer(Modifier.height(16.dp))

            if (link == WindowLink.UNCONFIGURED) {
                NotReady(permitted, onRequestPermission)
            } else {
                Controls(enabled = link == WindowLink.CONNECTED, onOpen = onOpen, onClose = onClose, onStop = onStop)
                Spacer(Modifier.height(10.dp))
                Text("1回押すと0.5秒動きます。窓に手や物がないことを確認してください。",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }

            failure?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Danger, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismissFailure) { Text("閉じる") }
            }

            Spacer(Modifier.height(20.dp))
            AirRow(air)
            Spacer(Modifier.height(20.dp))
            Text("機器のログ", style = MaterialTheme.typography.titleSmall, color = Muted)
            Spacer(Modifier.height(8.dp))
            LogList(log, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LinkRow(deviceName: String, link: WindowLink) {
    Surface(color = Mist, shape = RoundedCornerShape(CardCorner)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("接続先", style = MaterialTheme.typography.labelMedium, color = Ink)
                Text(deviceName, style = MaterialTheme.typography.titleMedium, color = Ink)
            }
            Surface(color = if (link == WindowLink.CONNECTED) Mountains else Muted.copy(alpha = .4f), shape = CircleShape) {
                Spacer(Modifier.size(10.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text(link.label(), style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
private fun NotReady(permitted: Boolean, onRequestPermission: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (permitted) {
            Text(
                "端末の Bluetooth が切れています。入れると機器を探し始めます。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        } else {
            Text(
                "近くにある機器を探すために、Bluetooth の許可が必要です。" +
                    "位置情報の取得には使いません。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mountains, contentColor = SurfaceWhite),
            ) {
                Text("Bluetooth を許可する")
            }
        }
    }
}

@Composable
private fun Controls(enabled: Boolean, onOpen: () -> Unit, onClose: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onOpen, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mountains, contentColor = SurfaceWhite)) {
            Text("開ける")
        }
        Button(onClick = onClose, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mountains, contentColor = SurfaceWhite)) {
            Text("閉める")
        }
        OutlinedButton(onClick = onStop, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text("停止")
        }
    }
}

@Composable
private fun AirRow(air: CabinAir?) {
    Surface(color = Mist, shape = RoundedCornerShape(CardCorner)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("車内の空気", style = MaterialTheme.typography.labelMedium, color = Ink)
            if (air == null) {
                Text("測定を待っています", style = MaterialTheme.typography.bodyMedium, color = Ink)
            } else {
                Text("ガス ${air.gasKiloOhm}kΩ · 普段比 -${air.dropPercent.toInt()}%",
                    style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("湿度 ${air.humidityPercent}% · ${if (air.warmingUp) "暖機中" else "検知中"}",
                    style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
    }
}

@Composable
private fun LogList(log: List<String>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) state.scrollToItem(log.lastIndex)
    }
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        items(log) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

private fun WindowLink.label(): String = when (this) {
    WindowLink.UNCONFIGURED -> "準備待ち"
    WindowLink.CONNECTING -> "探索中"
    WindowLink.CONNECTED -> "接続済"
    WindowLink.UNAVAILABLE -> "切断"
}
