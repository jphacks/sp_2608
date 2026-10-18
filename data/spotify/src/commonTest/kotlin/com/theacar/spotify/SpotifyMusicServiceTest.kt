package com.theacar.spotify

import com.theacar.music.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SpotifyMusicServiceTest {
    private val id = "0123456789012345678901"
    @Test fun importsAllPagesUsingOnlySpotifyHostAndSkipsUnsupportedItems() = runTest {
        val offsets = mutableListOf<String?>()
        val client = HttpClient(MockEngine {
            assertEquals("api.spotify.com", it.url.host)
            assertEquals("/v1/playlists/$id/items", it.url.encodedPath)
            offsets += it.url.parameters["offset"]
            if (offsets.size == 1) respond("""{"items":[{"item":{"id":"$id","name":"Song","artists":[{"name":"Artist"}],"external_ids":{"isrc":"JPABC2300001"}}},{"item":null}],"next":"https://evil.example/next"}""")
            else respond("""{"items":[{"item":{"id":"$id","name":"Song","artists":[{"name":"Artist"}],"external_ids":{"isrc":"JPABC2300001"}}}],"next":null}""")
        })
        val loaded = service(client).playlist(id)
        assertEquals(listOf<String?>("0", "2"), offsets)
        assertEquals("isrc:JPABC2300001", loaded.tracks.single().trackId)
        assertEquals(1, loaded.skipped)
        client.close()
    }
    private fun service(client: HttpClient, store: MemoryTokens = MemoryTokens(), now: () -> Long = { 1_000 }) =
        SpotifyMusicService(client, SpotifyAuthorization("client", "redirect", client, store, now), now)

    @Test fun skipsEpisodesLocalTracksAndDuplicateIsrcs() = runTest {
        val offsets = mutableListOf<String?>()
        val client = HttpClient(MockEngine {
            assertEquals("/v1/playlists/$id/items", it.url.encodedPath)
            assertEquals("Bearer access", it.headers[HttpHeaders.Authorization])
            offsets += it.url.parameters["offset"]
            if (offsets.size == 1) respond("""{"next":"https://untrusted.example/page","items":[
                {"item":{"type":"track","id":"$id","name":"Song","artists":[{"name":"A"}],"external_ids":{"isrc":"JPABC1200001"}}},
                {"item":null},{"item":{"type":"episode","id":"$id","name":"Podcast"}}]}""")
            else respond("""{"next":null,"items":[
                {"track":{"id":"$id","name":"Song","artists":[{"name":"A"}],"external_ids":{"isrc":"JPABC1200001"}}},
                {"track":{"id":"$id","name":"local","is_local":true}}]}""")
        })
        val loaded = service(client).playlist(id)
        assertEquals(listOf<String?>("0", "3"), offsets)
        assertEquals("isrc:JPABC1200001", loaded.tracks.single().trackId)
        assertEquals(3, loaded.skipped)
        client.close()
    }

    @Test fun rejectsMalformedPlaylistIdsBeforeMakingRequests() = runTest {
        val client = HttpClient(MockEngine { error("No request expected") })
        for (source in listOf("https://open.spotify.com/playlist/$id", "bad", "../me/tracks")) {
            assertFailsWith<IllegalArgumentException> { service(client).playlist(source) }
        }
        client.close()
    }

    @Test fun searchesTracksWithDevelopmentModeLimitAndUserToken() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("api.spotify.com", it.url.host)
            assertEquals("Bearer access", it.headers[HttpHeaders.Authorization])
            assertEquals("雨の日", it.url.parameters["q"])
            assertEquals("10", it.url.parameters["limit"])
            respond("""{"tracks":{"items":[{"id":"$id","name":"雨の道","duration_ms":183000,"artists":[{"name":"Artist"}]}]}}""")
        })
        val track = service(client).search("雨の日").single()
        assertEquals("雨の道", track.title)
        assertEquals(183_000, track.durationMillis)
        assertEquals("spotify:track:$id", track.uri)
        assertEquals("https://open.spotify.com/track/$id", track.url)
        client.close()
    }

    @Test fun excludesUnplayableTracksAndRestrictedDevices() = runTest {
        val client = HttpClient(MockEngine {
            if (it.url.encodedPath.endsWith("devices")) respond("""{"devices":[{"id":null},{"id":"restricted","is_restricted":true},{"id":"phone","name":"Phone","is_active":true}]}""")
            else respond("""{"tracks":{"items":[{"id":"$id","name":"restricted","is_playable":false}]}}""")
        })
        val service = service(client)
        assertTrue(service.search("test").isEmpty())
        assertEquals("phone", service.devices().single().id)
        client.close()
    }

    @Test fun sendsSelectedTrackAndDeviceAndTreats204AsSuccess() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals(HttpMethod.Put, it.method)
            assertEquals("/v1/me/player/play", it.url.encodedPath)
            assertEquals("phone", it.url.parameters["device_id"])
            val body = (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertTrue(body.contains("spotify:track:$id"))
            respond("", HttpStatusCode.NoContent)
        })
        service(client).play("spotify:track:$id", "phone")
        client.close()
    }

    @Test fun queuesTrackOnSelectedDeviceWithoutStartingPlayback() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals(HttpMethod.Post, it.method)
            assertEquals("/v1/me/player/queue", it.url.encodedPath)
            assertEquals("phone", it.url.parameters["device_id"])
            assertEquals("spotify:track:$id", it.url.parameters["uri"])
            respond("", HttpStatusCode.NoContent)
        })
        service(client).enqueue("spotify:track:$id", "phone")
        client.close()
    }

    @Test fun losingTheConnectionIsReportedAsANetworkFailure() = runTest {
        val client = HttpClient(MockEngine { throw Exception("Unable to resolve host \"api.spotify.com\"") })
        val error = assertFailsWith<MusicException> { service(client).playback() }
        assertEquals(MusicFailure.NETWORK, error.failure)
        client.close()
    }

    @Test fun sendsTheEntireMixInOrderToTheSelectedDevice() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("phone", it.url.parameters["device_id"])
            val body = (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals("{\"uris\":[\"spotify:track:$id\",\"spotify:track:1123456789012345678901\"]}", body)
            respond("", HttpStatusCode.NoContent)
        })
        service(client).playMix(listOf(MusicTrack("spotify:track:$id", "A", "Artist", ""),
            MusicTrack("spotify:track:1123456789012345678901", "B", "Artist", "")), "phone")
        client.close()
    }

    @Test fun readsShuffleStateAndTreatsInvalidDurationAsUnknown() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"shuffle_state":true,"is_playing":true,"device":{"id":"phone"},"item":{"id":"$id","name":"Song","duration_ms":0,"artists":[]}}""")
        })
        val playback = service(client).playback()
        assertEquals(true, playback.shuffleEnabled)
        assertNull(playback.track?.durationMillis)
        client.close()
    }

    @Test fun emptyPlaybackIsNotReportedAsPlaying() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })
        assertEquals(MusicPlayback(), service(client).playback())
        client.close()
    }

    @Test fun unauthorizedRequestRefreshesOnceAndRetries() = runTest {
        var apiCalls = 0
        var tokenCalls = 0
        val client = HttpClient(MockEngine {
            if (it.url.host == "accounts.spotify.com") {
                tokenCalls++
                respond("""{"access_token":"replacement","refresh_token":"rotated","expires_in":3600}""")
            } else {
                apiCalls++
                if (apiCalls == 1) respond("{}", HttpStatusCode.Unauthorized)
                else {
                    assertEquals("Bearer replacement", it.headers[HttpHeaders.Authorization])
                    respond("""{"devices":[]}""")
                }
            }
        })
        service(client).devices()
        assertEquals(2, apiCalls)
        assertEquals(1, tokenCalls)
        client.close()
    }

    @Test fun repeatedUnauthorizedResponseClearsCredentialsAndStopsRetrying() = runTest {
        val store = MemoryTokens()
        var apiCalls = 0
        val client = HttpClient(MockEngine {
            if (it.url.host == "accounts.spotify.com") respond("""{"access_token":"replacement","expires_in":3600}""")
            else { apiCalls++; respond("{}", HttpStatusCode.Unauthorized) }
        })
        assertEquals(MusicFailure.AUTHORIZATION, assertFailsWith<MusicException> { service(client, store).devices() }.failure)
        assertNull(store.value)
        assertEquals(2, apiCalls)
        client.close()
    }

    @Test fun respectsRetryAfterWithoutRepeatedNetworkRequests() = runTest {
        var time = 1_000L
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30"))
        })
        val service = service(client, now = { time })
        repeat(2) { assertEquals(MusicFailure.RATE_LIMIT, assertFailsWith<MusicException> { service.devices() }.failure) }
        assertEquals(1, calls)
        time += 30_000
        assertFailsWith<MusicException> { service.devices() }
        assertEquals(2, calls)
        client.close()
    }

    @Test fun mapsAccessDeniedAndMissingDevice() = runTest {
        for ((status, expected) in listOf(HttpStatusCode.Forbidden to MusicFailure.ACCESS_DENIED, HttpStatusCode.NotFound to MusicFailure.NO_DEVICE)) {
            val client = HttpClient(MockEngine { respond("{}", status) })
            assertEquals(expected, assertFailsWith<MusicException> { service(client).devices() }.failure)
            client.close()
        }
    }
}
