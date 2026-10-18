package com.theacar.memory

import com.theacar.model.Coordinate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class PhotoStyle(val label: String) { WATERCOLOR("水彩"), POSTER("ポスター風"), FILM("フィルムカメラ風") }

/** 1回の旅の記録。ナビの到着時に自動で作られ、写真とメンバーはあとから足す。 */
data class TripMemory(
    val id: String,
    val startedAtEpochMillis: Long,
    val arrivedAtEpochMillis: Long,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val destinationTitle: String,
    val destinationCoordinate: Coordinate,
    val placeName: String? = null,
    val geometry: List<Coordinate> = emptyList(),
    val members: List<String> = emptyList(),
    val photoStyle: PhotoStyle? = null,
    val photoLocation: Coordinate? = null,
    val timeZoneId: String = "UTC",
    /** 「シールの画像を保存」で書いた MediaStore の行。2 回目以降は同じ行を上書きする。 */
    val galleryUri: String? = null,
    val photoVersion: Long? = null,
) {
    init {
        require(id.isNotBlank() && destinationTitle.isNotBlank())
        require(distanceMeters >= 0 && durationSeconds >= 0)
        require(arrivedAtEpochMillis >= startedAtEpochMillis)
    }
}

fun TripMemory.toJson(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
    put("id", id)
    put("startedAt", startedAtEpochMillis)
    put("arrivedAt", arrivedAtEpochMillis)
    put("distanceMeters", distanceMeters)
    put("durationSeconds", durationSeconds)
    put("destinationTitle", destinationTitle)
    put("destination", destinationCoordinate.toJson())
    put("placeName", placeName?.let(::JsonPrimitive) ?: JsonNull)
    put("geometry", buildJsonArray { geometry.forEach { add(it.toJson()) } })
    put("members", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
    put("photoStyle", photoStyle?.let { JsonPrimitive(it.name) } ?: JsonNull)
    put("photoLocation", photoLocation?.toJson() ?: JsonNull)
    put("timeZoneId", timeZoneId)
    put("galleryUri", galleryUri?.let(::JsonPrimitive) ?: JsonNull)
    put("photoVersion", photoVersion?.let(::JsonPrimitive) ?: JsonNull)
})

fun parseTripMemory(text: String): TripMemory {
    val o = Json.parseToJsonElement(text).jsonObject
    return TripMemory(
        id = o.getValue("id").jsonPrimitive.content,
        startedAtEpochMillis = o.getValue("startedAt").jsonPrimitive.long,
        arrivedAtEpochMillis = o.getValue("arrivedAt").jsonPrimitive.long,
        distanceMeters = o.getValue("distanceMeters").jsonPrimitive.int,
        durationSeconds = o.getValue("durationSeconds").jsonPrimitive.int,
        destinationTitle = o.getValue("destinationTitle").jsonPrimitive.content,
        destinationCoordinate = o.getValue("destination").jsonArray.toCoordinate(),
        placeName = o["placeName"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull,
        geometry = o["geometry"]?.jsonArray?.map { it.jsonArray.toCoordinate() }.orEmpty(),
        members = o["members"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        photoStyle = o["photoStyle"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { PhotoStyle.valueOf(it) },
        photoLocation = o["photoLocation"]?.takeUnless { it is JsonNull }?.jsonArray?.toCoordinate(),
        timeZoneId = o["timeZoneId"]?.jsonPrimitive?.content ?: "UTC",
        galleryUri = o["galleryUri"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull,
        photoVersion = o["photoVersion"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
    )
}

// 座標は [lat, lon] の配列で持つ（経路が長いので短く）
private fun Coordinate.toJson() = buildJsonArray { add(JsonPrimitive(latitude)); add(JsonPrimitive(longitude)) }
private fun JsonArray.toCoordinate() = Coordinate(this[0].jsonPrimitive.double, this[1].jsonPrimitive.double)
