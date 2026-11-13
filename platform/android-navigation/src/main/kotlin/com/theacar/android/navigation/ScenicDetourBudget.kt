package com.theacar.android.navigation

import kotlin.math.abs
import kotlin.math.roundToInt

internal val scenicDetourMinutes = (0..30 step 5).toList() +
    listOf(45, 60, 90, 120, 180, 240, 300, 360)

internal fun scenicDetourIndex(minutes: Int): Int =
    scenicDetourMinutes.indices.minBy { abs(scenicDetourMinutes[it] - minutes) }

internal fun scenicDetourLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}分"
    minutes % 60 == 0 -> "${minutes / 60}時間"
    else -> "${minutes / 60}時間${minutes % 60}分"
}

/** 無段階のスライダーの位置（0〜表の最後の番号）から寄り道の分へ。表の値の間はなめらかにつなぐ */
internal fun scenicDetourMinutesAt(position: Float): Int {
    val p = position.coerceIn(0f, scenicDetourMinutes.lastIndex.toFloat())
    val i = p.toInt().coerceAtMost(scenicDetourMinutes.lastIndex - 1)
    val (from, to) = scenicDetourMinutes[i] to scenicDetourMinutes[i + 1]
    return (from + (to - from) * (p - i)).roundToInt()
}

/** scenicDetourMinutesAt の逆。寄り道の分からスライダーの位置へ */
internal fun scenicDetourPosition(minutes: Int): Float {
    val m = minutes.coerceIn(scenicDetourMinutes.first(), scenicDetourMinutes.last())
    val i = (scenicDetourMinutes.indexOfLast { it <= m }).coerceAtMost(scenicDetourMinutes.lastIndex - 1)
    val (from, to) = scenicDetourMinutes[i] to scenicDetourMinutes[i + 1]
    return i + (m - from).toFloat() / (to - from)
}
