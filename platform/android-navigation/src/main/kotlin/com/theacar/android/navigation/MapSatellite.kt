package com.theacar.android.navigation

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * 航空写真は国土地理院のシームレス空中写真（地理院タイル）を使う。
 * アプリで読み込んで表示するだけなら申請は要らず、出典（地理院タイル）を示せばよい。
 * Google の航空写真は、Google 以外の地図（この OSM の地図）と混ぜて使えないので使わない。
 */
private const val SATELLITE_ID = "gsi-seamlessphoto"
private const val SATELLITE_TILES = "https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg"
internal const val SATELLITE_ATTRIBUTION = "地理院タイル"
internal const val SATELLITE_ATTRIBUTION_URL = "https://maps.gsi.go.jp/development/ichiran.html"

/**
 * 航空写真を地の上に敷き、写真を覆う面（緑地・水・建物など）を隠す。道・線路・地名は写真の上に残す。
 * 色は [applyTabiMapColors] の satellite で写真向けにしてから呼ぶ。
 * 配信元のスタイルには最初から隠れているレイヤーがないので、戻すときは全部を見える状態にすればよい。
 */
internal fun applySatellite(style: Style, enabled: Boolean) {
    if (enabled) {
        if (style.getSource(SATELLITE_ID) == null) {
            val tiles = TileSet("2.2.0", SATELLITE_TILES).apply {
                minZoom = 2f
                maxZoom = 18f
                attribution = "<a href=\"$SATELLITE_ATTRIBUTION_URL\">$SATELLITE_ATTRIBUTION</a>"
            }
            style.addSource(RasterSource(SATELLITE_ID, tiles, 256))
        }
        if (style.getLayer(SATELLITE_ID) == null) {
            val photo = RasterLayer(SATELLITE_ID, SATELLITE_ID)
            // 背景（写真のない沖合の色）のすぐ上、ほかのどのレイヤーよりも下に敷く
            val bottom = style.layers.firstOrNull()?.id
            if (bottom != null) style.addLayerAbove(photo, bottom) else style.addLayer(photo)
        }
    } else {
        style.getLayer(SATELLITE_ID)?.let(style::removeLayer)
        style.getSource(SATELLITE_ID)?.let(style::removeSource)
    }
    style.layers.forEach { layer ->
        if (layer.id == SATELLITE_ID) return@forEach
        val visible = !(enabled && hiddenOnSatellite(layer))
        layer.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE))
    }
}

private fun hiddenOnSatellite(layer: Layer): Boolean = when (layer) {
    // 写真を覆ってしまう面と、低い拡大率で使う陰影の画像
    is FillLayer, is FillExtrusionLayer, is RasterLayer -> true
    // 道の縁取り・線路の縞・公園の輪郭は、写真の上では線が太くうるさく見える
    is LineLayer -> layer.id.endsWith("_casing") || layer.id.endsWith("_hatching") || layer.id == "park_outline"
    else -> false
}
