package com.theacar.memory

/** 一覧用。長い経路形状や写真データは持たない。 */
data class TripMemorySummary(
    val id: String,
    val arrivedAtEpochMillis: Long,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val destinationTitle: String,
    val placeName: String?,
    val members: List<String>,
    val timeZoneId: String,
    val photoVersion: Long?,
)

fun TripMemory.summary() = TripMemorySummary(id, arrivedAtEpochMillis, distanceMeters, durationSeconds,
    destinationTitle, placeName, members, timeZoneId, photoVersion ?: photoStyle?.let { 0L })
