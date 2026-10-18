package com.theacar.bgm

import com.theacar.music.BgmRecommendation
import com.theacar.music.BgmRecommendationRequest
import com.theacar.music.BgmRecommendationService
import com.theacar.music.MusicException
import com.theacar.music.MusicFailure
import com.theacar.music.MusicTarget
import com.theacar.music.RecommendedTrack
import com.theacar.music.SceneryRoute
import com.theacar.music.TrackLink
import com.theacar.music.TrackLinkStatus
import com.theacar.music.TrackLinks
import com.theacar.music.GuestRequest
import com.theacar.music.RequestBatch
import com.theacar.music.RequestInbox
import com.theacar.music.RequestParty
import com.theacar.music.RequestTiming
import com.theacar.music.SharedTrack
import com.theacar.music.SongRequest
import com.theacar.music.PlaylistAnnotator
import com.theacar.music.PlaylistTrack
import com.theacar.music.RecommendationMode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

fun bgmHttpClient() = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
}

/**
 * bgm-api への接続。経路 API と同じサーバーの /bgm/ を、同じトークンで呼ぶ。
 * 景観エンジンのルート ID が分かるときはそれと現在位置だけを送り、タイムラインはサーバー同士で受け渡す。
 * サーバー側で取れなかったと返ってきたときだけ、手元のタイムラインを付けて送り直す。
 * トークンは HTTPS か loopback にだけ送る。
 */
