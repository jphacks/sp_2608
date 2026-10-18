package com.theacar.location

fun LocationFix.shouldReplace(previous: LocationFix?, nowMillis: Long): Boolean {
    if (nowMillis - elapsedRealtimeMillis !in 0..120_000) return false
    if (previous == null) return true
    if (elapsedRealtimeMillis <= previous.elapsedRealtimeMillis) return false
    val previousIsPrecise = previous.accuracyMeters <= 40 && nowMillis - previous.elapsedRealtimeMillis in 0..10_000
    return !previousIsPrecise || accuracyMeters <= 40
}
