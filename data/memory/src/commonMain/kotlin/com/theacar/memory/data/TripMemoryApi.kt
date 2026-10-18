package com.theacar.memory.data

import com.theacar.memory.TripMemory
import com.theacar.memory.TripMemorySender
import com.theacar.memory.toJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TripMemoryApi(
    endpoint: String,
    private val token: String,
    private val deviceId: String,
    private val client: HttpClient = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
    },
) : TripMemorySender {
    private val base = endpoint.trim().trimEnd('/')
    override val configured: Boolean get() = base.isNotEmpty()

    fun pageUrl(id: String): String? {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
        return base.takeIf { safeEndpoint() }?.let { "$it/t/$id" }
    }

    override suspend fun send(memory: TripMemory, photo: ByteArray?): Boolean {
        if (!configured || !safeEndpoint() || token.any { it.isWhitespace() || it.isISOControl() }) return false
        val response = client.post("$base/api/trips") {
            if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Device-Id", deviceId)
            setBody(multipartBody(memory.toJson(), photo))
        }
        return response.status.value in 200..299
    }

    private fun safeEndpoint(): Boolean {
        if (!base.startsWith("https://", ignoreCase = true) && !base.startsWith("http://", ignoreCase = true)) return false
        val url = runCatching { Url(base) }.getOrNull() ?: return false
        if (url.host.isEmpty() || url.user != null || url.password != null || url.fragment.isNotEmpty() || !url.parameters.isEmpty()) return false
        return url.protocol.name == "https" ||
            (url.protocol.name == "http" && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1"))
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun multipartBody(json: String, photo: ByteArray?): ByteArrayContent {
        val boundary = "tabireco-${Uuid.random()}"
        // パート名を引用符で囲み、Workers側のFormDataで解釈できる形式にする。
        val trip = "--$boundary\r\nContent-Disposition: form-data; name=\"trip\"\r\nContent-Type: application/json\r\n\r\n$json\r\n".encodeToByteArray()
        val image = photo?.let {
            "--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".encodeToByteArray() + it + "\r\n".encodeToByteArray()
        } ?: byteArrayOf()
        return ByteArrayContent(trip + image + "--$boundary--\r\n".encodeToByteArray(),
            ContentType.MultiPart.FormData.withParameter("boundary", boundary))
    }
}
