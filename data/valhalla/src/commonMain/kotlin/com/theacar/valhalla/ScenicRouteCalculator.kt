package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ScenicRouteCalculator(
    private val endpoint: String,
    private val client: HttpClient = routingHttpClient(),
    private val accessToken: () -> String = { "" },
) : RouteAlternativesCalculator {
    private val ordinary = ValhallaRouteCalculator(endpoint, client, accessToken)
    private var sequence = 0L

    override suspend fun calculate(query: RouteQuery) = ordinary.calculate(query)

    override suspend fun alternatives(query: RouteQuery): List<RouteAlternative> {
        val options = query.options.scenic ?: return listOf(RouteAlternative(calculate(query)))
        require(query.options.viaPoints.isEmpty()) { "Scenic search cannot reorder manual waypoints" }
        if (endpoint.isBlank()) throw RoutingException(RoutingFailure.NOT_CONFIGURED)
        val token = accessToken().trim()
        if (!canSendBearerToken(endpoint, token)) {
            throw RoutingException(RoutingFailure.AUTHORIZATION)
        }
        try {
            val response = client.post(endpoint.trimEnd('/') + "/scenic/route") {
                timeout { requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
                contentType(ContentType.Application.Json)
                if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(buildJsonObject {
                    fun point(name: String, coordinate: Coordinate) {
                        putJsonObject(name) { put("lat", coordinate.latitude); put("lon", coordinate.longitude) }
                    }
                    point("origin", query.origin)
                    point("destination", query.destination.coordinate)
                    put("preference", options.preference.name.lowercase())
                    put("extraSeconds", options.extraSeconds)
                    putJsonObject("options") {
                        put("avoidTolls", query.options.avoidTolls)
                        put("avoidHighways", query.options.avoidHighways)
                        put("avoidFerries", query.options.avoidFerries)
                    }
                }.toString())
            }
            when (response.status.value) {
                401, 403 -> throw RoutingException(RoutingFailure.AUTHORIZATION)
                429 -> throw RoutingException(RoutingFailure.LIMIT_REACHED)
                404, 422, 502, 503 -> throw RoutingException(RoutingFailure.SCENIC_UNAVAILABLE)
            }
            if (response.status.value !in 200..299) throw RoutingException(RoutingFailure.NETWORK)
            val text = response.bodyAsText()
            require(text.length <= 16_000_000)
            val id = ++sequence
            return withContext(Dispatchers.Default) { parse(text, query, id) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RoutingException) {
            throw error
        } catch (_: Exception) {
            throw RoutingException(RoutingFailure.NETWORK)
        }
    }

    internal fun parse(text: String, query: RouteQuery, sequence: Long): List<RouteAlternative> {
        val root = Json.parseToJsonElement(text).jsonObject
        val baseline = root.getValue("baselineSeconds").jsonPrimitive.int
        require(baseline > 0)
        val alternatives = root.getValue("alternatives").jsonArray
        require(alternatives.size in 1..3)
        return alternatives.mapIndexed { index, element ->
            val value = element.jsonObject
            val via = value.getValue("viaPoints").jsonArray.map {
                val point = it.jsonObject
                Coordinate(point.getValue("lat").jsonPrimitive.double, point.getValue("lon").jsonPrimitive.double)
            }
            val plan = ordinary.parse(value.getValue("route").toString(),
                query.copy(options = query.options.copy(viaPoints = via)), "scenic:$sequence:$index")
            require(plan.durationSeconds <= baseline + requireNotNull(query.options.scenic).extraSeconds)
            val metrics = value.getValue("metrics").jsonObject
            RouteAlternative(plan, ScenicMetrics(value.getValue("extraSeconds").jsonPrimitive.int,
                metrics.getValue("seaMeters").jsonPrimitive.int,
                metrics.getValue("forestMeters").jsonPrimitive.int,
                metrics.getValue("continuousMeters").jsonPrimitive.int,
                metrics.getValue("coverage").jsonPrimitive.double))
        }
    }
}
