package com.theacar.android.navigation

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

// 地図の配信元（OpenFreeMap liberty）の色を、旅れこのテーマ色（paper・mist・sky・sunglow・ink）に寄せて塗り替える。
// [dark] のときは車載画面の夜の表示に合わせ、同じ塗り分けを暗い色で行う
internal fun applyTabiMapColors(style: Style, dark: Boolean = false) {
    val c = if (dark) DarkColors else LightColors
    style.layers.forEach { layer ->
        val id = layer.id
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(c.land))
            is FillExtrusionLayer -> layer.setProperties(PropertyFactory.fillExtrusionColor(c.building))
            is FillLayer -> fillColor(id, c)?.let { layer.setProperties(PropertyFactory.fillColor(it)) }
            is LineLayer -> lineColor(id, c)?.let { layer.setProperties(PropertyFactory.lineColor(it)) }
            is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor(if (id.startsWith("label_") || id.startsWith("water")) c.ink else c.muted),
                PropertyFactory.textHaloColor(c.land),
            )
        }
    }
}

private fun fillColor(id: String, c: MapColors): String? = when {
    id == "water" -> c.water
    id == "park" || id in setOf("landcover_wood", "landcover_grass", "landcover_wetland") -> c.green
    id == "landuse_residential" -> c.residential
    id == "landcover_sand" -> c.sand
    id == "building" -> c.building
    id.startsWith("landuse_") || id == "aeroway_fill" -> c.facility
    else -> null
}

private fun lineColor(id: String, c: MapColors): String? = when {
    id.startsWith("waterway") -> c.waterLine
    id == "park_outline" -> c.green
    id.startsWith("boundary") -> c.muted
    id.endsWith("_casing") -> c.casing
    id.contains("rail") -> c.rail
    id.contains("path_pedestrian") -> c.casing
    id.endsWith("motorway") || id.endsWith("motorway_link") || id.endsWith("trunk_primary") -> c.majorRoad
    id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_") || id.startsWith("aeroway_") -> c.road
    else -> null
}

private class MapColors(
    val land: String, val residential: String, val facility: String, val building: String, val green: String,
    val water: String, val waterLine: String, val sand: String, val majorRoad: String, val road: String,
    val casing: String, val rail: String, val ink: String, val muted: String,
)

private val LightColors = MapColors(
    land = "#F5F3EC", // paper
    residential = "#EFECE3",
    facility = "#ECE8DE",
    building = "#E5E1D6",
    green = "#D6E7DE", // mist を薄めた色
    water = "#BCDAD6", // sky を薄めた色
    waterLine = "#9FC9C3",
    sand = "#EFE6CC",
    majorRoad = "#F1DC9C", // sunglow を薄めた色
    road = "#FFFFFF", // surface
    casing = "#DDD8CB",
    rail = "#B8BDB5",
    ink = "#1E2A24",
    muted = "#5C6B63",
)

// 夜は ink を地にして、緑・水・幹線の色味だけを残す。文字は paper に寄せる
private val DarkColors = MapColors(
    land = "#1B2420",
    residential = "#202A25",
    facility = "#222C27",
    building = "#2A3530",
    green = "#1F3A30",
    water = "#173439",
    waterLine = "#2B5A5F",
    sand = "#3A3526",
    majorRoad = "#7A6630",
    road = "#3B4741",
    casing = "#2C3631",
    rail = "#55605A",
    ink = "#E8ECE6",
    muted = "#A9B5AE",
)
