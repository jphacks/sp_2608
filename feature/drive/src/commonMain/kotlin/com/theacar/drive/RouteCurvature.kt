package com.theacar.drive

import com.theacar.model.Coordinate
import kotlin.math.*

/**
 * 経路の曲率（rad/m）を一定間隔で求める。
 *
 * 景観エンジンの `curvature_profile`（infra/scenic/recommendation/terrain.py）と同じ計算にしている。
 * 点列を [spacingMeters] ごとに取り直し、前後3点で平均してから、隣り合う向きの差を距離で割る。
 * 両端と、点が足りない経路の値は null。
 */
internal fun curvatureProfile(points: List<Coordinate>, spacingMeters: Double = CURVATURE_SPACING_METERS): List<Double?> {
    if (points.isEmpty()) return emptyList()
    val sampled = mutableListOf(points.first())
    var traveled = 0.0
    var target = spacingMeters
    for ((first, second) in points.zipWithNext()) {
        val length = planarDistance(first, second)
        if (length <= 0) continue
        while (target <= traveled + length) {
            val fraction = (target - traveled) / length
            sampled += Coordinate(first.latitude + (second.latitude - first.latitude) * fraction,
                first.longitude + (second.longitude - first.longitude) * fraction)
            target += spacingMeters
        }
        traveled += length
    }
    if (sampled.size < 5) return List(sampled.size) { null }
    val smooth = listOf(sampled.first()) + (1 until sampled.lastIndex).map { i ->
        val window = sampled.subList(i - 1, i + 2)
        Coordinate(window.sumOf { it.latitude } / 3, window.sumOf { it.longitude } / 3)
    } + sampled.last()
    val profile = mutableListOf<Double?>(null)
    for (i in 0 until smooth.size - 2) {
        val (a, b, c) = Triple(smooth[i], smooth[i + 1], smooth[i + 2])
        val delta = abs(((bearing(b, c) - bearing(a, b) + 180) % 360 + 360) % 360 - 180)
        profile += (delta * PI / 180) / max(1.0, (planarDistance(a, b) + planarDistance(b, c)) / 2)
    }
    profile += null
    return profile
}

/** 景観エンジンが運転負荷の「急カーブ」に使う正規化（0〜1）。0.01 rad/m で 0、0.04 rad/m で 1。 */
internal fun sharpness(curvature: Double): Double = ((curvature - 0.01) / 0.03).coerceIn(0.0, 1.0)

internal const val CURVATURE_SPACING_METERS = 25.0

// 景観エンジンの geometry.distance / store.bearing と同じ近似。
private fun planarDistance(a: Coordinate, b: Coordinate): Double {
    val latitude = (a.latitude + b.latitude) / 2 * PI / 180
    return hypot((a.latitude - b.latitude) * 111_195, (a.longitude - b.longitude) * 111_195 * cos(latitude))
}

private fun bearing(a: Coordinate, b: Coordinate): Double {
    val dy = (b.latitude - a.latitude) * PI / 180
    val dx = (b.longitude - a.longitude) * PI / 180 * cos((a.latitude + b.latitude) / 2 * PI / 180)
    return (atan2(dx, dy) * 180 / PI + 360) % 360
}
