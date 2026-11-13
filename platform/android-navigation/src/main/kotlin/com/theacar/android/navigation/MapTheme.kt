package com.theacar.android.navigation

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

// 地図の配信元（OpenFreeMap liberty）の色を、読みやすい塗り分けに塗り替える。
// [dark] のときは車載画面の夜の表示に合わせ、同じ塗り分けを暗い色で行う。
// [satellite] のときは航空写真の上に重ねる色にする（面は MapSatellite.kt で隠すので、線と文字だけが効く）
internal fun applyTabiMapColors(style: Style, dark: Boolean = false, satellite: Boolean = false) {
    val c = when {
        satellite -> SatelliteColors
        dark -> DarkColors
        else -> LightColors
    }
    style.layers.forEach { layer ->
        val id = layer.id
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(c.land))
            is FillExtrusionLayer -> layer.setProperties(PropertyFactory.fillExtrusionColor(c.building))
            is FillLayer -> fillColor(id, c)?.let {
                layer.setProperties(PropertyFactory.fillColor(it))
                // 元のスタイルは森や草地を薄く透かしているので、緑がはっきり見えるようにする
                if (id in GreenLayers) layer.setProperties(PropertyFactory.fillOpacity(1f))
            }
            is LineLayer -> lineColor(id, c)?.let { layer.setProperties(PropertyFactory.lineColor(it)) }
            is SymbolLayer -> layer.setProperties(
                if (id in PoiLayers) PropertyFactory.textColor(poiTextColor(c))
                else PropertyFactory.textColor(
                    when {
                        id.startsWith("water") -> c.waterText
                        id.startsWith("label_") -> c.ink
                        else -> c.muted
                    },
                ),
                PropertyFactory.textHaloColor(c.land),
            )
        }
    }
}

private val PoiLayers = setOf("poi_r1", "poi_r7", "poi_r20")

// 店や施設の名前を、Google マップと同じ考え方で種類ごとに色分けする（OpenMapTiles の poi の class）
private val FoodClasses = listOf("restaurant", "fast_food", "cafe", "bar", "beer", "ice_cream", "bakery")
private val ShopClasses = listOf("shop", "grocery", "clothing_store", "alcohol_shop")
private val HealthClasses = listOf("hospital", "doctors", "dentist", "pharmacy")
private val NatureClasses = listOf("park", "attraction", "campsite", "zoo", "museum", "art_gallery", "castle", "monument")
private val LodgingClasses = listOf("lodging")

private fun poiTextColor(c: MapColors): Expression {
    val stops = listOf(FoodClasses to c.food, ShopClasses to c.shop, HealthClasses to c.health,
        NatureClasses to c.nature, LodgingClasses to c.lodging)
        .flatMap { (classes, color) -> classes.map { Expression.stop(it, Expression.color(android.graphics.Color.parseColor(color))) } }
    return Expression.match(Expression.get("class"), Expression.color(android.graphics.Color.parseColor(c.muted)), *stops.toTypedArray())
}

private val GreenLayers = setOf("park", "landcover_wood", "landcover_grass", "landcover_wetland")

private fun fillColor(id: String, c: MapColors): String? = when {
    id == "water" -> c.water
    id == "landcover_wood" -> c.wood
    id in GreenLayers -> c.green
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
    val land: String, val residential: String, val facility: String, val building: String, val green: String, val wood: String,
    val water: String, val waterLine: String, val sand: String, val majorRoad: String, val road: String,
    val casing: String, val rail: String, val ink: String, val muted: String, val waterText: String,
    val food: String, val shop: String, val health: String, val nature: String, val lodging: String,
)

// Google マップのような塗り分け：地は薄いベージュ、道はグレー、緑は緑、水は水色
private val LightColors = MapColors(
    land = "#F3EFE6",
    residential = "#EEEAE0",
    facility = "#EAE6DC",
    building = "#E3DFD5",
    green = "#D3EBC8",
    wood = "#BFDEAE",
    water = "#AAD3EE",
    waterLine = "#8EC2E6",
    sand = "#F3E8C6",
    majorRoad = "#C9CDD2",
    road = "#DDE0E3",
    casing = "#C2C6CB",
    rail = "#BDBFC2",
    ink = "#3C4043",
    muted = "#5F6368",
    waterText = "#3E7BB0",
    food = "#C5590A",
    shop = "#1A62C9",
    health = "#C5221F",
    nature = "#1E7A3C",
    lodging = "#B0306A",
)

// 航空写真の上では、Google マップの航空写真と同じく、幹線は黄色、ほかの道は半透明の白、文字は白に暗い縁取りにする。
// land は写真のない所（沖合など）の地の色と、文字の縁取りに使う。面の色は使わない（面ごと隠す）
private val SatelliteColors = MapColors(
    land = "#0E1B24",
    residential = "#0E1B24",
    facility = "#0E1B24",
    building = "#0E1B24",
    green = "rgba(255, 255, 255, 0.35)",
    wood = "#0E1B24",
    water = "#0E1B24",
    waterLine = "rgba(170, 211, 238, 0.5)",
    sand = "#0E1B24",
    majorRoad = "rgba(247, 210, 110, 0.85)",
    road = "rgba(255, 255, 255, 0.5)",
    casing = "rgba(0, 0, 0, 0)",
    rail = "rgba(255, 255, 255, 0.35)",
    ink = "#FFFFFF",
    muted = "#F1F3F4",
    waterText = "#AFD3F2",
    food = "#F6B26B",
    shop = "#9CC2FA",
    health = "#F6A29B",
    nature = "#97D7A8",
    lodging = "#F5A3C4",
)

// 夜は ink を地にして、緑・水・幹線の色味だけを残す。文字は paper に寄せる
private val DarkColors = MapColors(
    land = "#1B2420",
    residential = "#202A25",
    facility = "#222C27",
    building = "#2A3530",
    green = "#1F3A30",
    wood = "#1B3429",
    water = "#173439",
    waterLine = "#2B5A5F",
    sand = "#3A3526",
    majorRoad = "#7A6630",
    road = "#3B4741",
    casing = "#2C3631",
    rail = "#55605A",
    ink = "#E8ECE6",
    muted = "#A9B5AE",
    waterText = "#8FC3E8",
    food = "#F2A65A",
    shop = "#8AB4F8",
    health = "#F28B82",
    nature = "#81C995",
    lodging = "#F48FB1",
)
