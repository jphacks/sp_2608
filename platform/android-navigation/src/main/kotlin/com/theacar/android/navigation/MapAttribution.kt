package com.theacar.android.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theacar.ui.Ink

/** 地図の左下に出す短い出典。航空写真の追加の出典は長いので、押して開く一覧に載せる */
internal fun mapAttributionLine(satellite: Boolean) =
    if (satellite) "© OpenMapTiles © OpenStreetMap · © $SATELLITE_ATTRIBUTION ほか" else "© OpenMapTiles © OpenStreetMap"

/** 車の画面の地図の下に出す出典。航空写真のときは地理院タイルを足す */
fun carMapAttribution(satellite: Boolean) =
    "© OpenStreetMap contributors · MapLibre / Valhalla" + if (satellite) " · © $SATELLITE_ATTRIBUTION" else ""

private class Credit(val title: String, val text: String, val link: Pair<String, String>? = null)

/**
 * 地図の出典の一覧。航空写真（地理院タイル）は拡大率によって元の画像が違い、
 * 縮小したときの画像には、地理院タイル一覧で決められた追加の出典を、原文のまま示す。
 */
private val Credits = listOf(
    Credit("地図", "© OpenMapTiles © OpenStreetMap contributors",
        "OpenStreetMap の著作権とライセンス" to "https://www.openstreetmap.org/copyright"),
    Credit("航空写真", "地理院タイル（国土地理院）",
        "地理院タイル一覧" to SATELLITE_ATTRIBUTION_URL),
    Credit("航空写真を縮小したとき（市町村が見えるくらい）",
        "データソース：Landsat8画像（GSI,TSIC,GEO Grid/AIST）, Landsat8画像（courtesy of the U.S. Geological Survey）, 海底地形（GEBCO）"),
    Credit("航空写真をさらに縮小したとき（北海道より広い範囲）",
        "Images on 世界衛星モザイク画像 obtained from site https://lpdaac.usgs.gov/data_access maintained by the NASA Land Processes " +
            "Distributed Active Archive Center (LP DAAC), USGS/Earth Resources Observation and Science (EROS) Center, " +
            "Sioux Falls, South Dakota, (Year). Source of image data product."),
)

@Composable
internal fun MapAttributionDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    NavigationDialog(title = "地図の出典", onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Credits.forEach { credit ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(credit.title, style = MaterialTheme.typography.titleSmall, color = Ink, fontWeight = FontWeight.Bold)
                    Text(credit.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    credit.link?.let { (label, url) ->
                        TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}
