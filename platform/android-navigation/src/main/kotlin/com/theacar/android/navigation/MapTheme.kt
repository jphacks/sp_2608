package com.theacar.android.navigation

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

// 地図の配信元（OpenFreeMap liberty）の色を、旅れこのテーマ色（paper・mist・sky・sunglow・ink）に寄せて塗り替える
internal fun applyTabiMapColors(style: Style) {
    style.layers.forEach { layer ->
        val id = layer.id
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(Land))
            is FillExtrusionLayer -> layer.setProperties(PropertyFactory.fillExtrusionColor(Building))
            is FillLayer -> fillColor(id)?.let { layer.setProperties(PropertyFactory.fillColor(it)) }
            is LineLayer -> lineColor(id)?.let { layer.setProperties(PropertyFactory.lineColor(it)) }
            is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor(if (id.startsWith("label_") || id.startsWith("water")) Ink else Muted),
                PropertyFactory.textHaloColor(Land),
            )
        }
    }
}

private fun fillColor(id: String): String? = when {
    id == "water" -> Water
    id == "park" || id in setOf("landcover_wood", "landcover_grass", "landcover_wetland") -> Green
    id == "landuse_residential" -> Residential
    id == "landcover_sand" -> Sand
    id == "building" -> Building
    id.startsWith("landuse_") || id == "aeroway_fill" -> Facility
    else -> null
}

private fun lineColor(id: String): String? = when {
    id.startsWith("waterway") -> WaterLine
    id == "park_outline" -> Green
    id.startsWith("boundary") -> Muted
    id.endsWith("_casing") -> Casing
    id.contains("rail") -> Rail
    id.contains("path_pedestrian") -> Casing
    id.endsWith("motorway") || id.endsWith("motorway_link") || id.endsWith("trunk_primary") -> MajorRoad
    id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_") || id.startsWith("aeroway_") -> Road
    else -> null
}

private const val Land = "#F5F3EC" // paper
private const val Residential = "#EFECE3"
private const val Facility = "#ECE8DE"
private const val Building = "#E5E1D6"
private const val Green = "#D6E7DE" // mist を薄めた色
private const val Water = "#BCDAD6" // sky を薄めた色
private const val WaterLine = "#9FC9C3"
private const val Sand = "#EFE6CC"
private const val MajorRoad = "#F1DC9C" // sunglow を薄めた色
private const val Road = "#FFFFFF" // surface
private const val Casing = "#DDD8CB"
private const val Rail = "#B8BDB5"
private const val Ink = "#1E2A24"
private const val Muted = "#5C6B63"
