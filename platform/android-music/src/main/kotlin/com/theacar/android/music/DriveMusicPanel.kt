package com.theacar.android.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.location.LocationStatus
import com.theacar.music.DriveMusicState
import com.theacar.ui.CardCorner
import com.theacar.ui.Sunglow

@Composable
internal fun DriveMusicPanel(
    state: DriveMusicState,
    locationStatus: LocationStatus,
    canPlay: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var showSchedule by remember { mutableStateOf(false) }
    Surface(color = Sunglow, shape = RoundedCornerShape(CardCorner)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("いまの景色に、音楽を。", style = MaterialTheme.typography.titleLarge)
            Text("天気と案内中のルートから、曲の長さも考えて再生順を組み立てます。", style = MaterialTheme.typography.bodyMedium)
            state.journey?.let {
                Text("${it.destination}まで 約${(it.remainingSeconds + 59) / 60}分", style = MaterialTheme.typography.titleMedium)
                Text("高速道路・経由地・到着の見込み時刻を考慮", style = MaterialTheme.typography.bodySmall)
            } ?: Text("ナビの案内を開始するとルートも選曲に使います。", style = MaterialTheme.typography.bodySmall)
            state.weather?.let {
                Text("選曲に使用した天気：${it.weather.label} · ${it.temperatureCelsius.toInt()}℃ · ${if (it.isDay) "昼" else "夜"}", style = MaterialTheme.typography.titleMedium)
            }
            state.theme?.let {
                Text(it.title, style = MaterialTheme.typography.titleMedium)
                Text(it.reason)
            }
            if (state.waitingForLocation) Text(when (locationStatus) {
                LocationStatus.PERMISSION_REQUIRED -> "位置情報の許可が必要です。"
                LocationStatus.DISABLED -> "端末の位置情報をオンにしてください。"
                else -> "現在地を取得しています。屋外などGPSを受信できる場所でお待ちください。"
            })
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failure?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            if (state.needsPlayback && !state.loading) Text("新しい選曲候補があります。今の曲を変える場合は、もう一度おまかせ再生してください。")
            Button(onClick = onStart, enabled = canPlay && !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (state.enabled) "この提案で再生し直す" else "おまかせ再生")
            }
            if (state.enabled) TextButton(onClick = onStop) { Text("環境の自動更新を終了") }
            Text("開始すると約1km単位に丸めた座標をOpen-Meteoへ送信します。音楽画面を閉じると環境取得は終了し、Spotifyの再生は続きます。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onOpenUrl("https://open-meteo.com/") }) { Text("Weather data by Open-Meteo · CC BY 4.0") }
            if (state.schedule.isNotEmpty()) {
                TextButton(onClick = { showSchedule = !showSchedule }) { Text(if (showSchedule) "再生順の提案を閉じる" else "再生順とタイミングを見る") }
                if (showSchedule) {
                    Text("セット再生開始からの予測。渋滞・一時停止・シャッフルなどでずれます。", style = MaterialTheme.typography.bodySmall)
                    state.schedule.forEach { slot ->
                        val minutes = slot.startsAfterSeconds / 60
                        val seconds = (slot.startsAfterSeconds % 60).toString().padStart(2, '0')
                        Text("+$minutes:$seconds · ${slot.theme.title}", style = MaterialTheme.typography.labelLarge)
                        Text(slot.track.title + " · " + slot.track.artist, style = MaterialTheme.typography.bodyMedium)
                        if (slot.durationEstimated) Text("曲の長さが未取得のため3分30秒として計算", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onOpenUrl(slot.track.url) }) { Text("Spotifyで見る") }
                    }
                }
            }
        }
    }
}
