package com.theacar.bgm

import com.theacar.music.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class BgmApiServiceTest {
    private val members = listOf(MusicMember("a", "A"), MusicMember("b", "B"))
    @Test fun playlistModeExcludesCatalogAndPreservesOwnership() {
        val body = service().payload(BgmRecommendationRequest(members,
            listOf(PlaylistEntry("isrc:JPABC2300001", listOf("a", "b"))), RecommendationMode.PLAYLIST))
        assertFalse(body.getValue("include_catalog").jsonPrimitive.boolean)
        assertEquals("playlist", body.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf("a", "b"), body.getValue("pool").jsonArray.single().jsonObject.getValue("owners").jsonArray.map { it.jsonPrimitive.content })
        assertNull(service().payload(BgmRecommendationRequest(members))["include_catalog"])
    }

    @Test fun annotatesImportedTracksAndRejectsPartialFailures() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals("/annotate", it.url.encodedPath)
            assertEquals("Bearer token", it.headers[HttpHeaders.Authorization])
            respond("""{"tracks":[{"track_id":"isrc:JPABC2300001","energy":0.5,"valence":0.5,"tempo":0.5,"acoustic":0.5,"spacious":0.5,"vocal":0.5},{"track_id":"bad","error":"not annotated"}]}""")
        })
        assertEquals(setOf("isrc:JPABC2300001"), service(client).annotate(listOf(PlaylistTrack("isrc:JPABC2300001", "Song", "Artist", 180000))))
        client.close()
    }
    private val scenery = SceneryRoute(8_000.0, listOf(
        SceneryBlock(3_000.0, 9_000.0, "forest", .3, arrivalTime = "2026-10-17T15:04:00+09:00"),
        SceneryBlock(9_400.0, 20_000.0, "sea", .3, openness = .8, visibilityVerified = true,
            recommendedRank = "signature", arrivalTime = "2026-10-17T15:10:24+09:00"),
    ), listOf(9_400.0), 240_000)
    private val request = BgmRecommendationRequest(members, listOf(PlaylistEntry("isrc:AAA", listOf("a"))),
        RecommendationMode.BLEND, CabinState.LIVELY, scenery, DrivingConditions(),
        listOf(PlayedTrack("isrc:BBB", "b", "Artist")))

    private fun service(client: HttpClient = HttpClient(MockEngine { respond("") }), endpoint: String = "https://bgm.example") =
        BgmApiService(endpoint, client, { "token" })

    private val code = "A".repeat(22)
    private val uri = "spotify:track:" + "a".repeat(22)


    @Test fun opensAPartyAndSyncsTheQueueWithTheToken() = runTest {
        val bodies = mutableListOf<Pair<String, String>>()
        val client = HttpClient(MockEngine {
            assertEquals("Bearer token", it.headers[HttpHeaders.Authorization])
            bodies += it.url.encodedPath to (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            when (it.url.encodedPath) {
                "/bgm/parties" -> respond("""{"code":"$code","expires_at":1000}""")
                "/bgm/parties/$code/sync" -> respond("""{"open":true,"requests":[
                    {"id":7,"guest_name":"たろう","uri":"$uri","title":"夏","artist":"A","duration_ms":180000,"mode":"now"},
                    {"id":8,"guest_name":"はな","uri":"bad","title":"x","artist":"y","mode":"next"},
                    {"id":9,"guest_name":"はな","uri":"$uri","title":"秋","artist":"B","mode":"later"}]}""")
                else -> respond("{}")
            }
        })
        val service = service(client, "https://bgm.example/bgm")
        assertEquals(RequestParty(code, 1000), service.open())
        val batch = service.sync(code, 3, SharedTrack("海の曲", "A"), listOf(SharedTrack("次", "B", "たろう", request = true)))
        assertTrue(batch.open)
        // 曲の形が正しくないものと、知らないタイミングのものは捨てる
        assertEquals(listOf(GuestRequest(7, SongRequest("guest:7", uri, "夏", "A", 180_000, "たろう"), RequestTiming.NOW)), batch.requests)
        val sent = Json.parseToJsonElement(bodies[1].second).jsonObject
        assertEquals(3, sent.getValue("after").jsonPrimitive.int)
        assertEquals("bgm", sent.getValue("now_playing").jsonObject.getValue("kind").jsonPrimitive.content)
        val queued = sent.getValue("queue").jsonArray.single().jsonObject
        assertEquals("request", queued.getValue("kind").jsonPrimitive.content)
        assertEquals("たろう", queued.getValue("by").jsonPrimitive.content)
        service.close(code)
        assertEquals("/bgm/parties/$code/close", bodies.last().first)
    }

    @Test fun sendsTheTimelineAsRouteWithoutRoadNamesOrIds() {
        val route = service().payload(request).getValue("route").jsonObject
        assertEquals(8_000.0, route.getValue("position_m").jsonPrimitive.double)
        assertEquals(240_000, route.getValue("next_duration_ms").jsonPrimitive.int)
        val blocks = route.getValue("timeline").jsonArray.map { it.jsonObject }
        assertEquals(listOf("forest", "sea"), blocks.map { it.getValue("category").jsonPrimitive.content })
        val sea = blocks[1]
        assertEquals("signature", sea.getValue("recommended_rank").jsonPrimitive.content)
        assertEquals(.8, sea.getValue("openness").jsonPrimitive.double)
        assertTrue(sea.getValue("visibility_verified").jsonPrimitive.boolean)
        assertEquals("2026-10-17T15:10:24+09:00", sea.getValue("arrival_time").jsonPrimitive.content)
        assertTrue(blocks.none { "name" in it || "physical_position" in it })
        assertEquals(9_400.0, route.getValue("reveals").jsonArray.single().jsonObject.getValue("start_m").jsonPrimitive.double)
    }

    @Test fun sendsMembersPoolHistoryAndCabin() {
        val body = service().payload(request)
        assertEquals("blend", body.getValue("mode").jsonPrimitive.content)
        assertEquals("lively", body.getValue("cabin").jsonPrimitive.content)
        assertEquals(listOf("a", "b"), body.getValue("members").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals("isrc:AAA", body.getValue("pool").jsonArray.single().jsonObject.getValue("track_id").jsonPrimitive.content)
        val history = body.getValue("history").jsonArray.single().jsonObject
        assertEquals("isrc:BBB", history.getValue("track_id").jsonPrimitive.content)
        assertEquals("b", history.getValue("owner_id").jsonPrimitive.content)
    }

    @Test fun onlyDrivingConditionsKnownOnTheDeviceGoIntoScene() {
        assertFalse("scene" in service().payload(request))
        val body = service().payload(request.copy(conditions = DrivingConditions(highLoad = true, curvy = true)))
        val scene = body.getValue("scene").jsonObject
        assertEquals("high", scene.getValue("load").jsonPrimitive.content)
        assertTrue(scene.getValue("curvy").jsonPrimitive.boolean)
        // 景観はサーバー側が route から計算するので、ここでは送らない。
        assertFalse("features" in scene)
    }

    @Test fun withoutSceneryAnEmptySceneIsStillSentBecauseTheApiRequiresOne() {
        val body = service().payload(request.copy(scenery = null))
        assertFalse("route" in body)
        assertTrue("scene" in body)
    }

    @Test fun previousTargetIsSentAsSixAxes() {
        val target = MusicTarget(.5, .6, .4, .3, .8, .5)
        val sent = service().payload(request.copy(previousTarget = target)).getValue("previous_target").jsonObject
        assertEquals(.8, sent.getValue("spacious").jsonPrimitive.double)
        assertEquals(6, sent.size)
    }

    @Test fun parsesChosenCandidatesVolumeTargetAndReveal() {
        val root = Json.parseToJsonElement("""
            {"pool_size":10,"missing":["isrc:ZZZ"],"volume":0.45,
             "chosen":{"track_id":"isrc:AAA","title":"まつり","artist":"藤井 風","duration_ms":225000,"owner_id":null,"message":"海が近いので"},
             "candidates":[{"track_id":"isrc:CCC","title":"きらり","artist":"藤井 風"}],
             "plan":{"target":{"energy":0.59,"valence":0.72,"tempo":0.545,"acoustic":0.5,"spacious":0.835,"vocal":0.5}},
             "route_window":{"coverage":0.9,"reveal":{"start_m":9400,"eta_s":42}}}
        """).jsonObject
        val result = service().recommendation(root)
        val chosen = assertNotNull(result.chosen)
        assertEquals("isrc:AAA", chosen.trackId)
        assertEquals(225_000, chosen.durationMillis)
        assertNull(chosen.ownerId)
        assertEquals("海が近いので", chosen.message)
        assertEquals("isrc:CCC", result.candidates.single().trackId)
        assertEquals(.45f, result.volume)
        assertEquals(.835, assertNotNull(result.target).spacious)
        assertEquals(.9, result.coverage)
        assertEquals(42, result.revealEtaSeconds)
        assertEquals(listOf("isrc:ZZZ"), result.missing)
    }

    @Test fun missingChosenAndNoteAreKept() {
        val root = Json.parseToJsonElement("""{"chosen":null,"note":"no tracks left in pool","volume":0.6}""").jsonObject
        val result = service().recommendation(root)
        assertNull(result.chosen)
        assertEquals("no tracks left in pool", result.note)
    }

    @Test fun linkStatusesAreMappedAndFoundWithoutAUsableUriBecomesNotFound() {
        val root = Json.parseToJsonElement("""
            {"links":[{"track_id":"isrc:AAA","status":"found","spotify_uri":"spotify:track:3L7ISJTvKx56uhsF28aJ4p"},
                      {"track_id":"isrc:BBB","status":"not_found"},
                      {"track_id":"isrc:CCC","status":"pending"},
                      {"track_id":"isrc:DDD","status":"found","spotify_uri":"not-a-uri"}],
             "retry_after":30}
        """).jsonObject
        val links = service().links(root)
        assertEquals(TrackLinkStatus.FOUND, links.links[0].status)
        assertEquals("spotify:track:3L7ISJTvKx56uhsF28aJ4p", links.links[0].uri)
        assertEquals(TrackLinkStatus.NOT_FOUND, links.links[1].status)
        assertEquals(TrackLinkStatus.PENDING, links.links[2].status)
        assertEquals(TrackLinkStatus.NOT_FOUND, links.links[3].status)
        assertEquals(30, links.retryAfterSeconds)
    }

    @Test fun statusCodesBecomeMusicFailures() = runTest {
        suspend fun failureFor(code: HttpStatusCode): MusicFailure {
            val client = HttpClient(MockEngine { respond("", code) })
            return assertFailsWith<MusicException> { service(client).recommend(request) }.failure
        }
        assertEquals(MusicFailure.AUTHORIZATION, failureFor(HttpStatusCode.Unauthorized))
        assertEquals(MusicFailure.RATE_LIMIT, failureFor(HttpStatusCode.TooManyRequests))
        assertEquals(MusicFailure.NETWORK, failureFor(HttpStatusCode.BadGateway))
        assertEquals(MusicFailure.UNKNOWN, failureFor(HttpStatusCode.BadRequest))
    }

    @Test fun theSharedTokenIsOnlySentOverHttpsOrLoopback() = runTest {
        val client = HttpClient(MockEngine { respond("""{"chosen":null}""") })
        assertEquals(MusicFailure.AUTHORIZATION,
            assertFailsWith<MusicException> { service(client, "http://bgm.example").recommend(request) }.failure)
        assertNotNull(service(client, "http://10.0.2.2:8787").recommend(request))
    }

    @Test fun theBearerTokenAndJsonBodyReachTheEndpoint() = runTest {
        var authorization: String? = null
        var path: String? = null
        val client = HttpClient(MockEngine { call ->
            authorization = call.headers[HttpHeaders.Authorization]
            path = call.url.encodedPath
            respond("""{"chosen":null}""")
        })
        service(client).recommend(request)
        assertEquals("Bearer token", authorization)
        assertEquals("/recommend", path)
    }

    @Test fun linkRequestsAreLimitedToWhatTheApiAccepts() = runTest {
        val client = HttpClient(MockEngine { respond("""{"links":[]}""") })
        assertFailsWith<IllegalArgumentException> { service(client).link(emptyList()) }
        assertFailsWith<IllegalArgumentException> { service(client).link(List(6) { "isrc:$it" }) }
    }

    private val routeId = "route_" + "c".repeat(32)
    private val byId = request.copy(scenery = scenery.copy(routeId = routeId))

    @Test fun withAScenicRouteIdOnlyTheIdAndPositionAreSent() {
        val route = service().payload(byId).getValue("route").jsonObject
        assertEquals(routeId, route.getValue("route_id").jsonPrimitive.content)
        assertEquals(8_000.0, route.getValue("position_m").jsonPrimitive.double)
        assertEquals(240_000, route.getValue("next_duration_ms").jsonPrimitive.int)
        assertFalse("timeline" in route)
        assertFalse("reveals" in route)
    }

    @Test fun whenTheServerCannotResolveTheRouteTheTimelineIsSentOnce() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val client = HttpClient(MockEngine { call ->
            bodies += Json.parseToJsonElement((call.body as io.ktor.http.content.TextContent).text).jsonObject
            respond(if (bodies.size == 1) """{"chosen":null,"route_status":"unavailable"}"""
                else """{"chosen":{"track_id":"isrc:X","title":"T","artist":"A"},"route_status":"given"}""")
        })
        val result = service(client).recommend(byId)
        assertEquals("isrc:X", result.chosen?.trackId)
        assertEquals(2, bodies.size)
        assertFalse("timeline" in bodies[0].getValue("route").jsonObject)
        assertEquals(2, bodies[1].getValue("route").jsonObject.getValue("timeline").jsonArray.size)
    }

    @Test fun aResolvedRouteIsNotSentAgain() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("""{"chosen":null,"route_status":"resolved"}""") })
        service(client).recommend(byId)
        assertEquals(1, calls)
    }
}
