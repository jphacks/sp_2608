package com.theacar.android.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.theacar.ui.CardCorner
import com.theacar.ui.Muted

@Composable
internal fun NavigationSettings(
    token: String,
    onTokenChanged: suspend (String) -> Boolean,
    avoidTolls: Boolean,
    onAvoidTollsChanged: (Boolean) -> Unit,
    avoidHighways: Boolean,
    onAvoidHighwaysChanged: (Boolean) -> Unit,
    voice: Boolean,
    onVoiceChanged: (Boolean) -> Unit,
    canPlan: Boolean,
    onClose: () -> Unit,
    tokenLoaded: Boolean,
    tokenError: String?,
    tokenFromBuild: Boolean,
    routingUrl: String,
    builtInRoutingUrl: String,
    onRoutingUrlChanged: (String) -> Boolean,
) {
    var draft by remember(token) { mutableStateOf(token) }
    var urlDraft by remember(routingUrl) { mutableStateOf(routingUrl) }
    var urlInvalid by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    NavigationDialog(title = "ナビの設定", onDismiss = onClose, dismissEnabled = !saving) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsSection("案内") {
                SettingSwitch("音声案内", voice, onVoiceChanged, !saving)
            }
            SettingsSection("道路の条件") {
                SettingSwitch("有料道路を避ける", avoidTolls, onAvoidTollsChanged, canPlan && !saving)
                SettingSwitch("高速道路を避ける", avoidHighways, onAvoidHighwaysChanged, canPlan && !saving)
            }
            Text(if (canPlan) "道路条件の変更後は、経路を再検索してください。" else "道路条件と認証は案内終了後に変更できます。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DisclosureButton("接続・認証", showCredentials, { showCredentials = !showCredentials }, enabled = !saving)
            if (showCredentials || tokenError != null || urlInvalid) {
                OutlinedTextField(value = urlDraft, onValueChange = { urlDraft = it; urlInvalid = false }, label = { Text("経路サーバーの URL") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canPlan && !saving, isError = urlInvalid,
                    shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text(when {
                    urlInvalid -> "https:// で始まる URL を入力してください。"
                    routingUrl != builtInRoutingUrl -> "アプリに同梱した接続先から変更しています。変更すると、アプリが起動し直します。"
                    else -> "Tunnel の URL が変わったときは、ここを書き換えます。変更すると、アプリが起動し直します。"
                }, style = MaterialTheme.typography.bodySmall,
                    color = if (urlInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (routingUrl != builtInRoutingUrl) {
                    TextButton(onClick = { urlInvalid = !onRoutingUrlChanged("") }, enabled = canPlan && !saving) { Text("同梱の接続先に戻す") }
                }
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("経路 API のトークン") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canPlan && tokenLoaded && !saving,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text(if (tokenFromBuild) "アプリに同梱したトークンで接続しています。入力は不要です。別のサーバーへ繋ぐときだけ上書きしてください。"
                    else "端末内に暗号化して保存し、次回以降も使用します。バックアップには含めません。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!tokenLoaded) Text("保存済みトークンを読み込んでいます…", style = MaterialTheme.typography.bodySmall)
                tokenError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    scope.launch {
                        saving = true
                        try { if (onTokenChanged("")) draft = "" }
                        finally { saving = false }
                    }
                }, enabled = canPlan && tokenLoaded && !saving && !tokenFromBuild &&
                    (token.isNotEmpty() || draft.isNotEmpty() || tokenError != null)) { Text("保存済み認証を削除") }
            }
        }
        Button(enabled = !saving && tokenLoaded, onClick = {
            scope.launch {
                saving = true
                try {
                    if (!canPlan) onClose()
                    else if (onTokenChanged(draft)) {
                        // URL を変えたときは保存後にアプリが起動し直すので、ここで閉じる必要はない
                        if (urlDraft.trim().trimEnd('/') == routingUrl) onClose() else urlInvalid = !onRoutingUrlChanged(urlDraft)
                    }
                }
                finally { saving = false }
            }
        }, modifier = Modifier.fillMaxWidth().padding(24.dp).heightIn(min = 48.dp)) {
            Text(if (saving) "保存中…" else "保存して閉じる")
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Muted)
        Surface(shape = RoundedCornerShape(CardCorner), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChanged: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChanged, enabled = enabled)
    }
}
