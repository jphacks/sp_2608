package com.theacar.location

enum class LocationUse(val maximumAccuracyMeters: Float, val maximumAgeMillis: Long) {
    ROUTE_PREVIEW(500f, 120_000),
    /**
     * 案内の開始だけはおおよその位置で許す。発進前や屋内寄りの駐車場では 40m に届かないことが多い。
     * 開始後の追跡・逸脱判定・到着判定は [GUIDANCE] のままで、精度が上がるまで右左折の案内を保留する。
     */
    GUIDANCE_START(100f, 30_000),
    GUIDANCE(40f, 10_000),
}

fun LocationState.usableFix(use: LocationUse, nowMillis: Long): LocationFix? {
    if (status != LocationStatus.AVAILABLE && status != LocationStatus.STALE) return null
    return fix?.takeIf {
        it.accuracyMeters <= use.maximumAccuracyMeters &&
            nowMillis - it.elapsedRealtimeMillis in 0..use.maximumAgeMillis
    }
}
