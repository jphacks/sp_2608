package com.theacar.bgm

import com.theacar.music.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class LiveViewApiTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUv"
    private val evaluation = LiveEvaluation(
        atMillis = 1_800_000_000_000,
        decided = true,
        evaluation = BgmEvaluation(
            windowStartMeters = 1_200.4, windowEndMeters = 4_800.0,
            shares = mapOf("sea" to .7, "urban" to .3, "Bad-Key" to .1),
            reasons = listOf("海が近い"),
            target = MusicTarget(.6, .7, .5, .4, .6, .5),
            themes = mapOf("sea" to .9),
            candidates = listOf(BgmCandidateScore("isrc:AAA", "海の曲", "A", -.123456, .4, .2, "sea", .8)),
        ),
        chosen = RecommendedTrack("isrc:AAA", "海の曲", "A", message = "海が近いので"),
    )
    private val snapshot = LiveSnapshot(
        atMillis = 1_800_000_000_000, arrived = false, destination = "江の島",
        car = LiveCar(35.3123456789, 139.48, 48.25, -10.0),
        routeKey = "1-0-abc", positionMeters = 1_200.6, totalMeters = 20_000.0,
        segments = listOf(LiveSegment(0.0, 5_000.0, "urban"), LiveSegment(5_000.0, 20_000.0, "sea")),
        cabin = LiveCabin(CabinState.LIVELY, .52, true),
        nowPlaying = LiveNowPlaying("海の曲", "A", 60_000, 200_000, true, LivePlaybackKind.BGM),
        evaluation = evaluation,
        played = listOf(LivePlayed("海の曲", "A", "海が近いので", "sea", "sea", 1_000.0, null)),
    )

    @Test fun sendsTheSnapshotInTheShapeTheWebPageReads() {
        val body = LiveViewApi("https://web.example", "token").payload(snapshot, listOf(139.4 to 35.3, 139.5 to 35.31))
        assertEquals("driving", body.getValue("status").jsonPrimitive.content)
        val car = body.getValue("car").jsonObject
        assertEquals(35.312346, car.getValue("latitude").jsonPrimitive.double)
        assertEquals(350.0, car.getValue("heading").jsonPrimitive.double)
        val route = body.getValue("route").jsonObject
        assertEquals(1201, route.getValue("positionM").jsonPrimitive.int)
        assertEquals(2, route.getValue("segments").jsonArray.size)
        assertEquals("lively", body.getValue("cabin").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("bgm", body.getValue("nowPlaying").jsonObject.getValue("kind").jsonPrimitive.content)
        val e = body.getValue("evaluation").jsonObject
        assertEquals("decided", e.getValue("kind").jsonPrimitive.content)
        // 受け口が弾くキー（英小文字と _ 以外）は送らない
        assertEquals(setOf("sea", "urban"), e.getValue("shares").jsonObject.keys)
        assertEquals(-.123, e.getValue("candidates").jsonArray.single().jsonObject.getValue("score").jsonPrimitive.double)
        assertEquals("海が近いので", e.getValue("chosen").jsonObject.getValue("message").jsonPrimitive.content)
        assertEquals(2, body.getValue("geometry").jsonArray.size)
        assertNull(LiveViewApi("https://web.example", "token").payload(snapshot, null)["geometry"])
    }

    @Test fun anEvaluationWithoutATargetIsLeftOut() {
        val noTarget = snapshot.copy(evaluation = evaluation.copy(evaluation = evaluation.evaluation.copy(target = null)))
        assertNull(LiveViewApi("https://web.example", "token").payload(noTarget, null)["evaluation"])
    }

    @Test fun putsToTheCodeWithTheUploadToken() = runTest {
        val client = HttpClient(MockEngine {
            assertEquals(HttpMethod.Put, it.method)
            assertEquals("/api/live/$code", it.url.encodedPath)
            assertEquals("Bearer token", it.headers[HttpHeaders.Authorization])
            respond("""{"code":"$code"}""")
        })
        val api = LiveViewApi("https://web.example/", "token", client)
        assertTrue(api.send(code, snapshot, null))
        assertEquals("https://web.example/live/$code", api.pageUrl(code))
        client.close()
    }

    @Test fun neverSendsTheTokenOverPlainHttpOrToABadCode() = runTest {
        val client = HttpClient(MockEngine { fail("送ってはいけない") })
        assertFalse(LiveViewApi("http://web.example", "token", client).send(code, snapshot, null))
        assertFalse(LiveViewApi("https://web.example", "token", client).send("short", snapshot, null))
        assertNull(LiveViewApi("", "token", client).pageUrl(code))
        client.close()
    }

    @Test fun thinsALongRouteBelowTheReceiverLimit() {
        val points = List(9_000) { i -> 139.0 + i * 1e-5 to 35.0 }
        val sent = LiveViewApi("https://web.example", "token").payload(snapshot, points).getValue("geometry").jsonArray
        assertEquals(4_000, sent.size)
        assertEquals(points.last().first, sent.last().jsonArray[0].jsonPrimitive.double, 1e-6)
    }
}