class BgmApiService(
    private val endpoint: String,
    private val client: HttpClient = bgmHttpClient(),
    private val accessToken: () -> String = { "" },
) : BgmRecommendationService, RequestInbox, PlaylistAnnotator {

    override suspend fun annotate(tracks: List<PlaylistTrack>): Set<String> {
        require(tracks.size in 1..50)
        val body = buildJsonObject {
            putJsonArray("tracks") { tracks.forEach { track -> addJsonObject {
                put("track_id", track.trackId); put("title", track.title); put("artist", track.artist)
                track.durationMillis?.let { put("duration_ms", it) }
            } } }
        }.toString()
        val root = parse(send("/annotate", body))
        return root["tracks"]?.jsonArray.orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            if (track["error"] != null || target(track) == null) null else track.text("track_id")
        }.toSet()
    }

    override suspend fun recommend(request: BgmRecommendationRequest): BgmRecommendation {
        val byId = request.scenery?.routeId != null
        var root = parse(send("/recommend", withContext(Dispatchers.Default) { payload(request).toString() }))
        // 景観エンジン側で検索結果が消えていて、サーバーがまだ保持していなかった場合。
        if (byId && (root["route_status"] as? JsonPrimitive)?.contentOrNull == "unavailable") {
            root = parse(send("/recommend", withContext(Dispatchers.Default) { payload(request, inlineTimeline = true).toString() }))
        }
        return withContext(Dispatchers.Default) { recommendation(root) }
    }

    override suspend fun link(trackIds: List<String>): TrackLinks {
        val ids = trackIds.distinct()
        require(ids.isNotEmpty() && ids.size <= BgmRecommendationService.MAX_LINKS)
        val body = buildJsonObject { putJsonArray("track_ids") { ids.forEach { add(it) } } }.toString()
        val text = send("/link/spotify", body)
        return withContext(Dispatchers.Default) { links(parse(text)) }
    }

    override suspend fun open(): RequestParty {
        val root = parse(send("/parties", "{}"))
        val code = root.text("code")?.takeIf { CODE.matches(it) } ?: throw MusicException(MusicFailure.NETWORK)
        return RequestParty(code, root.number("expires_at")?.toLong() ?: 0L)
    }

    override suspend fun sync(code: String, after: Long, nowPlaying: SharedTrack?, queue: List<SharedTrack>): RequestBatch {
        require(CODE.matches(code))
        val body = buildJsonObject {
            put("after", after)
            if (nowPlaying == null) put("now_playing", JsonNull) else putJsonObject("now_playing") { shared(nowPlaying) }
            putJsonArray("queue") { queue.forEach { addJsonObject { shared(it) } } }
        }.toString()
        val root = parse(send("/parties/$code/sync", body))
        return withContext(Dispatchers.Default) { requestBatch(root) }
    }

    override suspend fun close(code: String) {
        require(CODE.matches(code))
        send("/parties/$code/close", "{}")
    }

    private fun JsonObjectBuilder.shared(track: SharedTrack) {
        put("title", track.title)
        put("artist", track.artist)
        track.requester?.let { put("by", it) }
        put("kind", if (track.request) "request" else "bgm")
    }

    internal fun requestBatch(root: JsonObject) = RequestBatch(
        (root["open"] as? JsonPrimitive)?.booleanOrNull == true,
        root["requests"]?.jsonArray.orEmpty().mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val id = value.number("id")?.toLong() ?: return@mapNotNull null
            val uri = value.text("uri")?.takeIf { TRACK_URI.matches(it) } ?: return@mapNotNull null
            val timing = when (value.text("mode")) { "now" -> RequestTiming.NOW; "next" -> RequestTiming.NEXT; else -> return@mapNotNull null }
            GuestRequest(id, SongRequest("guest:$id", uri, value.text("title") ?: "", value.text("artist") ?: "",
                value.number("duration_ms")?.toInt()?.takeIf { it > 0 }, value.text("guest_name")), timing)
        },
    )

    private suspend fun send(path: String, body: String): String {
        if (endpoint.isBlank()) throw MusicException(MusicFailure.UNKNOWN)
        val token = accessToken().trim()
        val url = Url(endpoint)
        if (token.isNotEmpty() && (token.any { it.isWhitespace() || it.isISOControl() } ||
                (url.protocol.name != "https" && url.host !in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")))) {
            throw MusicException(MusicFailure.AUTHORIZATION)
        }
        val response: HttpResponse = try {
            client.post(endpoint.trimEnd('/') + path) {
                if (path == "/annotate") timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
                contentType(ContentType.Application.Json)
                if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(body)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw MusicException(MusicFailure.NETWORK)
        }
        when (response.status.value) {
            401, 403 -> throw MusicException(MusicFailure.AUTHORIZATION)
            429 -> throw MusicException(MusicFailure.RATE_LIMIT)
            in 500..599 -> throw MusicException(MusicFailure.NETWORK)
            !in 200..299 -> throw MusicException(MusicFailure.UNKNOWN)
        }
        val text = try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw MusicException(MusicFailure.NETWORK)
        }
        if (text.length > 4_000_000) throw MusicException(MusicFailure.NETWORK)
        return text
    }

    private fun parse(text: String) = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        throw MusicException(MusicFailure.NETWORK)
    }

    internal fun payload(request: BgmRecommendationRequest, inlineTimeline: Boolean = request.scenery?.routeId == null) = buildJsonObject {
        put("mode", request.mode.code)
        // 「プレイリスト内だけ」はカタログのインストによる補完も行わない。
        if (request.mode == RecommendationMode.PLAYLIST) put("include_catalog", false)
        put("cabin", request.cabin.code)
        put("top_k", request.topK)
        putJsonArray("members") {
            request.members.forEach { member -> addJsonObject { put("id", member.id); put("name", member.name) } }
        }
        putJsonArray("pool") {
            request.pool.forEach { entry ->
                addJsonObject {
                    put("track_id", entry.trackId)
                    putJsonArray("owners") { entry.owners.forEach { add(it) } }
                }
            }
        }
        putJsonArray("history") {
            request.history.forEach { played ->
                addJsonObject {
                    put("track_id", played.trackId)
                    played.ownerId?.let { put("owner_id", it) }
                    played.artist?.let { put("artist", it) }
                }
            }
        }
        if (request.recentTrackIds.isNotEmpty()) {
            putJsonArray("recent_track_ids") { request.recentTrackIds.forEach { add(it) } }
        }
        request.scenery?.let { putJsonObject("route") { route(it, inlineTimeline) } }
        // 区間ごとの運転条件は景観エンジンから取れないので、分かるぶんだけ scene で足す。
        // route の景観はサーバー側で保たれ、ここで指定した項目だけが上書きされる。
        val conditions = request.conditions
        if (request.scenery == null || conditions.highLoad || conditions.curvy) {
            putJsonObject("scene") {
                if (conditions.highLoad) put("load", "high")
                if (conditions.curvy) put("curvy", true)
            }
        }
        request.previousTarget?.let { axes -> putJsonObject("previous_target") { target(axes) } }
    }

    private fun JsonObjectBuilder.route(route: SceneryRoute, inlineTimeline: Boolean) {
        put("position_m", route.positionMeters)
        route.nextDurationMillis?.let { put("next_duration_ms", it) }
        if (!inlineTimeline) {
            put("route_id", requireNotNull(route.routeId))
            return
        }
        putJsonArray("timeline") {
            route.blocks.forEach { block ->
                addJsonObject {
                    put("start_m", block.startMeters)
                    put("end_m", block.endMeters)
                    put("category", block.category)
                    block.confidence?.let { put("confidence", it) }
                    block.openness?.let { put("openness", it) }
                    block.visibilityVerified?.let { put("visibility_verified", it) }
                    block.recommendedRank?.let { put("recommended_rank", it) }
                    block.arrivalTime?.let { put("arrival_time", it) }
                }
            }
        }
        if (route.revealStartMeters.isNotEmpty()) {
            putJsonArray("reveals") { route.revealStartMeters.forEach { addJsonObject { put("start_m", it) } } }
        }
    }

    private fun JsonObjectBuilder.target(target: MusicTarget) {
        put("energy", target.energy)
        put("valence", target.valence)
        put("tempo", target.tempo)
        put("acoustic", target.acoustic)
        put("spacious", target.spacious)
        put("vocal", target.vocal)
    }

    internal fun recommendation(root: JsonObject): BgmRecommendation {
        val window = root["route_window"] as? JsonObject
        val chosen = (root["chosen"] as? JsonObject)?.let(::track)
        return BgmRecommendation(
            chosen = chosen,
            candidates = root["candidates"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::track) },
            volume = root.number("volume")?.toFloat()?.coerceIn(0f, 1f) ?: .6f,
            target = (root["plan"] as? JsonObject)?.get("target")?.let { (it as? JsonObject)?.let(::target) },
            note = root.text("note"),
            missing = root["missing"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            coverage = window?.number("coverage")?.coerceIn(0.0, 1.0),
            revealEtaSeconds = (window?.get("reveal") as? JsonObject)?.number("eta_s")?.toInt()?.coerceAtLeast(0),
        )
    }

    internal fun links(root: JsonObject) = TrackLinks(
        root["links"]?.jsonArray.orEmpty().mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val id = value.text("track_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = when (value.text("status")) {
                "found" -> TrackLinkStatus.FOUND
                "not_found" -> TrackLinkStatus.NOT_FOUND
                "pending" -> TrackLinkStatus.PENDING
                else -> TrackLinkStatus.NO_ISRC
            }
            val uri = value.text("spotify_uri")?.takeIf { Regex("spotify:track:[A-Za-z0-9]{22}").matches(it) }
            TrackLink(id, if (status == TrackLinkStatus.FOUND && uri == null) TrackLinkStatus.NOT_FOUND else status, uri)
        },
        root.number("retry_after")?.toInt()?.coerceAtLeast(0),
    )

    private fun track(value: JsonObject): RecommendedTrack? {
        val id = value.text("track_id")?.takeIf { it.isNotBlank() } ?: return null
        return RecommendedTrack(id, value.text("title") ?: id, value.text("artist") ?: "",
            value.number("duration_ms")?.toInt()?.takeIf { it > 0 }, value.text("owner_id"), value.text("message"))
    }

    private fun target(value: JsonObject): MusicTarget? {
        val axes = listOf("energy", "valence", "tempo", "acoustic", "spacious", "vocal")
            .map { value.number(it)?.coerceIn(0.0, 1.0) ?: return null }
        return MusicTarget(axes[0], axes[1], axes[2], axes[3], axes[4], axes[5])
    }

    private companion object {
        val CODE = Regex("[A-Za-z0-9_-]{22}")
        val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]{22}")
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
}
