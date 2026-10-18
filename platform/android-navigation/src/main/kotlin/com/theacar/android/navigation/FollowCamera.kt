package com.theacar.android.navigation

/**
 * 現在地を追うカメラの決め方。倍率を固定にすると、走っていても画面が止まって見える。
 * 止まっているときは手元を広く、速いときは先を見せるように引く。
 */
internal object FollowCamera {
    const val NEAR = 17.5
    const val FAR = 14.5
    /** この速さで FAR に届く。日本の高速道路の巡航。 */
    const val FAST_METERS_PER_SECOND = 27.8f
    /** 速度が取れないときの倍率。 */
    const val DEFAULT = 16.0

    const val TILT = 35.0
    /** これより遅いと傾けない。駐車場や渋滞で傾くと見づらい。 */
    const val TILT_METERS_PER_SECOND = 5f

    fun zoom(speedMetersPerSecond: Float?): Double {
        val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 } ?: return DEFAULT
        val ratio = (speed / FAST_METERS_PER_SECOND).coerceIn(0f, 1f)
        return NEAR - (NEAR - FAR) * ratio
    }

    fun tilt(speedMetersPerSecond: Float?): Double {
        val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 } ?: return 0.0
        return if (speed >= TILT_METERS_PER_SECOND) TILT else 0.0
    }
}
