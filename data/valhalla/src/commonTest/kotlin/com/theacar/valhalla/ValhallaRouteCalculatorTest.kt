package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ValhallaRouteCalculatorTest {
    private val start = Coordinate(35.0, 139.0)
    private val middle = Coordinate(35.001, 139.0)
    private val end = Coordinate(35.002, 139.0)
    private val query = RouteQuery(start, Destination("end", "目的地", end), RouteOptions(viaPoints = listOf(middle)))

    private fun encode(points: List<Coordinate>): String = buildString {
        var lat = 0
        var lon = 0
        fun component(delta: Int) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 32) { append(((value and 31) or 32).plus(63).toChar()); value = value ushr 5 }
            append((value + 63).toChar())
        }
        points.forEach {
            val nextLat = (it.latitude * 1_000_000).toInt()
            val nextLon = (it.longitude * 1_000_000).toInt()
            component(nextLat - lat); component(nextLon - lon)
            lat = nextLat; lon = nextLon
        }
    }

    private fun response(legs: List<List<Coordinate>>, toll: Boolean = false) = buildJsonObject {
        putJsonObject("trip") {
            put("status", 0)
            putJsonObject("summary") { put("length", 0.222); put("time", 100) }
            putJsonArray("legs") { legs.forEach { points -> add(buildJsonObject {
                put("shape", encode(points))
                putJsonArray("maneuvers") { add(buildJsonObject {
                    put("begin_shape_index", 0); put("end_shape_index", points.lastIndex)
                    put("type", 15); put("instruction", "左折してください")
                    put("verbal_pre_transition_instruction", "左に曲がります"); put("toll", toll)
                    put("time", 100.0); put("highway", true)
                }) }
            }) } }
        }
    }.toString()

    @Test fun usesThroughPointsWithoutCreatingStopoverLegs() {
        val adapter = ValhallaRouteCalculator("http://localhost", HttpClient(MockEngine { respond("") }))
        val json = adapter.request(query)
        assertEquals(listOf("break", "through", "break"), json.getValue("locations").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("ja-JP", json.getValue("language").jsonPrimitive.content)
        assertEquals("auto", json.getValue("costing").jsonPrimitive.content)
    }

    @Test fun parsesGeometryInstructionsAndThroughPoint() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/route", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(response(listOf(listOf(start, middle, end))))
        })
        val route = ValhallaRouteCalculator("http://localhost/", client).calculate(query)
        assertEquals(listOf(start, middle, end), route.geometry)
        assertEquals(222, route.distanceMeters)
        assertEquals(100, route.durationSeconds)
        assertEquals("左に曲がります", route.steps.single().voiceInstruction)
        assertEquals(100, route.steps.single().durationSeconds)
        assertTrue(route.steps.single().highway)
        assertEquals(1, route.waypoints.single().geometryIndex)
        client.close()
    }

    @Test fun joinsLegsAndOffsetsManeuverIndices() {
        val adapter = ValhallaRouteCalculator("", HttpClient(MockEngine { respond("") }))
        val route = adapter.parse(response(listOf(listOf(start, middle), listOf(middle, end))), query, "test")
        assertEquals(listOf(start, middle, end), route.geometry)
        assertEquals(listOf(0, 1), route.steps.map { it.beginGeometryIndex })
        assertEquals(listOf(1, 2), route.steps.map { it.endGeometryIndex })
    }

    @Test fun rejectsTollRouteWhenAvoidanceWasRequested() {
        val adapter = ValhallaRouteCalculator("", HttpClient(MockEngine { respond("") }))
        val failure = assertFailsWith<RoutingException> {
            adapter.parse(response(listOf(listOf(start, end)), toll = true), query.copy(options = RouteOptions(avoidTolls = true)), "test")
        }
        assertEquals(RoutingFailure.NO_ROUTE, failure.failure)
    }

    @Test fun mapsHttpFailuresWithoutRetrying() = runTest {
        listOf(429 to RoutingFailure.LIMIT_REACHED, 503 to RoutingFailure.NETWORK, 401 to RoutingFailure.AUTHORIZATION, 400 to RoutingFailure.NO_ROUTE).forEach { (status, expected) ->
            var requests = 0
            val client = HttpClient(MockEngine { requests++; respond("{}", HttpStatusCode.fromValue(status)) })
            assertEquals(expected, assertFailsWith<RoutingException> { ValhallaRouteCalculator("http://localhost", client).calculate(query) }.failure)
            assertEquals(1, requests)
            client.close()
        }
    }

    @Test fun readsCurrentCredentialForEachRequest() = runTest {
        var token = "first"
        val received = mutableListOf<String?>()
        val client = HttpClient(MockEngine {
            received += it.headers[HttpHeaders.Authorization]
            respond(response(listOf(listOf(start, end))))
        })
        val calculator = ValhallaRouteCalculator("https://routing.example.com", client) { token }
        calculator.calculate(query)
        token = "second"
        calculator.calculate(query)
        token = ""
        calculator.calculate(query)
        assertEquals(listOf("Bearer first", "Bearer second", null), received)
        client.close()
    }

    @Test fun refusesToSendTokenOverRemoteHttpOrWithHeaderInjection() = runTest {
        val client = HttpClient(MockEngine { error("Credentials must not be sent") })
        listOf("http://routing.example.com" to "token", "https://routing.example.com" to "token\r\nInjected: value").forEach { (endpoint, token) ->
            assertEquals(RoutingFailure.AUTHORIZATION, assertFailsWith<RoutingException> {
                ValhallaRouteCalculator(endpoint, client) { token }.calculate(query)
            }.failure)
        }
        client.close()
    }

    @Test fun acceptsMaximumThroughPointsAndRejectsOverflow() {
        RouteOptions(viaPoints = List(126) { middle })
        assertFailsWith<IllegalArgumentException> { RouteOptions(viaPoints = List(127) { middle }) }
    }

    @Test fun rejectsMalformedPolyline() {
        listOf("_", "~~~~~~~", "\u0000").forEach { assertFailsWith<IllegalArgumentException> { decodePolyline6(it) } }
        assertEquals(listOf(start, middle, end), decodePolyline6(encode(listOf(start, middle, end))))
    }

    @Test fun coordinateSearchDoesNotUsePublicGeocodingService() = runTest {
        val client = HttpClient(MockEngine { error("Unexpected request") })
        val search = OsmDestinationSearch("", client)
        assertEquals(start, search.search("35.0,139.0").single().coordinate)
        assertFailsWith<RoutingException> { search.search("東京駅") }
        assertTrue(search.search("91,139").isEmpty())
        client.close()
    }
}
