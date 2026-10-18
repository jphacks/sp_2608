package com.theacar.memory

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

const val STICKER_MEMBER_LIMIT = 6
/** Web 側が受け付ける上限と同じ。メンバーは 20 名まで、1 名 40 文字まで。 */
const val MEMBER_COUNT_LIMIT = 20
const val MEMBER_NAME_LENGTH_LIMIT = 40
const val PLACE_NAME_LENGTH_LIMIT = 40

/** 情報面に出す文字。フォントの使い分けは描画側で行う。 */
data class StickerText(
    val year: String,
    val monthDay: String,
    val place: String,
    val members: List<String>,
    val distanceKm: String,
    val hours: String,
    val minutes: String,
)

fun TripMemory.stickerText(): StickerText {
    val (year, monthDay) = dateLines(arrivedAtEpochMillis, timeZoneId)
    val (hours, minutes) = durationParts(durationSeconds)
    return StickerText(year, monthDay, placeName ?: destinationShortName(destinationTitle), memberLines(members),
        distanceKm(distanceMeters).toString(), hours.toString(), minutes.toString())
}

/** 到着時刻を「2026」「10.17 sat.」の2行にする。 */
fun dateLines(epochMillis: Long, timeZoneId: String): Pair<String, String> {
    val zone = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
    val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
    val weekday = date.dayOfWeek.name.take(3).lowercase()
    return date.year.toString() to "${date.monthNumber.toString().padStart(2, '0')}.${date.dayOfMonth.toString().padStart(2, '0')} $weekday."
}

/** 秒を時と分に分ける。分は四捨五入。 */
fun durationParts(seconds: Int): Pair<Int, Int> {
    val totalMinutes = (seconds + 30) / 60
    return totalMinutes / 60 to totalMinutes % 60
}

fun distanceKm(meters: Int): Int = (meters / 1000.0).roundToInt()

/** 目的地検索の表示名「函館山 · 北海道函館市… · 山」から、利用者が選んだ名前の部分だけを取り出す。 */
fun destinationShortName(title: String): String = title.substringBefore(" · ").trim().ifEmpty { title }

/** 利用者が入力した地名。空白だけなら未入力（null）扱いにし、目的地名に戻す。 */
fun placeNameInput(text: String): String? = text.trim().take(PLACE_NAME_LENGTH_LIMIT).ifEmpty { null }

/** 入力した 1 名を追加する。空白だけなら無視し、Web 側の上限（人数・文字数）を超える分は入れない。 */
fun addMember(members: List<String>, name: String): List<String> {
    val trimmed = name.trim().take(MEMBER_NAME_LENGTH_LIMIT)
    if (trimmed.isEmpty() || members.size >= MEMBER_COUNT_LIMIT) return members
    return members + trimmed
}

/** 表示するメンバー。7名以上は 6名と「+N」。 */
fun memberLines(members: List<String>, limit: Int = STICKER_MEMBER_LIMIT): List<String> =
    if (members.size <= limit) members else members.take(limit) + "+${members.size - limit}"
