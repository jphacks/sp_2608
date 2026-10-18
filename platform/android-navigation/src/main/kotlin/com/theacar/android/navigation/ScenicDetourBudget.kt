package com.theacar.android.navigation

import kotlin.math.abs

internal val scenicDetourMinutes = (0..30 step 5).toList() +
    listOf(45, 60, 90, 120, 180, 240, 300, 360)

internal fun scenicDetourIndex(minutes: Int): Int =
    scenicDetourMinutes.indices.minBy { abs(scenicDetourMinutes[it] - minutes) }

internal fun scenicDetourLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}分"
    minutes % 60 == 0 -> "${minutes / 60}時間"
    else -> "${minutes / 60}時間${minutes % 60}分"
}
