package com.theacar.android.music

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import com.theacar.music.MusicService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class MusicRequestState(
    val active: Boolean = false,
    val busy: Boolean = false,
    val inviteUrl: String = "",
    val message: String = "",
    val sent: Int = 0,
)

/** The relay only sees search results and requested tracks, never Spotify tokens. */
class MusicRequests internal constructor(
    private val service: MusicService,
    private val scope: CoroutineScope,
    private val ready: () -> Boolean,
    private val beforeStart: () -> Unit,
) {
    private val mutableState = MutableStateFlow(MusicRequestState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    fun start(baseUrl: String, device: String) {
        if (state.value.active || state.value.busy) return
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/")) {
            mutableState.value = MusicRequestState(message = "受付サーバーのHTTPS URLを入力してください（パスなし）。")
            return
        }
        if (!ready()) {
            mutableState.value = MusicRequestState(message = "Spotifyにログインし、再生先を選んでください。")
            return
        }
        val current = ++generation
        beforeStart()
        mutableState.value = MusicRequestState(busy = true, message = "受付を開始しています…")
        job = scope.launch {
            var localRoom = ""
            var localHost = ""
            val base = baseUrl.trim().trimEnd('/')
            try {
                val created = post(base, "rooms", JSONObject())
                localRoom = created.getString("room")
                localHost = created.getString("host")
                mutableState.value = MusicRequestState(active = true, inviteUrl = "$base/r/$localRoom", message = "QRから曲を追加できます。")
                // Cache completion before ACK: a failed ACK must never enqueue the same job twice.
                val completed = mutableMapOf<String, JSONObject>()
                var started = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 4 * 60 * 60 * 1000L
                var failures = 0
                while (isActive && ready() && android.os.SystemClock.elapsedRealtime() < deadline) {
                    try {
                        val jobs = post(base, "$localRoom/poll", JSONObject(), localHost).getJSONArray("jobs")
                        for (index in 0 until jobs.length()) {
                            val request = jobs.getJSONObject(index)
                            val id = request.getString("id")
                            val result = completed[id] ?: JSONObject().put("id", id).also { response ->
                                try {
                                    if (request.getString("kind") == "search") {
                                        val tracks = service.search(request.getString("query"))
                                        response.put("tracks", JSONArray().apply {
                                            tracks.forEach { put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist)) }
                                        })
                                    } else {
                                        val track = request.getJSONObject("track")
                                        val trackUri = track.getString("uri")
                                        require(Regex("spotify:track:[A-Za-z0-9]{22}").matches(trackUri))
                                        // The first request starts playback; subsequent requests append without interrupting it.
                                        if (!started) { service.play(trackUri, device); started = true }
                                        else service.enqueue(trackUri, device)
                                        mutableState.value = state.value.copy(sent = state.value.sent + 1, message = "Spotifyへ送信: ${track.getString("title")}")
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    response.put("error", "Spotify操作を確認できませんでした。ホストの接続・再生先を確認してください。追加は自動再送しません。")
                                    mutableState.value = state.value.copy(message = "Spotify操作に失敗しました。参加者の画面に通知しました。")
                                }
                                completed[id] = response
                            }
                            post(base, "$localRoom/complete", result, localHost)
                        }
                        // ACKed jobs disappear from poll; only unresolved ACKs need local retention.
                        val pending = (0 until jobs.length()).map { jobs.getJSONObject(it).getString("id") }.toSet()
                        completed.keys.retainAll(pending)
                        failures = 0
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failures++
                        mutableState.value = state.value.copy(message = "受付サーバーへの接続を確認中です。")
                        if (failures >= 6) throw error
                    }
                    delay(2_000)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current == generation) mutableState.value = state.value.copy(message = "受付を終了しました。サーバーURLと通信状態を確認してください。")
            } finally {
                if (localRoom.isNotEmpty()) withContext(NonCancellable) {
                    runCatching { post(base, "$localRoom/close", JSONObject(), localHost) }
                }
                if (current == generation) {
                    mutableState.value = state.value.copy(active = false, busy = false, inviteUrl = "")
                }
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel(); job = null
        mutableState.value = MusicRequestState(message = "受付を終了しました。Spotifyへ送信済みの曲は引き続き再生されます。")
    }

    private suspend fun post(base: String, path: String, data: JSONObject, token: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("$base/api/$path").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299)
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 128 * 1024)
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            JSONObject(body)
        } finally { connection.disconnect() }
    }
}

/** Shared by phone and DHU; no external QR service sees the invitation. */
fun musicRequestQr(url: String, size: Int = 512): Bitmap {
    val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
