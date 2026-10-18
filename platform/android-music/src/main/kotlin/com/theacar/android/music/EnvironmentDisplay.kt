package com.theacar.android.music

import com.theacar.location.LocationState
import com.theacar.location.LocationStatus
import com.theacar.model.Coordinate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun Coordinate.display(decimals: Int = 5): String = String.format(Locale.US, "%.${decimals}f, %.${decimals}f", latitude, longitude)

internal fun displayTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("MM/dd HH:mm:ss z", Locale.JAPAN).withZone(zone).format(Instant.ofEpochMilli(millis))

internal fun LocationState.displayStatus(nowElapsedMillis: Long): String {
    val latest = fix
    return when {
    status == LocationStatus.PERMISSION_REQUIRED -> "位置情報の許可が必要"
    status == LocationStatus.DISABLED -> "端末の位置情報がオフ"
    latest == null -> "現在地は未取得"
    nowElapsedMillis - latest.elapsedRealtimeMillis !in 0..120_000 -> "古い位置情報・選曲には使用できません"
    latest.accuracyMeters > 500 -> "精度が不足・選曲には使用できません"
    status == LocationStatus.STALE -> "位置の更新待ち"
    else -> "位置情報を取得済み"
    }
}

internal fun locationAgeSeconds(nowElapsedMillis: Long, fixElapsedMillis: Long): Long? =
    (nowElapsedMillis - fixElapsedMillis).takeIf { it >= 0 }?.div(1_000)
