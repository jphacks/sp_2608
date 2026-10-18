package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import kotlin.test.*

class ScenicRecommendationClientTest {
    @Test fun sendsCustomWeightsAndExperienceParametersWithoutPresetLeakage() {
        val target = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(
            customWeights = mapOf("lake" to .8, "river" to .4, "sea" to 0.0),
            variety = .75, passengerView = .6, certifiedRoad = .9)))
        val preferences = adapter().request(target, "2026-10-17T12:00:00+09:00").getValue("preferences").jsonObject
        assertEquals(setOf("lake", "river", "variety", "passenger_view", "certified_road"), preferences.keys)
        assertEquals(.8, preferences.getValue("lake").jsonPrimitive.double)
        assertEquals(.4, preferences.getValue("river").jsonPrimitive.double)
        assertEquals(.75, preferences.getValue("variety").jsonPrimitive.double)
        assertEquals(.6, preferences.getValue("passenger_view").jsonPrimitive.double)
        assertEquals(.9, preferences.getValue("certified_road").jsonPrimitive.double)
    }

    @Test fun continuousSceneryUsesCustomCategoriesRatherThanTheOldPreset() {
        val route = fixture.getValue("routes").jsonArray.single().jsonObject
        val metrics = route.getValue("metrics").jsonObject
        val updatedMetrics = JsonObject(metrics + ("continuous" to buildJsonObject {
            putJsonArray("sea") { add(buildJsonArray { add(0); add(70) }) }
            putJsonArray("lake") { add(buildJsonArray { add(0); add(100) }) }
        }))
        val result = JsonObject(fixture + ("routes" to buildJsonArray {
            add(JsonObject(route + ("metrics" to updatedMetrics)))
        }))
        val target = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(customWeights = mapOf("lake" to 1.0))))
        assertEquals(100, adapter().parse(result, target).single().scenic!!.continuousMeters)
    }

    @Test fun sendsEveryLandscapeProfileWithoutDroppingNewCategories() {
        for (preference in ScenicPreference.entries) {
            val target = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(preference = preference)))
            val preferences = adapter().request(target, "2026-10-17T12:00:00+09:00").getValue("preferences").jsonObject
            assertEquals(preference.weights, preferences.filterKeys { it !in setOf("variety", "passenger_view", "certified_road") }.mapValues { it.value.jsonPrimitive.double })
            assertEquals(1.0, preference.weights.values.sum(), .000001)
            assertTrue(preference.weights.values.all { it > 0 && it <= 1 })
        }
        assertTrue(ScenicPreference.BALANCED.weights.keys.containsAll(listOf("lake", "river", "valley", "farmland")))
        assertEquals(setOf("urban"), ScenicPreference.URBAN.weights.keys)
    }

    @Test fun sendsAndChecksNightCorrectionSetting() {
        assertTrue(adapter().request(query, "2026-10-17T12:00:00+09:00").getValue("night_correction").jsonPrimitive.boolean)
        val disabled = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(nightCorrection = false)))
        assertFalse(adapter().request(disabled, "2026-10-17T12:00:00+09:00").getValue("night_correction").jsonPrimitive.boolean)
        assertFailsWith<RoutingException> { adapter().parse(fixture, disabled) }
        val response = JsonObject(fixture + ("night_correction" to JsonPrimitive(false)))
        assertEquals(id, adapter().parse(response, disabled).single().plan.id)
        assertFailsWith<RoutingException> { adapter().parse(response, query) }
    }

    @Test fun publishesImprovingRevisionsWithoutDownloadingUnchangedResults() = runTest {
        var calls = 0
        val nextId = "route_" + "c".repeat(32)
        val first = JsonObject(fixture + mapOf("status" to JsonPrimitive("improving"), "revision" to JsonPrimitive(2)))
        val second = Json.parseToJsonElement(first.toString().replace(id, nextId)).jsonObject
        val final = JsonObject(second + mapOf("status" to JsonPrimitive("complete"), "revision" to JsonPrimitive(4)))
        val client = HttpClient(MockEngine { request ->
            calls++
            when (calls) {
                1 -> respond("""{"request_id":"${"b".repeat(32)}","status":"queued"}""")
                2, 4 -> respond("""{"status":"improving","revision":2}""")
                3 -> { assertNull(request.url.parameters["view"]); respond(first.toString()) }
                5 -> respond("""{"status":"improving","revision":3}""")
                6 -> { assertNull(request.url.parameters["view"]); respond(JsonObject(second + ("revision" to JsonPrimitive(3))).toString()) }
                else -> respond(final.toString())
            }
        })
        val updates = mutableListOf<String>()
        val result = withContext(Dispatchers.Default) {
            adapter(client).alternatives(query) { updates += it.single().plan.id }
        }
        assertEquals(listOf(id, nextId), updates)
        assertEquals(nextId, result.single().plan.id)
        assertEquals(7, calls)
        client.close()
    }

    private val id = "route_" + "a".repeat(32)
    private val query = RouteQuery(Coordinate(0.0, 0.0), Destination("end", "目的地", Coordinate(.001, .001)),
        RouteOptions(scenic = ScenicOptions(ScenicPreference.SEA, 300, totalSeconds = 240, desiredStops = 1, dwellSeconds = 60)))
    private val fixture get() = Json.parseToJsonElement("""
        {"status":"limited","snapshot_id":"snapshot-test","baseline":{"driving_seconds":100},
        "sources":[{"source_id":"osm","source_url":"https://www.openstreetmap.org/copyright","license":"ODbL-1.0","version":"map-test"}],
        "warnings":["予報未使用"],"routes":[{"route_id":"$id","revision":1,"snapshot_id":"snapshot-test","role":"海を楽しむ",
        "anchors":[{"lat":0.0005,"lon":0.0005,"type":"break","facility_id":"f1"}],
        "schedule":[{"facility_id":"f1","dwell_seconds":60,"geometry_index":1}],
        "route":{"trip":{"status":0,"summary":{"time":120,"length":0.157},"legs":[{"shape":"??g^g^","maneuvers":[]},{"shape":"g^g^g^g^","maneuvers":[]}]}},
        "timing":{"driving_seconds":120,"stop_seconds":60,"waiting_seconds":0,"total_seconds":180,"extra_driving_seconds":20},
        "distance_m":157,"metrics":{"sea_proximity_m":70,"threshold_m":{"forest":150},"coverage":0.9,"load":0.4,"continuous":{"sea":[[0,70]],"forest":[[0,150]]}},
        "evidence":[{"evidence_id":"e1","route_id":"$id","revision":1,"snapshot_id":"snapshot-test","source_ids":["osm"],"geometry_ranges":[{"start_m":0,"end_m":70}]}],
        "reasons":[{"reason_id":"r1","template":"sea_proximity","text":"海に近い区間","evidence_ids":["e1"],"geometry_ranges":[{"start_m":0,"end_m":70}]}],
        "timeline":[{"category":"sea","start_m":0,"end_m":157,"confidence":0.3,"openness":null,"visibility_verified":false,"arrival_time":"2026-10-17T12:01:00+09:00"}],"warnings":["可視性未確認"]}]}
    """).jsonObject

    private fun adapter(client: HttpClient = HttpClient(MockEngine { respond("") }), dispatcher: CoroutineDispatcher = Dispatchers.Default) =
        ScenicRecommendationClient("https://routing.example", client, { "2026-10-17T12:00:00+09:00" }, { "example" }, dispatcher)

    @Test fun pollsJobThenUsesReturnedRouteAndEvidenceWithoutRegenerating() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("Bearer example", request.headers[HttpHeaders.Authorization])
            when (calls) {
                1 -> { assertEquals("/route-recommendations", request.url.encodedPath)
                    respond("""{"request_id":"${"b".repeat(32)}","status":"queued"}""", HttpStatusCode.Accepted) }
                2 -> respond("""{"status":"processing"}""")
                else -> { assertEquals("status", request.url.parameters["view"]); respond(fixture.toString()) }
            }
        })
        val route = withContext(Dispatchers.Default) { adapter(client).alternatives(query).single() }
        assertEquals(id, route.plan.id)
        assertEquals(180, route.explanation?.totalSeconds)
        assertEquals(60, route.explanation?.stopSeconds)
        assertEquals(70, route.scenic?.continuousMeters)
        assertEquals(listOf("e1"), route.explanation?.reasons?.single()?.evidenceIds)
        assertEquals("sea_proximity", route.explanation?.reasons?.single()?.template)
        val block = route.explanation?.timeline?.single()
        assertNull(block?.openness)
        assertFalse(block?.visibilityVerified == true)
        assertEquals("2026-10-17T12:01:00+09:00", block?.arrivalTime)
        assertTrue(route.explanation?.searchLimited == true)
        assertEquals(3, calls)
    }

    @Test fun sendsDetourBudgetWithoutTotalLimitForPointToPoint() {
        val body = adapter().request(query, "2026-10-17T12:00:00+09:00")
        assertFalse("max_total_seconds" in body.getValue("budget").jsonObject)
        assertEquals(6, body.getValue("max_routes").jsonPrimitive.int)
        assertEquals(300, body.getValue("budget").jsonObject.getValue("max_extra_driving_seconds").jsonPrimitive.int)
        assertEquals("penalize_unknown", body.getValue("constraints").jsonObject.getValue("narrow_road_policy").jsonPrimitive.content)
    }

    @Test fun parsesSixRoutesAndRejectsDuplicateIdsOrExceedingRequestedLimit() {
        val item = fixture.getValue("routes").jsonArray.single().jsonObject
        val routes = (0 until 6).map { index ->
            Json.parseToJsonElement(item.toString().replace(id, "route_" + (index + 1).toString().repeat(32)))
        }
        val root = JsonObject(fixture + ("routes" to JsonArray(routes)))
        assertEquals(6, adapter().parse(root, query).size)
        val limited = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(maxRoutes = 3)))
        assertFailsWith<IllegalArgumentException> { adapter().parse(root, limited) }
        assertFailsWith<IllegalArgumentException> {
            adapter().parse(JsonObject(fixture + ("routes" to JsonArray(listOf(item, item)))), query)
        }
    }

    @Test fun parsesReferenceTimelineAndMetricsWithoutMakingItAnotherCandidate() {
        val item = fixture.getValue("routes").jsonArray.single().jsonObject
        val baselineRoute = Json.parseToJsonElement(item.toString()
            .replace("\"stop_seconds\":60", "\"stop_seconds\":0")
            .replace("\"total_seconds\":180", "\"total_seconds\":120")
            .replace("\"schedule\":[{\"facility_id\":\"f1\",\"dwell_seconds\":60,\"geometry_index\":1}]", "\"schedule\":[]")
            .replace("\"legs\":[{\"shape\":\"??g^g^\",\"maneuvers\":[]},{\"shape\":\"g^g^g^g^\",\"maneuvers\":[]}]",
                "\"legs\":[{\"shape\":\"??o}@o}@\",\"maneuvers\":[]}]"))
        val root = JsonObject(fixture + ("baseline" to baselineRoute))
        val result = adapter().parse(root, query)
        assertEquals(1, result.size)
        val reference = assertNotNull(result.single().explanation?.reference)
        assertEquals(120, reference.drivingSeconds)
        assertEquals(70, reference.metrics.seaMeters)
        assertEquals("sea", reference.timeline.single().category)
        val foreignBaseline = Json.parseToJsonElement(baselineRoute.toString().replace("snapshot-test", "foreign-snapshot"))
        assertFailsWith<IllegalArgumentException> {
            adapter().parse(JsonObject(root + ("baseline" to foreignBaseline)), query)
        }
        assertNull(adapter().parse(fixture, query).single().explanation?.reference)
    }

    @Test fun readsPortfolioRoleWithoutChangingImmutableRoute() {
        val root = JsonObject(fixture + ("portfolio_roles" to buildJsonObject { put(id, "海沿いを満喫") }))
        assertEquals("海沿いを満喫", adapter().parse(root, query).single().explanation?.role)
        assertEquals("海を楽しむ", adapter().parse(fixture, query).single().explanation?.role)
    }

    @Test fun sendsSixHourDetourWithoutClampingOrAddingTotalLimit() {
        val longDetour = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(extraSeconds = 21600)))
        val budget = adapter().request(longDetour, "2026-10-17T12:00:00+09:00").getValue("budget").jsonObject
        assertEquals(21600, budget.getValue("max_extra_driving_seconds").jsonPrimitive.int)
        assertFalse("max_total_seconds" in budget)
    }

    @Test fun roundTripKeepsItsTotalTimeBudget() {
        val roundTrip = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(roundTrip = true)))
        val budget = adapter().request(roundTrip, "2026-10-17T12:00:00+09:00").getValue("budget").jsonObject
        assertEquals(240, budget.getValue("max_total_seconds").jsonPrimitive.int)
        val overLimit = Json.parseToJsonElement(fixture.toString().replace("\"total_seconds\":180", "\"total_seconds\":780")
            .replace("\"stop_seconds\":60", "\"stop_seconds\":660").replace("\"dwell_seconds\":60", "\"dwell_seconds\":660")).jsonObject
        assertEquals(780, adapter().parse(overLimit, query).single().explanation?.totalSeconds)
        assertFailsWith<IllegalArgumentException> { adapter().parse(overLimit, roundTrip) }
    }

    @Test fun rejectsDrivingBeyondDetourBudget() {
        val tight = query.copy(options = query.options.copy(scenic = query.options.scenic!!.copy(extraSeconds = 19)))
        assertFailsWith<IllegalArgumentException> { adapter().parse(fixture, tight) }
    }

    @Test fun readsRecommendedRoadRankAndRequiresKnownSources() {
        val original = "\"timeline\":[{\"category\":\"sea\""
        val enriched = "\"timeline\":[{\"recommended_roads\":[{\"id\":\"test-road\",\"name\":\"試験用の道\",\"rank\":\"signature\",\"source_ids\":[\"osm\"]}],\"category\":\"sea\""
        val root = Json.parseToJsonElement(fixture.toString().replace(original, enriched)).jsonObject
        val road = adapter().parse(root, query).single().explanation!!.timeline.single().recommendedRoads.single()
        assertEquals(ScenicRoadRank.SIGNATURE, road.rank)
        assertEquals("秀逸な道", road.rank.label)
        assertFailsWith<IllegalArgumentException> {
            adapter().parse(Json.parseToJsonElement(root.toString().replace("\"source_ids\":[\"osm\"]", "\"source_ids\":[\"missing\"]")).jsonObject, query)
        }
        assertTrue(adapter().parse(fixture, query).single().explanation!!.timeline.single().recommendedRoads.isEmpty())
    }

    @Test fun rejectsBudgetArithmeticMismatchAndMissingEvidence() {
        val text = fixture.toString()
        assertFailsWith<IllegalArgumentException> { adapter().parse(Json.parseToJsonElement(text.replace("\"total_seconds\":180", "\"total_seconds\":181")).jsonObject, query) }
        assertFailsWith<IllegalArgumentException> { adapter().parse(Json.parseToJsonElement(text.replace("\"evidence_ids\":[\"e1\"]", "\"evidence_ids\":[\"unknown\"]")).jsonObject, query) }
        assertFailsWith<IllegalArgumentException> { adapter().parse(Json.parseToJsonElement(text.replace("\"total_seconds\":180", "\"total_seconds\":250")).jsonObject, query) }
    }

    @Test fun rejectsMixedRevisionAndUnknownSources() {
        val text = fixture.toString()
        assertFailsWith<IllegalArgumentException> { adapter().parse(Json.parseToJsonElement(text.replace("\"evidence_id\":\"e1\",\"route_id\":\"$id\",\"revision\":1", "\"evidence_id\":\"e1\",\"route_id\":\"$id\",\"revision\":2")).jsonObject, query) }
        assertFailsWith<IllegalArgumentException> { adapter().parse(Json.parseToJsonElement(text.replace("\"source_ids\":[\"osm\"]", "\"source_ids\":[\"unverified\"]")).jsonObject, query) }
    }
}
