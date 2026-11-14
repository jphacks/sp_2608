package com.theacar.memory.records

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** レコードに刻む曲。その道を走っている間に流れていた曲。[uri] は spotify:track:…、[url] は Spotify で開くページ */
data class RecordSong(val uri: String, val title: String, val artist: String, val url: String)

/**
 * 手に入れたレコード1枚。最初に手に入れた日と曲は、同じ道をまた走っても変えない。
 * [timesDriven] は走り切った回数。
 */
data class RoadRecordEntry(
    val id: String,
    val firstUnlockedAtEpochMillis: Long,
    val lastUnlockedAtEpochMillis: Long,
    val timesDriven: Int,
    val song: RecordSong? = null,
)

/** 手に入れたレコードの一覧と、まだ着いたときの演出を見せていないレコード（取れた順） */
data class RoadRecordBook(
    val entries: Map<String, RoadRecordEntry> = emptyMap(),
    val pendingCelebration: List<String> = emptyList(),
) {
    /** 走り切った道を足す。初めてなら演出を待つ列にも入れる */
    fun unlock(id: String, atEpochMillis: Long, song: RecordSong?): RoadRecordBook {
        val old = entries[id]
        val entry = old?.copy(lastUnlockedAtEpochMillis = atEpochMillis, timesDriven = old.timesDriven + 1,
            song = old.song ?: song)
            ?: RoadRecordEntry(id, atEpochMillis, atEpochMillis, 1, song)
        val pending = if (old == null && id !in pendingCelebration) pendingCelebration + id else pendingCelebration
        return copy(entries = entries + (id to entry), pendingCelebration = pending)
    }

    fun celebrated(ids: Collection<String>) = copy(pendingCelebration = pendingCelebration - ids.toSet())

    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", 1)
        put("entries", buildJsonArray {
            entries.values.sortedBy { it.firstUnlockedAtEpochMillis }.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id)
                    put("firstUnlockedAt", e.firstUnlockedAtEpochMillis)
                    put("lastUnlockedAt", e.lastUnlockedAtEpochMillis)
                    put("timesDriven", e.timesDriven)
                    e.song?.let { song ->
                        put("song", buildJsonObject {
                            put("uri", song.uri); put("title", song.title); put("artist", song.artist); put("url", song.url)
                        })
                    }
                })
            }
        })
        put("pendingCelebration", buildJsonArray { pendingCelebration.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    })

    companion object {
        fun parse(text: String): RoadRecordBook {
            val root = Json.parseToJsonElement(text).jsonObject
            val entries = root["entries"]?.jsonArray.orEmpty().map { it.jsonObject }.map { e ->
                val song = e["song"]?.jsonObject?.let { s ->
                    RecordSong(s.getValue("uri").jsonPrimitive.content, s.getValue("title").jsonPrimitive.content,
                        s.getValue("artist").jsonPrimitive.content, s.getValue("url").jsonPrimitive.content)
                }
                RoadRecordEntry(e.getValue("id").jsonPrimitive.content, e.getValue("firstUnlockedAt").jsonPrimitive.long,
                    e.getValue("lastUnlockedAt").jsonPrimitive.long, e.getValue("timesDriven").jsonPrimitive.int, song)
            }
            val pending = (root["pendingCelebration"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            return RoadRecordBook(entries.associateBy { it.id }, pending)
        }
    }
}
