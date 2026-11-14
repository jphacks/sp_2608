package com.theacar.bgm

import com.theacar.music.LiveEvaluation
import com.theacar.music.LiveSender
import com.theacar.music.LiveSnapshot
import com.theacar.music.MusicTarget
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

fun liveHttpClient() = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 8_000 }
}

/**
 * Web 版（旅の記録と同じ Worker）の PUT /api/live/{受付番号} へ、走行中の様子を送る。
 * トークンは旅の記録の送信と同じもの。HTTPS か loopback にだけ送る。
 */
class LiveViewApi(
    endpoint: String,
    private val token: String,
    private val client: HttpClient = liveHttpClient(),
) : LiveSender {
    private val base = endpoint.trim().trimEnd('/')
    val configured: Boolean get() = base.isNotEmpty() && safeEndpoint()

    /** 受付番号のページ。 */
    fun pageUrl(code: String): String? = base.takeIf { configured && CODE.matches(code) }?.let { "$it/live/$code" }

    override suspend fun send(code: String, snapshot: LiveSnapshot, geometry: List<Pair<Double, Double>>?): Boolean {
        if (!configured || !CODE.matches(code) || token.any { it.isWhitespace() || it.isISOControl() }) return false
        val body = withContext(Dispatchers.Default) { payload(snapshot, geometry).toString() }
        val response = client.put("$base/api/live/$code") {
            contentType(ContentType.Application.Json)
            if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }
        return response.status.value in 200..299
    }

    private fun safeEndpoint(): Boolean {
        val url = runCatching { Url(base) }.getOrNull() ?: return false
        if (url.host.isEmpty() || url.user != null || url.password != null) return false
        return url.protocol.name == "https" ||
            (url.protocol.name == "http" && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1"))
    }

    internal fun payload(s: LiveSnapshot, geometry: List<Pair<Double, Double>>?) = buildJsonObject {
        put("at", s.atMillis)
        put("status", if (s.arrived) "arrived" else "driving")
        s.destination?.let { put("destination", it.take(60)) }
        s.car?.let { car ->
            putJsonObject("car") {
                put("latitude", round6(car.latitude))
                put("longitude", round6(car.longitude))
                put("speedKmh", car.speedKmh.coerceIn(0.0, 400.0).let { (it * 10).roundToInt() / 10.0 })
                car.heading?.let { put("heading", ((it % 360 + 360) % 360).let { h -> (h * 10).roundToInt() / 10.0 }.coerceAtMost(360.0)) }
            }
        }
        putJsonObject("route") {
            put("key", s.routeKey.take(64))
            put("positionM", s.positionMeters.coerceAtLeast(0.0).roundToInt())
            put("totalM", s.totalMeters.coerceAtLeast(0.0).roundToInt())
            putJsonArray("segments") {
                s.segments.take(MAX_SEGMENTS).forEach { g ->
                    addJsonObject {
                        put("startM", g.startMeters.coerceAtLeast(0.0).roundToInt())
                        put("endM", g.endMeters.coerceAtLeast(0.0).roundToInt())
                        put("category", g.category.take(32))
                    }
                }
            }
        }
        s.cabin?.let { c ->
            putJsonObject("cabin") {
                put("state", c.state.code)
                put("speechRatio", round3(c.speechRatio.coerceIn(0.0, 1.0)))
                put("speaking", c.speaking)
            }
        }
        s.nowPlaying?.let { n ->
            putJsonObject("nowPlaying") {
                put("title", n.title.take(120))
                put("artist", n.artist.take(120))
                put("positionMs", n.positionMillis.coerceAtLeast(0))
                put("durationMs", n.durationMillis.coerceAtLeast(0))
                put("playing", n.playing)
                put("kind", n.kind.code)
            }
        }
        s.evaluation?.takeIf { it.evaluation.target != null }?.let { putJsonObject("evaluation") { evaluation(it) } }
        putJsonArray("played") {
            s.played.take(30).forEach { p ->
                addJsonObject {
                    put("title", p.title.take(120))
                    put("artist", p.artist.take(120))
                    p.message?.let { put("message", it.take(200)) }
                    p.theme?.let { put("theme", it) }
                    p.scene?.let { put("scene", it) }
                    p.startMeters?.let { put("startM", it.coerceAtLeast(0.0).roundToInt()) }
                    p.endMeters?.let { put("endM", it.coerceAtLeast(0.0).roundToInt()) }
                }
            }
        }
        geometry?.let { points ->
            putJsonArray("geometry") {
                thin(points).forEach { (lon, lat) -> addJsonArray { add(round6(lon)); add(round6(lat)) } }
            }
        }
    }

    private fun JsonObjectBuilder.evaluation(live: LiveEvaluation) {
        val e = live.evaluation
        put("at", live.atMillis)
        put("kind", if (live.decided) "decided" else "preview")
        val start = e.windowStartMeters
        val end = e.windowEndMeters
        if (start != null && end != null) putJsonObject("window") { put("startM", start.roundToInt()); put("endM", end.roundToInt()) }
        putJsonObject("shares") { e.shares.filterKeys(KEY::matches).entries.take(32).forEach { (k, v) -> put(k, round3(v)) } }
        putJsonArray("reasons") { e.reasons.take(10).forEach { add(it.take(60)) } }
        putJsonObject("target") { axes(requireNotNull(e.target)) }
        putJsonObject("themes") { e.themes.filterKeys(KEY::matches).entries.take(32).forEach { (k, v) -> put(k, round3(v)) } }
        put("slot", if (e.themeSlot) "theme" else "score")
        putJsonArray("candidates") {
            e.candidates.take(10).forEach { c ->
                addJsonObject {
                    put("title", c.title.take(120))
                    put("artist", c.artist.take(120))
                    put("score", c.score.coerceIn(-10.0, 10.0).let(::round3))
                    put("prob", round3(c.probability))
                    put("distance", c.distance.coerceIn(0.0, 10.0).let(::round3))
                    c.theme?.let { put("theme", it.take(32)) }
                    put("themeMatch", round3(c.themeMatch))
                    c.preference?.let { put("pref", it) }
                    put("sameArtist", c.sameArtist)
                    put("popularity", round3(c.popularity))
                    put("confPenalty", round3(c.confidencePenalty))
                    c.axes?.let { putJsonObject("axes") { axes(it) } }
                }
            }
        }
        live.chosen?.let { chosen ->
            putJsonObject("chosen") {
                put("title", chosen.title.take(120))
                put("artist", chosen.artist.take(120))
                chosen.message?.let { put("message", it.take(200)) }
            }
        }
    }

    private fun JsonObjectBuilder.axes(t: MusicTarget) {
        put("energy", round3(t.energy)); put("valence", round3(t.valence)); put("tempo", round3(t.tempo))
        put("acoustic", round3(t.acoustic)); put("spacious", round3(t.spacious)); put("vocal", round3(t.vocal))
    }

    private companion object {
        val CODE = Regex("[A-Za-z0-9_-]{22}")
        val KEY = Regex("[a-z_]{1,32}")
        const val MAX_SEGMENTS = 400
        /** 受け口の上限（5000点）より少なめに間引く。 */
        const val MAX_POINTS = 4_000

        fun thin(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            if (points.size <= MAX_POINTS) return points
            val step = (points.size - 1).toDouble() / (MAX_POINTS - 1)
            return List(MAX_POINTS) { i -> points[(i * step).roundToInt().coerceAtMost(points.lastIndex)] }
        }

        fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
        fun round6(v: Double) = (v * 1_000_000).roundToInt() / 1_000_000.0
    }
}
