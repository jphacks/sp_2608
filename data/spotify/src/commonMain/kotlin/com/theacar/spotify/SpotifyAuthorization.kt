package com.theacar.spotify

import com.theacar.music.MusicException
import com.theacar.music.MusicFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class SpotifyTokens(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long) {
    override fun toString() = "SpotifyTokens(redacted)"
}

interface SpotifyTokenStorage {
    suspend fun read(): SpotifyTokens?
    suspend fun write(tokens: SpotifyTokens?)
}

class SpotifyAuthorization(
    private val clientId: () -> String,
    private val redirectUri: String,
    private val client: HttpClient,
    private val storage: SpotifyTokenStorage,
    private val nowMillis: () -> Long,
) {
    /** アプリIDが起動中に変わらない構成向け。 */
    constructor(clientId: String, redirectUri: String, client: HttpClient,
        storage: SpotifyTokenStorage, nowMillis: () -> Long) : this({ clientId }, redirectUri, client, storage, nowMillis)

    private val mutex = Mutex()
    private var tokens: SpotifyTokens? = null
    private var loaded = false

    fun loginUrl(state: String, challenge: String): String = URLBuilder("https://accounts.spotify.com/authorize").apply {
        parameters.append("client_id", clientId())
        parameters.append("response_type", "code")
        parameters.append("redirect_uri", redirectUri)
        parameters.append("code_challenge_method", "S256")
        parameters.append("code_challenge", challenge)
        parameters.append("state", state)
        parameters.append("scope", "user-read-playback-state user-modify-playback-state playlist-read-private playlist-read-collaborative")
    }.buildString()

    suspend fun restore(): Boolean = mutex.withLock {
        load()
        tokens != null
    }

    suspend fun exchange(code: String, verifier: String) = mutex.withLock {
        val response = client.submitForm("https://accounts.spotify.com/api/token", parameters {
            append("client_id", clientId())
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
            append("code_verifier", verifier)
        })
        if (response.status.value !in 200..299) throw MusicException(MusicFailure.AUTHORIZATION)
        save(parse(response.bodyAsText(), null))
    }

    suspend fun accessToken(rejected: String? = null): String = mutex.withLock {
        load()
        val previous = tokens ?: throw MusicException(MusicFailure.AUTHORIZATION)
        if (previous.expiresAtMillis - nowMillis() > 60_000 && previous.accessToken != rejected) return@withLock previous.accessToken
        val response = client.submitForm("https://accounts.spotify.com/api/token", parameters {
            append("client_id", clientId())
            append("grant_type", "refresh_token")
            append("refresh_token", previous.refreshToken)
        })
        if (response.status.value in setOf(400, 401)) {
            save(null)
            throw MusicException(MusicFailure.AUTHORIZATION)
        }
        if (response.status.value !in 200..299) throw MusicException(MusicFailure.NETWORK)
        val refreshed = parse(response.bodyAsText(), previous.refreshToken)
        save(refreshed)
        refreshed.accessToken
    }

    suspend fun disconnect() = mutex.withLock { save(null) }

    private suspend fun load() {
        if (!loaded) { tokens = storage.read(); loaded = true }
    }

    private suspend fun save(value: SpotifyTokens?) {
        storage.write(value)
        tokens = value
        loaded = true
    }

    private fun parse(body: String, previousRefresh: String?): SpotifyTokens {
        val value = Json.parseToJsonElement(body).jsonObject
        val access = value["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw MusicException(MusicFailure.AUTHORIZATION)
        val refresh = value["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: previousRefresh
            ?: throw MusicException(MusicFailure.AUTHORIZATION)
        val lifetime = value["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..86_400 }
            ?: throw MusicException(MusicFailure.AUTHORIZATION)
        return SpotifyTokens(access, refresh, nowMillis() + lifetime * 1_000)
    }
}
