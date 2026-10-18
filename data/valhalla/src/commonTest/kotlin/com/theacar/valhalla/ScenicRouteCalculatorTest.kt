package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ScenicRouteCalculatorTest {
    private val origin = Coordinate(0.0, 0.0)
    private val end = Coordinate(0.001, 0.001)
    private val query = RouteQuery(origin, Destination("end", "目的地", end),
        RouteOptions(scenic = ScenicOptions(ScenicPreference.SEA, 300)))

    private fun response(seconds: Int = 120, coverage: Double = .9) = buildJsonObject {
        put("baselineSeconds", 100)
        putJsonArray("alternatives") {
            add(buildJsonObject {
                put("extraSeconds", (seconds - 100).coerceAtLeast(0))
                putJsonArray("viaPoints") { add(buildJsonObject { put("lat", .0005); put("lon", .0005) }) }
                putJsonObject("metrics") {
                    put("seaMeters", 70); put("forestMeters", 0); put("continuousMeters", 50); put("coverage", coverage)
                }
                putJsonObject("route") {
                    putJsonObject("trip") {
                        put("status", 0)
                        putJsonObject("summary") { put("time", seconds); put("length", .157) }
                        putJsonArray("legs") {
                            add(buildJsonObject {
                                put("shape", "??g^g^g^g^")
                                putJsonArray("maneuvers") {}
                            })
                        }
                    }
                }
            })
        }
    }.toString()

    @Test fun usesAuthenticatedScenicEndpointAndParsesCandidates() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/scenic/route", request.url.encodedPath)
            assertEquals("Bearer example", request.headers[HttpHeaders.Authorization])
            respond(response())
        })
        val choice = ScenicRouteCalculator("https://routing.example", client) { "example" }.alternatives(query).single()
        assertEquals(120, choice.plan.durationSeconds)
        assertEquals(1, choice.plan.waypoints.size)
        assertEquals(70, choice.scenic?.seaMeters)
        assertEquals(20, choice.scenic?.extraSeconds)
    }

    @Test fun rejectsOverBudgetRoutesAndInvalidMetrics() {
        val adapter = ScenicRouteCalculator("http://localhost", HttpClient(MockEngine { respond("") }))
        assertFailsWith<IllegalArgumentException> { adapter.parse(response(500), query, 1) }
        assertFailsWith<IllegalArgumentException> { adapter.parse(response(coverage = 1.1), query, 1) }
    }

    @Test fun doesNotFallbackSilentlyWhenScenicServerIsMissing() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val error = assertFailsWith<RoutingException> { ScenicRouteCalculator("http://localhost", client).alternatives(query) }
        assertEquals(RoutingFailure.SCENIC_UNAVAILABLE, error.failure)
    }

    @Test fun neverSendsTokenToRemotePlainHttp() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond(response()) })
        assertEquals(RoutingFailure.AUTHORIZATION, assertFailsWith<RoutingException> {
            ScenicRouteCalculator("http://routing.example", client) { "example" }.alternatives(query)
        }.failure)
        assertEquals(0, calls)
    }

    @Test fun rejectsManualWaypointsInsteadOfDroppingThem() = runTest {
        val client = HttpClient(MockEngine { error("Must not call server") })
        assertFailsWith<IllegalArgumentException> {
            ScenicRouteCalculator("http://localhost", client).alternatives(query.copy(options = query.options.copy(viaPoints = listOf(end))))
        }
    }
}
