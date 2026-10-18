package com.theacar.music

import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LoggedTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val genre: String?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
)

/** [atEpochMillis] はその分の先頭。[speechRatio] は発話秒 / 観測秒（0..1）。 */
data class MoodMinute(val atEpochMillis: Long, val speechRatio: Double)

data class MusicLogRecord(
    val id: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val tracks: List<LoggedTrack>,
    val mood: List<MoodMinute>,
)

/**
 * 1回の案内のあいだに流れた曲と、1分ごとの発話割合を貯める。音声は扱わない。
 * 上限（曲 [MAX_TRACKS]・分 [MAX_MINUTES]）を超えたぶんは古いものから捨てる。
 */
@OptIn(ExperimentalUuidApi::class)
class DriveMusicLog(private val newId: () -> String = { Uuid.random().toString() }) {
    private var id: String? = null
    private var startedAt = 0L
    private val tracks = ArrayDeque<LoggedTrack>()
    /** 分 → (観測秒, 発話秒) */
    private val minutes = LinkedHashMap<Long, IntArray>()

    val active: Boolean get() = id != null

    fun start(nowEpochMillis: Long) {
        tracks.clear()
        minutes.clear()
        id = newId()
        startedAt = nowEpochMillis
    }

    fun onTrackStarted(track: RecommendedTrack, nowEpochMillis: Long) {
        if (!active) return
        onTrackStopped(nowEpochMillis)
        tracks.addLast(LoggedTrack(track.trackId, track.title, track.artist, null, nowEpochMillis, null))
        while (tracks.size > MAX_TRACKS) tracks.removeFirst()
    }

    fun onTrackStopped(nowEpochMillis: Long) {
        if (!active) return
        val last = tracks.lastOrNull() ?: return
        if (last.endedAtEpochMillis == null) tracks[tracks.lastIndex] = last.copy(endedAtEpochMillis = nowEpochMillis)
    }

    fun onSecond(nowEpochMillis: Long, speaking: Boolean) {
        if (!active) return
        val counts = minutes.getOrPut(nowEpochMillis.floorDiv(60_000L)) { IntArray(2) }
        counts[0]++
        if (speaking) counts[1]++
        while (minutes.size > MAX_MINUTES) minutes.remove(minutes.keys.first())
    }

    fun finish(nowEpochMillis: Long): MusicLogRecord? {
        val current = id ?: return null
        onTrackStopped(nowEpochMillis)
        val record = if (tracks.isEmpty() && minutes.isEmpty()) null else MusicLogRecord(
            current, startedAt, nowEpochMillis, tracks.toList(),
            minutes.map { (minute, counts) -> MoodMinute(minute * 60_000L, counts[1].toDouble() / counts[0]) },
        )
        id = null
        tracks.clear()
        minutes.clear()
        return record
    }

    companion object {
        const val MAX_TRACKS = 500
        const val MAX_MINUTES = 1_440
    }
}

fun MusicLogRecord.toJson(deviceId: String): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
    put("id", id)
    put("deviceId", deviceId)
    put("startedAtEpochMillis", startedAtEpochMillis)
    put("endedAtEpochMillis", endedAtEpochMillis)
    put("tracks", buildJsonArray {
        tracks.forEach { track ->
            add(buildJsonObject {
                put("trackId", track.trackId)
                put("title", track.title)
                put("artist", track.artist)
                put("genre", track.genre?.let(::JsonPrimitive) ?: JsonNull)
                put("startedAtEpochMillis", track.startedAtEpochMillis)
                put("endedAtEpochMillis", track.endedAtEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    })
    put("mood", buildJsonArray {
        mood.forEach { add(buildJsonObject { put("atEpochMillis", it.atEpochMillis); put("speechRatio", it.speechRatio) }) }
    })
})

fun parseMusicLogRecord(text: String): MusicLogRecord {
    val o = Json.parseToJsonElement(text).jsonObject
    return MusicLogRecord(
        id = o.getValue("id").jsonPrimitive.content,
        startedAtEpochMillis = o.getValue("startedAtEpochMillis").jsonPrimitive.long,
        endedAtEpochMillis = o.getValue("endedAtEpochMillis").jsonPrimitive.long,
        tracks = o.getValue("tracks").jsonArray.map { element ->
            val t = element.jsonObject
            LoggedTrack(
                t.getValue("trackId").jsonPrimitive.content, t.getValue("title").jsonPrimitive.content,
                t.getValue("artist").jsonPrimitive.content, t["genre"]?.jsonPrimitive?.contentOrNull,
                t.getValue("startedAtEpochMillis").jsonPrimitive.long, t["endedAtEpochMillis"]?.jsonPrimitive?.longOrNull,
            )
        },
        mood = o.getValue("mood").jsonArray.map { element ->
            val m = element.jsonObject
            MoodMinute(m.getValue("atEpochMillis").jsonPrimitive.long, m.getValue("speechRatio").jsonPrimitive.double)
        },
    )
}
