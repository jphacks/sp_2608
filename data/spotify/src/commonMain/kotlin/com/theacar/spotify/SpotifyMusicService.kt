package com.theacar.spotify

import com.theacar.music.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

fun spotifyHttpClient() = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
}

class SpotifyMusicService(
    private val client: HttpClient,
    private val authorization: SpotifyAuthorization,
    private val nowMillis: () -> Long,
) : MusicService, PlaylistSource {
    private var retryAtMillis = 0L

    override suspend fun playlist(id: String): PlaylistTracks {
        require(Regex("[A-Za-z0-9]{22}").matches(id))
        val tracks = mutableListOf<PlaylistTrack>()
        var skipped = 0
        var offset = 0
        // 固定のSpotifyホストへだけ要求し、応答のnext URLへ資格情報を転送しない。
        while (true) {
            val page = request("playlists/$id/items") { parameter("limit", 50); parameter("offset", offset) }.obj()
            val items = page["items"] as? JsonArray ?: throw MusicException(MusicFailure.NETWORK)
            if (offset + items.size > 1000) throw PlaylistTooLargeException()
            for (element in items) {
                val wrapper = element as? JsonObject
                val value = (wrapper?.get("item") ?: wrapper?.get("track")) as? JsonObject
                val song = value?.let(::track)
                val isrc = (value?.get("external_ids") as? JsonObject)?.string("isrc")?.uppercase()
                    ?.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}").matches(it) }
                if (song == null || isrc == null || value?.string("type") == "episode") { skipped++; continue }
                tracks += PlaylistTrack("isrc:$isrc", song.title, song.artist, song.durationMillis)
            }
            if (page["next"] == null || page["next"] == JsonNull) break
            if (offset + items.size >= 1000) throw PlaylistTooLargeException()
            if (items.isEmpty()) throw MusicException(MusicFailure.NETWORK)
            offset += items.size
        }
        return PlaylistTracks(tracks.distinctBy { it.trackId }, skipped)
    }

    override suspend fun search(query: String): List<MusicTrack> {
        if (query.isBlank()) return emptyList()
        val response = request("search") { parameter("q", query.trim()); parameter("type", "track"); parameter("limit", 10) }
        return response.obj()["tracks"]?.jsonObject?.get("items")?.jsonArray.orEmpty().mapNotNull { track(it.jsonObject) }
    }

    override suspend fun devices(): List<MusicDevice> = request("me/player/devices").obj()["devices"]?.jsonArray.orEmpty().mapNotNull {
        val device = it.jsonObject
        val id = device.string("id") ?: return@mapNotNull null
        if (device.boolean("is_restricted")) return@mapNotNull null
        MusicDevice(id, device.string("name") ?: "Spotify デバイス", device.boolean("is_active"))
    }

    override suspend fun playback(): MusicPlayback {
        val response = request("me/player")
        if (response.status == HttpStatusCode.NoContent) return MusicPlayback()
        val value = response.obj()
        return MusicPlayback((value["item"] as? JsonObject)?.let(::track), value.boolean("is_playing"),
            (value["device"] as? JsonObject)?.string("id"),
            (value["shuffle_state"] as? JsonPrimitive)?.booleanOrNull,
            (value["progress_ms"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 })
    }

    override suspend fun play(trackUri: String?, deviceId: String) {
        require(trackUri == null || Regex("spotify:track:[A-Za-z0-9]{22}").matches(trackUri))
        request("me/player/play", HttpMethod.Put) {
            parameter("device_id", deviceId)
            if (trackUri != null) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("uris") { add(trackUri) } }.toString())
            }
        }
    }
    override suspend fun enqueue(trackUri: String, deviceId: String) {
        require(Regex("spotify:track:[A-Za-z0-9]{22}").matches(trackUri))
        request("me/player/queue", HttpMethod.Post) {
            parameter("uri", trackUri)
            parameter("device_id", deviceId)
        }
    }
    override suspend fun pause(deviceId: String) { request("me/player/pause", HttpMethod.Put) { parameter("device_id", deviceId) } }
    override suspend fun playMix(tracks: List<MusicTrack>, deviceId: String) {
        require(tracks.size in 1..10 && tracks.all { Regex("spotify:track:[A-Za-z0-9]{22}").matches(it.uri) })
        request("me/player/play", HttpMethod.Put) {
            parameter("device_id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { putJsonArray("uris") { tracks.forEach { add(it.uri) } } }.toString())
        }
    }
    override suspend fun next(deviceId: String) { request("me/player/next", HttpMethod.Post) { parameter("device_id", deviceId) } }
    override suspend fun previous(deviceId: String) { request("me/player/previous", HttpMethod.Post) { parameter("device_id", deviceId) } }

    private suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        if (nowMillis() < retryAtMillis) throw MusicException(MusicFailure.RATE_LIMIT)
        suspend fun send(token: String) = client.request("https://api.spotify.com/v1/$path") {
            this.method = method
            bearerAuth(token)
            configure()
        }
        // 圏外などで名前解決や接続に失敗したときも、通信の失敗として呼び出し元へ返す（そのまま投げるとアプリが落ちる）。
        val response = try {
            val token = authorization.accessToken()
            val first = send(token)
            if (first.status == HttpStatusCode.Unauthorized) send(authorization.accessToken(rejected = token)) else first
        } catch (error: CancellationException) {
            throw error
        } catch (error: MusicException) {
            throw error
        } catch (_: Exception) {
            throw MusicException(MusicFailure.NETWORK)
        }
        when (response.status.value) {
            in 200..299 -> return response
            401 -> {
                authorization.disconnect()
                throw MusicException(MusicFailure.AUTHORIZATION)
            }
            403 -> throw MusicException(MusicFailure.ACCESS_DENIED)
            404 -> throw MusicException(MusicFailure.NO_DEVICE)
            429 -> {
                val seconds = response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1, 86_400) ?: 30
                retryAtMillis = nowMillis() + seconds * 1_000
                throw MusicException(MusicFailure.RATE_LIMIT)
            }
            else -> throw MusicException(MusicFailure.NETWORK)
        }
    }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    private fun track(value: JsonObject): MusicTrack? {
        val id = value.string("id")?.takeIf { Regex("[A-Za-z0-9]{22}").matches(it) } ?: return null
        if (value["is_playable"] == JsonPrimitive(false) || value["restrictions"] is JsonObject || value.boolean("is_local")) return null
        val artists = value["artists"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.string("name") }.joinToString(" / ")
        return MusicTrack("spotify:track:$id", value.string("name") ?: return null, artists, "https://open.spotify.com/track/$id",
            (value["duration_ms"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 })
    }
}
