package com.theacar.memory

import com.theacar.model.Coordinate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** ステッカー1コマの縦横比（1/4 L判）と写真面の高さの割合。 */
const val STICKER_WIDTH_MM = 44.5
const val STICKER_HEIGHT_MM = 63.5
const val STICKER_PHOTO_FRACTION = 0.65
const val STICKER_ROUTE_MARGIN = 0.12

/** 写真面の中の位置。0..1 の正規化座標で、x は右向き、y は下向き。 */
data class StickerPoint(val x: Double, val y: Double)

data class StickerProjection(val route: List<StickerPoint>, val photoSpot: StickerPoint?)

/**
 * 経路（と写真の位置）を写真面に収める。経度は cos(緯度) で縮め、縦横比を保ったまま中央寄せし、
 * 辺の [margin] 分を余白にする。[aspect] は写真面の 幅/高さ。
 */
fun projectRoute(
    geometry: List<Coordinate>,
    photoLocation: Coordinate? = null,
    aspect: Double = STICKER_WIDTH_MM / (STICKER_HEIGHT_MM * STICKER_PHOTO_FRACTION),
    margin: Double = STICKER_ROUTE_MARGIN,
): StickerProjection {
    require(aspect > 0 && margin >= 0 && margin < 0.5)
    val all = geometry + listOfNotNull(photoLocation)
    if (all.isEmpty()) return StickerProjection(emptyList(), null)
    val minLat = all.minOf { it.latitude }; val maxLat = all.maxOf { it.latitude }
    val minLon = all.minOf { it.longitude }; val maxLon = all.maxOf { it.longitude }
    val scaleX = cos((minLat + maxLat) / 2 * PI / 180).coerceAtLeast(1e-6)
    val width = (maxLon - minLon) * scaleX
    val height = maxLat - minLat
    // 幅/高さともに高さ 1 の面の中で使える大きさ（横は aspect 倍）
    val availableWidth = aspect * (1 - 2 * margin)
    val availableHeight = 1 - 2 * margin
    val scale = when {
        width <= 0.0 && height <= 0.0 -> 0.0
        else -> min(availableWidth / max(width, 1e-12), availableHeight / max(height, 1e-12))
    }
    val centerX = (minLon + maxLon) / 2 * scaleX
    val centerY = (minLat + maxLat) / 2
    fun place(c: Coordinate) = StickerPoint(
        x = 0.5 + (c.longitude * scaleX - centerX) * scale / aspect,
        y = 0.5 - (c.latitude - centerY) * scale,
    )
    return StickerProjection(geometry.map(::place), photoLocation?.let(::place))
}
