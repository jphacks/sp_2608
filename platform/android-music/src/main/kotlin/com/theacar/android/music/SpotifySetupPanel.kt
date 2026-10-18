package com.theacar.android.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Spotify の初期設定。ダッシュボードで作成したアプリIDをこの画面で保存するので、再ビルドは不要。
 */
@Composable
internal fun SpotifySetupPanel(
    connection: SpotifyConnectionState,
    onSaveClientId: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var editing by rememberSaveable(connection.configured) { mutableStateOf(!connection.configured) }
    var clientId by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val entered = clientId.trim()
    val valid = SpotifyAuthSettings.isClientId(entered)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editing) {
                Text("Spotifyの設定は完了しています。", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { editing = true }) { Text("別のアプリIDに入れ替える") }
                return@Column
            }

            Text("Spotifyの初期設定", style = MaterialTheme.typography.titleLarge)
            Text("自分のSpotifyアカウントでこのアプリを動かすため、Spotify側でアプリを1つ作ります。5分ほどで終わります。",
                style = MaterialTheme.typography.bodyMedium)
            Text("Spotify Premium が必要です。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            SetupStep(1, "ダッシュボードで「Create app」を押す") {
                Text("アプリ名と説明は自由です。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onOpenUrl(SpotifyAuthSettings.DASHBOARD_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Spotify Developer Dashboard を開く")
                }
            }

            SetupStep(2, "「Redirect URI」にこれを貼って Add を押す") {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Text(SpotifyAuthSettings.REDIRECT_URI, Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(SpotifyAuthSettings.REDIRECT_URI))
                        copied = true
                    }) { Text("コピー") }
                    if (copied) Text("  コピーしました", style = MaterialTheme.typography.bodySmall)
                }
                Text("1文字でも違うとログインが弾かれます。手入力せずコピーしてください。",
                    style = MaterialTheme.typography.bodySmall)
            }

            SetupStep(3, "「Which API/SDKs are you planning to use?」で Web API を選ぶ") {
                Text("他の項目は不要です。そのまま Save を押します。", style = MaterialTheme.typography.bodySmall)
            }

            SetupStep(4, "作成したアプリの Settings で Client ID をコピーして貼る") {
                Text("Client Secret は使いません。貼らないでください。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID（英数字32文字）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !connection.loading,
                    isError = entered.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    supportingText = {
                        Text(when {
                            entered.isEmpty() -> "ダッシュボードの Client ID をそのまま貼り付けます。"
                            !valid -> "英数字32文字で入力してください（今 ${entered.length} 文字）。"
                            else -> "この内容で保存できます。"
                        })
                    })
                Button(onClick = { onSaveClientId(entered); editing = false }, enabled = valid && !connection.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存して次へ") }
                Text("端末内に暗号化して保存します。保存後はこの画面でログインできます。",
                    style = MaterialTheme.typography.bodySmall)
            }

            SetupStep(5, "同じ画面の User Management に自分のSpotifyアカウントを追加する") {
                Text("開発モードのアプリは、ここに登録した人（5人まで）しかログインできません。" +
                    "同乗者の端末でも使う場合は、その人のアカウントも追加します。", style = MaterialTheme.typography.bodySmall)
            }

            if (connection.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            connection.failure?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            if (connection.configured) TextButton(onClick = { editing = false }) { Text("設定をやめて戻る") }
        }
    }
}

@Composable
private fun SetupStep(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                Text("$number", Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Column(Modifier.padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
