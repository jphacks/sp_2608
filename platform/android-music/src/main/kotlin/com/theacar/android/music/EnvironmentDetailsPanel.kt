package com.theacar.android.music

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theacar.location.LocationState
import com.theacar.music.DriveMusicState
import com.theacar.weather.WeatherReadState
import kotlinx.coroutines.delay
import java.util.Locale
import com.theacar.ui.CardCorner

@Composable
internal fun EnvironmentDetailsPanel(
    location: LocationState,
    weather: WeatherReadState,
    check: EnvironmentCheckStatus,
    drive: DriveMusicState,
    onInspect: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clock by produceState(System.currentTimeMillis() to SystemClock.elapsedRealtime()) {
        while (true) {
            value = System.currentTimeMillis() to SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val busy = weather.loading || check == EnvironmentCheckStatus.LOCATING || check == EnvironmentCheckStatus.FETCHING
    val observation = weather.observation
    Surface(shape = RoundedCornerShape(CardCorner), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("現在地・天気", style = MaterialTheme.typography.titleLarge)
            Text(location.displayStatus(clock.second), style = MaterialTheme.typography.bodyMedium)
            location.fix?.let { Text("端末の最終位置  ${it.coordinate.display()}", style = MaterialTheme.typography.bodyMedium) }
            if (observation == null) Text("気象情報は未取得です。音楽を再生せずに確認できます。")
            else {
                val data = observation.weather
                Text("${data.weather.label} · ${String.format(Locale.JAPAN, "%.1f℃", data.temperatureCelsius)} · ${if (data.isDay) "昼" else "夜"}", style = MaterialTheme.typography.titleMedium)
                Text("取得日時  ${displayTime(observation.fetchedAtMillis)}", style = MaterialTheme.typography.bodySmall)
                if (clock.first - data.observedAtMillis !in -300_000..3_600_000) Text("古い気象情報です。再取得してください。", color = MaterialTheme.colorScheme.error)
            }
            if (weather.failed) Text(if (observation == null) "気象情報を取得できませんでした。通信状態を確認して再試行してください。"
                else "気象情報の再取得に失敗しました。表示中の値は前回の結果です。", color = MaterialTheme.colorScheme.error)
            if (check == EnvironmentCheckStatus.LOCATION_UNAVAILABLE) Text("利用できる現在地が取得できませんでした。位置情報の設定とGPSの受信状態を確認してください。", color = MaterialTheme.colorScheme.error)
            if (busy) {
                Text(if (check == EnvironmentCheckStatus.LOCATING) "現在地を確認しています…" else "気象情報を取得しています…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onInspect, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("現在地・天気を取得して確認")
            }
            Text("約1km単位に丸めた座標をOpen-Meteoへ送ります。同じ地域の取得結果は1分間再利用します。音楽は再生しません。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "詳細を閉じる" else "取得情報の詳細を見る") }
            if (expanded) {
                HorizontalDivider()
                Text("端末の位置情報", style = MaterialTheme.typography.titleMedium)
                location.fix?.let { fix ->
                    Detail("緯度・経度", fix.coordinate.display())
                    Detail("端末が報告した水平精度", String.format(Locale.JAPAN, "約 %.0f m", fix.accuracyMeters))
                    val age = locationAgeSeconds(clock.second, fix.elapsedRealtimeMillis)
                    Detail("位置の更新", age?.let { "${it}秒前" } ?: "不明")
                } ?: Detail("取得状態", "未取得")
                Detail("地名・住所", "未取得（座標からの住所検索は行っていません）")
                observation?.let { snapshot ->
                    val data = snapshot.weather
                    Text("取得済みの気象情報", style = MaterialTheme.typography.titleMedium)
                    Detail("取得に使った端末位置", snapshot.coordinate.display())
                    Detail("APIへ送信した座標", data.requestedCoordinate?.display(2) ?: "不明")
                    Detail("予報地点（気象モデル）", data.gridCoordinate?.display() ?: "APIから返されていません")
                    Detail("天気", data.weather.label)
                    Detail("気温", String.format(Locale.JAPAN, "%.1f℃", data.temperatureCelsius))
                    Detail("昼夜", if (data.isDay) "昼" else "夜")
                    Detail("周辺標高（気象モデル）", String.format(Locale.JAPAN, "%.0f m", data.elevationMeters))
                    Detail("気象モデルの対象時刻", displayTime(data.observedAtMillis))
                    Detail("端末で取得した時刻", displayTime(snapshot.fetchedAtMillis))
                    Text("実測観測ではなく気象モデルの推定値です。予報の格子点は端末位置と一致しないことがあります。", style = MaterialTheme.typography.bodySmall)
                }
                Text("選曲計画で参照した情報", style = MaterialTheme.typography.titleMedium)
                val usedWeather = drive.weather
                if (usedWeather == null) Detail("選曲計画", "まだ作成されていません")
                else {
                    Detail("使用した気象の対象時刻", displayTime(usedWeather.observedAtMillis))
                    Detail("使用した位置", drive.coordinate?.display() ?: "不明")
                    Detail("計画作成時刻", displayTime(drive.plannedAtMillis))
                    drive.journey?.let {
                        Detail("目的地", it.destination)
                        Detail("計画時の残り時間", "約${(it.remainingSeconds + 59) / 60}分（推定）")
                        it.cues.take(3).forEach { cue -> Detail(cue.label, "計画時点から約${(cue.secondsFromNow.coerceAtLeast(0) + 59) / 60}分後（推定）") }
                    } ?: Detail("ルート", "選曲時に案内中のルートなし")
                    Text("確認ボタンで新しい天気を取得しても、再生中の曲や既存の選曲計画は変更しません。", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { onOpenUrl("https://open-meteo.com/") }) { Text("Weather data by Open-Meteo · CC BY 4.0") }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
