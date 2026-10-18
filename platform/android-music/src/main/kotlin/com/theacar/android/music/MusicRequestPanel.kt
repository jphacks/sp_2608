package com.theacar.android.music

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun MusicRequestPanel(runtime: AndroidSpotifyRuntime) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("music-request-settings", 0) }
    var url by rememberSaveable { mutableStateOf(prefs.getString("server", "").orEmpty()) }
    val state by runtime.requests.state.collectAsState()
    val music by runtime.session.state.collectAsState()
    var error by remember { mutableStateOf("") }
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("みんなで曲を追加", style = MaterialTheme.typography.titleLarge)
            Text("同乗者はQRから検索・追加できます。最初のリクエストで再生を始め、以降はSpotifyのキューへ追加します。")
            if (state.active) {
                val bitmap = remember(state.inviteUrl) { musicRequestQr(state.inviteUrl).asImageBitmap() }
                Image(bitmap, "曲を追加する参加用QRコード", Modifier.size(240.dp))
                Text("DHUの「曲を追加」からもQRを表示できます。")
                Text("Spotifyへ送信済み: ${state.sent}曲")
                OutlinedButton(onClick = { runtime.requests.stop() }) { Text("受付終了") }
            } else {
                OutlinedTextField(url, { url = it }, label = { Text("受付サーバーのHTTPS URL") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Text("先に再生先を選んでください。受付中はその再生先を使用します。", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !state.busy && !music.busy && music.selectedDeviceId != null && url.isNotBlank(), onClick = {
                    error = ""
                    prefs.edit().putString("server", url.trim()).apply()
                    try {
                        context.startForegroundService(Intent(context, MusicRequestService::class.java)
                            .putExtra("url", url.trim()).putExtra("device", music.selectedDeviceId))
                    } catch (_: RuntimeException) { error = "受付を開始できませんでした。画面を開いたまま再試行してください。" }
                }) { Text(if (state.busy) "開始中…" else "リクエスト受付を開始") }
            }
            if (state.message.isNotEmpty()) Text(state.message)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
