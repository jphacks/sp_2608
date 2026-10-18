package com.theacar.android.music

import android.content.Context
import com.theacar.android.security.EncryptedSecretFile
import com.theacar.music.MusicException
import com.theacar.music.MusicFailure
import com.theacar.spotify.SpotifyTokenStorage
import com.theacar.spotify.SpotifyTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class SpotifyCredentialStore(context: Context, private val clientId: () -> String) : SpotifyTokenStorage {
    private val tokens = EncryptedSecretFile(context, "spotify-tokens.enc", "com.theacar.spotify-tokens.v1")
    private val login = EncryptedSecretFile(context, "spotify-login.enc", "com.theacar.spotify-login.v1")

    override suspend fun read(): SpotifyTokens? = disk {
        val raw = tokens.read().takeIf { it.isNotEmpty() } ?: return@disk null
        val value = Json.parseToJsonElement(raw).jsonObject
        if (value.getValue("client_id").jsonPrimitive.content != clientId()) return@disk null
        SpotifyTokens(value.getValue("access_token").jsonPrimitive.content,
            value.getValue("refresh_token").jsonPrimitive.content, value.getValue("expires_at").jsonPrimitive.long)
    }

    override suspend fun write(tokens: SpotifyTokens?) = disk {
        this.tokens.write(tokens?.let {
            buildJsonObject {
                put("client_id", clientId())
                put("access_token", it.accessToken)
                put("refresh_token", it.refreshToken)
                put("expires_at", it.expiresAtMillis)
            }.toString()
        }.orEmpty())
    }

    suspend fun saveLogin(request: SpotifyLogin?) = disk {
        login.write(request?.let {
            buildJsonObject {
                put("client_id", clientId()); put("state", it.state); put("verifier", it.verifier); put("created_at", it.createdAtMillis)
            }.toString()
        }.orEmpty())
    }

    suspend fun readLogin(): SpotifyLogin? = disk {
        val raw = login.read()
        if (raw.isEmpty()) return@disk null
        val value = Json.parseToJsonElement(raw).jsonObject
        if (value.getValue("client_id").jsonPrimitive.content != clientId()) return@disk null
        SpotifyLogin(value.getValue("state").jsonPrimitive.content, value.getValue("verifier").jsonPrimitive.content,
            value.getValue("created_at").jsonPrimitive.long)
    }

    private suspend fun <T> disk(action: () -> T): T = try { withContext(Dispatchers.IO) { action() } }
    catch (error: Exception) {
        if (error is CancellationException) throw error
        throw MusicException(MusicFailure.STORAGE)
    }
}
