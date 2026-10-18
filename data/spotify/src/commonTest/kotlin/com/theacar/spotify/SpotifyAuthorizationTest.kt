package com.theacar.spotify

import com.theacar.music.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal class MemoryTokens(var value: SpotifyTokens? = SpotifyTokens("access", "refresh", 100_000)) : SpotifyTokenStorage {
    override suspend fun read() = value
    override suspend fun write(tokens: SpotifyTokens?) { value = tokens }
}

class SpotifyAuthorizationTest {
    @Test fun authorizationUrlUsesPkceAndMinimumScopes() {
        val client = HttpClient(MockEngine { error("No request expected") })
        val auth = SpotifyAuthorization("client", "com.theacar.android.spotify://oauth/callback", client, MemoryTokens(), { 1_000 })
        val url = Url(auth.loginUrl("state-value", "challenge-value"))
        assertEquals("https", url.protocol.name)
        assertEquals("accounts.spotify.com", url.host)
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("state-value", url.parameters["state"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("user-read-playback-state user-modify-playback-state playlist-read-private playlist-read-collaborative", url.parameters["scope"])
        assertNull(url.parameters["client_secret"])
        client.close()
    }

    @Test fun exchangesCodeWithoutClientSecretAndPersistsTokens() = runTest {
        val store = MemoryTokens(null)
        val client = HttpClient(MockEngine {
            assertEquals("accounts.spotify.com", it.url.host)
            val form = parseQueryString((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("verifier", form["code_verifier"])
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("code", form["code"])
            assertNull(form["client_secret"])
            respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")
        })
        val auth = SpotifyAuthorization("client", "redirect", client, store, { 1_000 })
        auth.exchange("code", "verifier")
        assertEquals("new-access", store.value?.accessToken)
        assertEquals(3_601_000L, store.value?.expiresAtMillis)
        client.close()
    }

    @Test fun expiredAccessTokenRefreshesAndKeepsPreviousRefreshTokenWhenOmitted() = runTest {
        val store = MemoryTokens(SpotifyTokens("expired", "old-refresh", 100))
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"access_token":"new-access","expires_in":3600}""")
        })
        val auth = SpotifyAuthorization("client", "redirect", client, store, { 1_000 })
        assertEquals("new-access", auth.accessToken())
        assertEquals("new-access", auth.accessToken())
        assertEquals("old-refresh", store.value?.refreshToken)
        assertEquals(1, calls)
        client.close()
    }

    @Test fun revokedRefreshTokenClearsSavedCredentials() = runTest {
        val store = MemoryTokens(SpotifyTokens("expired", "revoked", 100))
        val client = HttpClient(MockEngine { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) })
        val auth = SpotifyAuthorization("client", "redirect", client, store, { 1_000 })
        assertEquals(MusicFailure.AUTHORIZATION, assertFailsWith<MusicException> { auth.accessToken() }.failure)
        assertNull(store.value)
        client.close()
    }

    @Test fun newAuthorizationRestoresTokensAndDisconnectRemovesThem() = runTest {
        val store = MemoryTokens()
        val client = HttpClient(MockEngine { error("No request expected") })
        val auth = SpotifyAuthorization("client", "redirect", client, store, { 1_000 })
        assertTrue(auth.restore())
        assertEquals("access", auth.accessToken())
        auth.disconnect()
        assertNull(store.value)
        assertFalse(SpotifyAuthorization("client", "redirect", client, store, { 1_000 }).restore())
        client.close()
    }
}
