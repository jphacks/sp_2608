package com.theacar.android.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theacar.route.ScenicLandscape
import com.theacar.route.ScenicOptions
import com.theacar.route.ScenicPreference
import kotlin.math.roundToInt

internal val ScenicWeightSaver = mapSaver<Map<String, Double>>(
    save = { weights -> mapOf("version" to 1) + weights },
    restore = { saved -> ScenicLandscape.entries.mapNotNull { item ->
        (saved[item.code] as? Double)?.let { item.code to it }
    }.toMap() },
)

internal fun scenicWeightSummary(settings: ScenicOptions): String {
    val weights = settings.preferenceWeights
    val total = weights.values.sum()
    return weights.entries.sortedByDescending { it.value }.take(3).joinToString(" · ") { (key, value) ->
        "${ScenicLandscape.entries.first { it.code == key }.label} ${(value / total * 100).roundToInt()}%"
    }
}

@Composable
internal fun ScenicPreferenceEditor(settings: ScenicOptions, enabled: Boolean, canSearch: Boolean,
    onApply: (ScenicOptions, Boolean) -> Unit, onDismiss: () -> Unit) {
    var weights by rememberSaveable(stateSaver = ScenicWeightSaver) { mutableStateOf(settings.preferenceWeights) }
    var variety by rememberSaveable { mutableDoubleStateOf(settings.variety) }
    var passenger by rememberSaveable { mutableDoubleStateOf(settings.passengerView) }
    var roads by rememberSaveable { mutableDoubleStateOf(settings.certifiedRoad) }
    val valid = weights.values.any { it > 0 }
    fun result(): ScenicOptions {
        val positive = weights.filterValues { it > 0 }
        return settings.copy(customWeights = positive.takeUnless { it == settings.preference.weights },
            variety = variety, passengerView = passenger, certifiedRoad = roads)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("ドライブの好み") }, text = {
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("バーは相対的な優先度です。合計100にする必要はありません。0はその特徴を目的にしない設定で、通行禁止ではありません。",
                style = MaterialTheme.typography.bodySmall)
            if (valid) Text("評価の比率 · ${scenicWeightSummary(result())}", style = MaterialTheme.typography.labelMedium)
            ScenicLandscape.entries.forEach { item ->
                PreferenceSlider(item.label, weights[item.code] ?: 0.0, enabled) { weights = weights + (item.code to it) }
            }
            Text("海・湖・川は近さの推定です。渓谷は地形・地図からの推定で眺望は未確認、市街地は建物密度です。",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("体験の好み", style = MaterialTheme.typography.titleSmall)
            PreferenceSlider("景色の変化", variety, enabled) { variety = it }
            PreferenceSlider("助手席からの眺め", passenger, enabled) { passenger = it }
            Text("水辺の向きが取得できる区間で、進行方向と助手席側（左）を考慮します。実際の眺望は未確認です。",
                style = MaterialTheme.typography.bodySmall)
            PreferenceSlider("名道を優先", roads, enabled) { roads = it }
            Text("紹介資料と区間を確認した登録道路の加点を強めます。0でも登録道路の基本加点は残ります。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled, onClick = {
                weights = settings.preference.weights; variety = .5; passenger = 0.0; roads = 0.0
            }) { Text("${settings.preference.label}の初期値に戻す") }
            if (!valid) Text("景観のバーを少なくとも1つ上げてください。", color = MaterialTheme.colorScheme.error)
            Text("寄り道上限・夜間補正・道路条件は元の設定を維持します。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Column {
            if (canSearch) Button(enabled = enabled && valid, onClick = { onApply(result(), true) }) { Text("この設定で探索") }
            TextButton(enabled = enabled && valid, onClick = { onApply(result(), false) }) { Text("設定を適用") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

@Composable
private fun PreferenceSlider(label: String, value: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).roundToInt()}", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.toFloat(), enabled = enabled, steps = 19,
            modifier = Modifier.semantics { contentDescription = label },
            onValueChange = { onChange((it * 20).roundToInt() / 20.0) })
    }
}

@Composable
internal fun ScenicQuickSearch(settings: ScenicOptions, enabled: Boolean,
    onSelect: (ScenicOptions) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("どんな道を走りますか？") }, text = {
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ボタンを押すと探索を始めます。寄り道上限などの条件は維持し、景観の比重だけを切り替えます。",
                style = MaterialTheme.typography.bodySmall)
            listOf(ScenicPreference.BALANCED to "いろんな自然を楽しむ", ScenicPreference.SEA to "海沿いを楽しむ",
                ScenicPreference.FOREST to "森の道を走る", ScenicPreference.WATER to "湖や川を楽しむ",
                ScenicPreference.VALLEY to "渓谷を探す", ScenicPreference.FARMLAND to "田園を走る",
                ScenicPreference.URBAN to "街なかを走る").forEach { (preset, title) ->
                FilledTonalButton(enabled = enabled, onClick = { onSelect(settings.withPreset(preset)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(title) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}
