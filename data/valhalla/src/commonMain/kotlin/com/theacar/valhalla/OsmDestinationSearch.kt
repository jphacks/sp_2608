package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.RoutingException
import com.theacar.route.RoutingFailure
import com.theacar.route.Destination
import com.theacar.route.DestinationSearch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

class OsmDestinationSearch(
    private val endpoint: String = "",
    private val client: HttpClient = routingHttpClient(),
    private val accessToken: () -> String = { "" },
) : DestinationSearch {
    override suspend fun search(query: String): List<Destination> {
        val parts = query.trim().split(',').map(String::trim)
        if (parts.size == 2) {
            val latitude = parts[0].toDoubleOrNull()
            val longitude = parts[1].toDoubleOrNull()
            if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                return listOf(Destination("coordinate:$latitude,$longitude", query, Coordinate(latitude, longitude)))
            }
            if (latitude != null && longitude != null) return emptyList()
        }
        if (endpoint.isBlank()) throw RoutingException(RoutingFailure.NOT_CONFIGURED)
        val token = accessToken().trim()
        if (!canSendBearerToken(endpoint, token)) {
            throw RoutingException(RoutingFailure.AUTHORIZATION)
        }
        val response = try {
            client.get(endpoint.trimEnd('/') + "/search") {
                if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
                parameter("q", query); parameter("format", "jsonv2"); parameter("limit", 10)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw RoutingException(RoutingFailure.NETWORK)
        }
        if (response.status.value == 429) throw RoutingException(RoutingFailure.LIMIT_REACHED)
        if (response.status.value in setOf(401, 403)) throw RoutingException(RoutingFailure.AUTHORIZATION)
        if (response.status.value !in 200..299) throw RoutingException(RoutingFailure.NETWORK)
        val text = response.bodyAsText()
        require(text.length < 1_000_000)
        val found = Json.parseToJsonElement(text).jsonArray.map {
            val item = it.jsonObject
            val lat = item.getValue("lat").jsonPrimitive.double
            val lon = item.getValue("lon").jsonPrimitive.double
            Destination("osm:${item.getValue("place_id").jsonPrimitive.content}",
                stationTitle(item.getValue("display_name").jsonPrimitive.content), Coordinate(lat, lon))
        }
        // 駅の本体とホームの停車位置のように、同じ名前で近くにあるものは1件にまとめる
        return found.filterIndexed { index, place ->
            found.take(index).none { it.title.substringBefore(" · ") == place.title.substringBefore(" · ") &&
                roughMeters(it.coordinate, place.coordinate) < 300 }
        }.take(5)
    }
}

/** 地名データでは駅の名前が「札幌」だけなので、「札幌駅」と呼ぶ */
internal fun stationTitle(label: String): String {
    val parts = label.split(" · ")
    if (parts.size < 2 || parts.last() !in setOf("station", "stop", "halt") || parts[0].endsWith("駅")) return label
    return (listOf(parts[0] + "駅") + parts.drop(1)).joinToString(" · ")
}

private fun roughMeters(a: Coordinate, b: Coordinate): Double {
    val dy = (a.latitude - b.latitude) * 111_000
    val dx = (a.longitude - b.longitude) * 111_000 * kotlin.math.cos(a.latitude * kotlin.math.PI / 180)
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
