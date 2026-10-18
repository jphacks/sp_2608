package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.RoutingException
import com.theacar.route.RoutingFailure
import com.theacar.route.RouteCalculator
import com.theacar.route.RoutePlan
import com.theacar.route.RouteQuery
import com.theacar.route.RouteStep
import com.theacar.route.RouteWaypoint
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.pow

class ValhallaRouteCalculator(
    private val endpoint: String,
    private val client: HttpClient = routingHttpClient(),
    private val accessToken: () -> String = { "" },
) : RouteCalculator {
    private var sequence = 0L

    override suspend fun calculate(query: RouteQuery): RoutePlan {
        if (endpoint.isBlank()) throw RoutingException(RoutingFailure.NOT_CONFIGURED)
        val token = accessToken().trim()
        if (!canSendBearerToken(endpoint, token)) {
            throw RoutingException(RoutingFailure.AUTHORIZATION)
        }
        try {
            val response = client.post(endpoint.trimEnd('/') + "/route") {
                contentType(ContentType.Application.Json)
                if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(request(query).toString())
            }
            if (response.status.value == 429) throw RoutingException(RoutingFailure.LIMIT_REACHED)
            if (response.status.value in setOf(401, 403)) throw RoutingException(RoutingFailure.AUTHORIZATION)
            if (response.status.value >= 500) throw RoutingException(RoutingFailure.NETWORK)
            if (response.status.value !in 200..299) throw RoutingException(RoutingFailure.NO_ROUTE)
            val text = response.bodyAsText()
            require(text.length <= 16_000_000)
            val id = "valhalla:${++sequence}"
            return withContext(Dispatchers.Default) { parse(text, query, id) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RoutingException) {
            throw error
        } catch (_: Exception) {
            throw RoutingException(RoutingFailure.NETWORK)
        }
    }

    internal fun request(query: RouteQuery) = buildJsonObject {
        putJsonArray("locations") {
            fun point(coordinate: Coordinate, type: String) {
                add(buildJsonObject { put("lat", coordinate.latitude); put("lon", coordinate.longitude); put("type", type) })
            }
            point(query.origin, "break")
            query.options.viaPoints.forEach { point(it, "through") }
            point(query.destination.coordinate, "break")
        }
        put("costing", "auto")
        putJsonObject("costing_options") {
            putJsonObject("auto") {
                put("use_tolls", if (query.options.avoidTolls) 0.0 else 0.5)
                put("use_highways", if (query.options.avoidHighways) 0.0 else 1.0)
                put("use_ferry", if (query.options.avoidFerries) 0.0 else 0.5)
            }
        }
        put("units", "kilometers")
        put("language", "ja-JP")
        put("directions_type", "instructions")
    }

    internal fun parse(text: String, query: RouteQuery, id: String): RoutePlan {
        val trip = Json.parseToJsonElement(text).jsonObject["trip"]?.jsonObject
            ?: throw RoutingException(RoutingFailure.NO_ROUTE)
        if (trip["status"]?.jsonPrimitive?.int != 0) throw RoutingException(RoutingFailure.NO_ROUTE)
        val geometry = mutableListOf<Coordinate>()
        val steps = mutableListOf<RouteStep>()
        val legs = trip.getValue("legs").jsonArray
        require(legs.isNotEmpty())
        legs.forEach { legElement ->
            val leg = legElement.jsonObject
            val shape = decodePolyline6(leg.getValue("shape").jsonPrimitive.content)
            require(shape.size >= 2)
            val joined = geometry.lastOrNull() == shape.first()
            val base = if (joined) geometry.lastIndex else geometry.size
            geometry.addAll(if (joined) shape.drop(1) else shape)
            require(geometry.size <= 200_000)
            leg.getValue("maneuvers").jsonArray.forEach { element ->
                val maneuver = element.jsonObject
                if ((query.options.avoidTolls && maneuver.flag("toll")) ||
                    (query.options.avoidHighways && maneuver.flag("highway")) ||
                    (query.options.avoidFerries && maneuver.flag("ferry"))) {
                    throw RoutingException(RoutingFailure.NO_ROUTE)
                }
                val begin = maneuver.getValue("begin_shape_index").jsonPrimitive.int
                val end = maneuver.getValue("end_shape_index").jsonPrimitive.int
                require(begin in shape.indices && end in begin..shape.lastIndex)
                val instruction = maneuver["instruction"]?.jsonPrimitive?.content.orEmpty()
                steps += RouteStep(instruction,
                    maneuver["verbal_pre_transition_instruction"]?.jsonPrimitive?.content ?: instruction,
                    maneuver["street_names"]?.jsonArray?.map { it.jsonPrimitive.content }?.joinToString("・").orEmpty(),
                    base + begin, base + end, maneuver.getValue("type").jsonPrimitive.int,
                    maneuver["time"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() }?.toInt(),
                    maneuver.flag("highway"))
            }
        }
        val summary = trip.getValue("summary").jsonObject
        val length = summary.getValue("length").jsonPrimitive.double * 1000
        val time = summary.getValue("time").jsonPrimitive.double
        require(length.isFinite() && length in 0.0..Int.MAX_VALUE.toDouble())
        require(time.isFinite() && time in 0.0..Int.MAX_VALUE.toDouble())
        var previous = 0
        val waypoints = query.options.viaPoints.map { point ->
            val index = (previous..geometry.lastIndex).minBy { index ->
                val candidate = geometry[index]
                (point.latitude - candidate.latitude).pow(2) + (point.longitude - candidate.longitude).pow(2)
            }
            previous = index
            RouteWaypoint(point, index)
        }
        return RoutePlan(id, query.destination, length.toInt(), time.toInt(), geometry, steps, waypoints)
    }
}

private fun JsonObject.flag(name: String) = this[name]?.jsonPrimitive?.booleanOrNull == true

fun routingHttpClient() = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 15_000 }
}
