package com.theacar.bgm

import com.theacar.music.MusicLogSender
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType

/**
 * 旅の音楽の記録を Web 版の /api/music-logs へ送る。
 * トークンは経路 API のものと混ぜず、HTTPS か loopback にだけ送る。
 */
class MusicLogApi(
    endpoint: String,
    private val token: String,
    private val client: HttpClient = bgmHttpClient(),
) : MusicLogSender {
    private val endpoint = endpoint.trim()
    override val configured = this.endpoint.isNotEmpty()

    override suspend fun send(json: String): Boolean {
        check(configured)
        val url = Url(endpoint)
        val token = token.trim()
        check(token.isEmpty() || (token.none { it.isWhitespace() || it.isISOControl() } &&
            (url.protocol.name == "https" || url.host in LOOPBACK))) { "トークンを安全でない宛先へ送れません" }
        val response = client.post(endpoint.trimEnd('/') + "/api/music-logs") {
            contentType(ContentType.Application.Json)
            if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(json)
        }
        return response.status.value in 200..299
    }

    private companion object { val LOOPBACK = setOf("127.0.0.1", "localhost", "10.0.2.2", "::1") }
}
