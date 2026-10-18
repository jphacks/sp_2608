package com.theacar.valhalla

import com.theacar.model.Coordinate
import com.theacar.route.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.math.ceil

class ScenicRecommendationClient(
    private val endpoint: String,
    private val client: HttpClient,
    private val departureTime: () -> String,
    private val accessToken: () -> String = { "" },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RouteAlternativesCalculator {
    private val ordinary = ValhallaRouteCalculator(endpoint, client, accessToken)
    override suspend fun calculate(query: RouteQuery) = ordinary.calculate(query)

    override suspend fun alternatives(query: RouteQuery): List<RouteAlternative> = alternatives(query) {}

    override suspend fun alternatives(query: RouteQuery, onUpdate: suspend (List<RouteAlternative>) -> Unit): List<RouteAlternative> {
        val options = query.options.scenic ?: return listOf(RouteAlternative(calculate(query)))
        require(query.options.viaPoints.isEmpty())
        if (endpoint.isBlank()) throw RoutingException(RoutingFailure.NOT_CONFIGURED)
        val target = if (options.roundTrip) query.copy(destination = Destination("roundtrip", "出発地点へ戻る", query.origin)) else query
        val token = accessToken().trim()
        if (!canSendBearerToken(endpoint, token)) {
            throw RoutingException(RoutingFailure.AUTHORIZATION)
        }
        var activeRequestId: String? = null
        try {
            val created = post("/route-recommendations", request(target, departureTime()), token)
            val requestId = created.getValue("request_id").jsonPrimitive.content
            require(requestId.matches(Regex("[a-f0-9]{32}")))
            activeRequestId = requestId
            return withTimeout(125_000) {
                var lastRevision = -1
                while (true) {
                    val result = get("/route-recommendations/$requestId?view=status", token)
                    when (result.getValue("status").jsonPrimitive.content) {
                        "complete", "limited" -> return@withTimeout withContext(computationDispatcher) { parse(result, target) }
                        "failed" -> throw RoutingException(RoutingFailure.NETWORK)
                        "infeasible" -> throw RoutingException(RoutingFailure.NO_ROUTE)
                        "improving" -> {
                            val revision = result["revision"]?.jsonPrimitive?.int ?: 0
                            if (revision != lastRevision) {
                                val full = get("/route-recommendations/$requestId", token)
                                if (full["routes"]?.jsonArray?.isNotEmpty() == true) {
                                    val choices = withContext(computationDispatcher) { parse(full, target) }
                                    onUpdate(choices)
                                    lastRevision = full["revision"]?.jsonPrimitive?.int ?: revision
                                }
                            }
                            delay(2_000)
                        }
                        "queued", "processing" -> delay(2_000)
                        else -> throw RoutingException(RoutingFailure.NETWORK)
                    }
                }
                @Suppress("UNREACHABLE_CODE") emptyList()
            }
        } catch (error: CancellationException) {
            activeRequestId?.let { id ->
                withContext(NonCancellable) {
                    runCatching {
                        withTimeout(3_000) {
                            client.delete(endpoint.trimEnd('/') + "/route-recommendations/$id") {
                                if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
                            }
                        }
                    }
                }
            }
            throw error
        } catch (error: RoutingException) {
            throw error
        } catch (_: Exception) {
            throw RoutingException(RoutingFailure.NETWORK)
        }
    }

    private suspend fun post(path: String, body: JsonObject, token: String): JsonObject = response(client.post(endpoint.trimEnd('/') + path) {
        contentType(ContentType.Application.Json)
        if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
        setBody(body.toString())
    })

    private suspend fun get(path: String, token: String): JsonObject = response(client.get(endpoint.trimEnd('/') + path) {
        if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
    })

    private suspend fun response(response: io.ktor.client.statement.HttpResponse): JsonObject {
        when (response.status.value) {
            401, 403 -> throw RoutingException(RoutingFailure.AUTHORIZATION)
            429 -> throw RoutingException(RoutingFailure.LIMIT_REACHED)
            404, 422, 502, 503 -> throw RoutingException(RoutingFailure.SCENIC_UNAVAILABLE)
        }
        if (response.status.value !in 200..299) throw RoutingException(RoutingFailure.NETWORK)
        val text = response.bodyAsText()
        require(text.length <= 24_000_000)
        return Json.parseToJsonElement(text).jsonObject
    }

    internal fun request(query: RouteQuery, departure: String) = buildJsonObject {
        val scenic = requireNotNull(query.options.scenic)
        fun point(name: String, coordinate: Coordinate) {
            putJsonObject(name) { put("lat", coordinate.latitude); put("lon", coordinate.longitude) }
        }
        point("origin", query.origin)
        point("destination", query.destination.coordinate)
        put("mode", if (scenic.roundTrip) "round_trip" else "point_to_point")
        put("departure_time", departure)
        put("seed", scenic.seed)
        put("max_routes", scenic.maxRoutes)
        put("night_correction", scenic.nightCorrection)
        putJsonObject("budget") {
            put("max_extra_driving_seconds", scenic.extraSeconds)
            if (scenic.roundTrip) put("max_total_seconds", scenic.totalSeconds)
        }
        putJsonObject("preferences") {
            scenic.preferenceWeights.forEach { (category, weight) -> put(category, weight) }
            put("variety", scenic.variety)
            put("passenger_view", scenic.passengerView)
            put("certified_road", scenic.certifiedRoad)
        }
        putJsonObject("constraints") {
            put("allow_tolls", !query.options.avoidTolls)
            put("allow_highways", !query.options.avoidHighways)
            put("allow_ferries", !query.options.avoidFerries)
            put("allow_unpaved", scenic.allowUnpaved)
            put("narrow_road_policy", if (scenic.requireVerifiedWidth) "require_verified_width" else "penalize_unknown")
        }
        putJsonObject("stops") {
            put("desired_count", scenic.desiredStops)
            put("default_dwell_seconds", scenic.dwellSeconds)
        }
    }

    internal fun parse(root: JsonObject, query: RouteQuery): List<RouteAlternative> = parseRoutes(root, query, true)

    private fun parseRoutes(root: JsonObject, query: RouteQuery, includeReference: Boolean): List<RouteAlternative> {
        val options = requireNotNull(query.options.scenic)
        if ((root["night_correction"]?.jsonPrimitive?.boolean ?: true) != options.nightCorrection) {
            throw RoutingException(RoutingFailure.SCENIC_UNAVAILABLE)
        }
        val snapshotId = root.getValue("snapshot_id").jsonPrimitive.content
        val baseline = root.getValue("baseline").jsonObject
        val baselineSeconds = baseline["timing"]?.jsonObject?.get("driving_seconds")?.jsonPrimitive?.int
            ?: baseline.getValue("driving_seconds").jsonPrimitive.int
        val sources = root.getValue("sources").jsonArray.map { element ->
            val source = element.jsonObject
            ScenicSource(source.getValue("source_id").jsonPrimitive.content, source.getValue("source_url").jsonPrimitive.content,
                source.getValue("license").jsonPrimitive.content, source.getValue("version").jsonPrimitive.content)
        }
        val globalWarnings = root.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
        val routes = root.getValue("routes").jsonArray
        require(routes.size in 1..options.maxRoutes)
        require(routes.map { it.jsonObject.getValue("route_id").jsonPrimitive.content }.distinct().size == routes.size)
        val reference = if (includeReference && !options.roundTrip && baseline["route"] != null) {
            val baselineRoot = JsonObject(root + mapOf("routes" to JsonArray(listOf(baseline)),
                "portfolio_roles" to JsonObject(emptyMap()), "counterfactuals" to JsonArray(emptyList())))
            val parsed = parseRoutes(baselineRoot, query.copy(options = query.options.copy(
                scenic = options.copy(desiredStops = 0))), false).single()
            val explanation = requireNotNull(parsed.explanation)
            ScenicRouteReference(parsed.plan.durationSeconds, parsed.plan.distanceMeters, explanation.stopSeconds,
                requireNotNull(parsed.scenic), explanation.timeline, explanation.load,
                explanation.repeatedMeters, explanation.recommendedMeters, explanation.environment)
        } else null
        return routes.map { element ->
            val item = element.jsonObject
            require(item.getValue("snapshot_id").jsonPrimitive.content == snapshotId)
            val routeId = item.getValue("route_id").jsonPrimitive.content
            require(routeId.matches(Regex("route_[a-f0-9]{32}")))
            val anchors = item.getValue("anchors").jsonArray.map { anchor ->
                val p = anchor.jsonObject
                Coordinate(p.getValue("lat").jsonPrimitive.double, p.getValue("lon").jsonPrimitive.double)
            }
            val plan = ordinary.parse(item.getValue("route").toString(), query.copy(options = query.options.copy(viaPoints = anchors)), routeId)
            val timing = item.getValue("timing").jsonObject
            val driving = timing.getValue("driving_seconds").jsonPrimitive.int
            val total = timing.getValue("total_seconds").jsonPrimitive.int
            val stops = timing.getValue("stop_seconds").jsonPrimitive.int
            val wait = timing.getValue("waiting_seconds").jsonPrimitive.int
            require(driving >= plan.durationSeconds && driving <= plan.durationSeconds + 1)
            require(stops >= 0 && wait >= 0 && total == driving + stops + wait)
            if (options.roundTrip) require(total <= options.totalSeconds)
            val schedule = item.getValue("schedule").jsonArray.map { it.jsonObject }
            require(schedule.size <= options.desiredStops)
            require(schedule.map { it.getValue("facility_id").jsonPrimitive.content }.distinct().size == schedule.size)
            require(schedule.sumOf { it.getValue("dwell_seconds").jsonPrimitive.int.also { require(it in 60..7200) } } == stops)
            require(item.getValue("route").jsonObject.getValue("trip").jsonObject.getValue("legs").jsonArray.size == schedule.size + 1)
            schedule.forEach { stop ->
                require(stop.getValue("geometry_index").jsonPrimitive.int in plan.geometry.indices)
                require(item.getValue("anchors").jsonArray.any {
                    val anchor = it.jsonObject
                    anchor["facility_id"]?.jsonPrimitive?.contentOrNull == stop.getValue("facility_id").jsonPrimitive.content &&
                        anchor.getValue("type").jsonPrimitive.content == "break"
                })
            }
            if (!options.roundTrip) require(driving <= baselineSeconds + options.extraSeconds)
            val metrics = item.getValue("metrics").jsonObject
            val evidence = item.getValue("evidence").jsonArray.map { it.jsonObject }
            val evidenceIds = evidence.map { it.getValue("evidence_id").jsonPrimitive.content }.toSet()
            evidence.forEach {
                require(it.getValue("route_id").jsonPrimitive.content == routeId)
                require(it.getValue("snapshot_id").jsonPrimitive.content == snapshotId)
                require(it.getValue("revision").jsonPrimitive.int == item.getValue("revision").jsonPrimitive.int)
                require(it.getValue("source_ids").jsonArray.all { source -> sources.any { it.id == source.jsonPrimitive.content } })
            }
            val distance = item.getValue("distance_m").jsonPrimitive.double
            fun ranges(value: JsonArray) = value.map {
                val range = it.jsonObject
                ScenicRange(range.getValue("start_m").jsonPrimitive.double, range.getValue("end_m").jsonPrimitive.double).also {
                    require(it.endMeters <= distance + 1)
                }
            }
            val reasons = item.getValue("reasons").jsonArray.map {
                val reason = it.jsonObject
                val ids = reason.getValue("evidence_ids").jsonArray.map { it.jsonPrimitive.content }
                require(ids.isNotEmpty() && ids.all { it in evidenceIds })
                val spans = ranges(reason.getValue("geometry_ranges").jsonArray)
                val proved = evidence.filter { it.getValue("evidence_id").jsonPrimitive.content in ids }
                    .flatMap { ranges(it.getValue("geometry_ranges").jsonArray) }
                require(spans.all { span -> proved.any { it.startMeters <= span.startMeters && it.endMeters >= span.endMeters } })
                ScenicReason(reason.getValue("reason_id").jsonPrimitive.content,
                    reason.getValue("template").jsonPrimitive.content,
                    reason.getValue("text").jsonPrimitive.content, ids, spans)
            }
            val timeline = item.getValue("timeline").jsonArray.map {
                val block = it.jsonObject
                val range = ScenicRange(block.getValue("start_m").jsonPrimitive.double, block.getValue("end_m").jsonPrimitive.double)
                require(range.endMeters <= distance + 1)
                val roads = block["recommended_roads"]?.jsonArray.orEmpty().map { raw ->
                    val road = raw.jsonObject
                    val rank = ScenicRoadRank.entries.first { it.code == road.getValue("rank").jsonPrimitive.content }
                    val sourceIds = road.getValue("source_ids").jsonArray.map { it.jsonPrimitive.content }
                    require(sourceIds.isNotEmpty() && sourceIds.all { id -> sources.any { it.id == id } })
                    ScenicRecommendedRoad(road.getValue("id").jsonPrimitive.content,
                        road.getValue("name").jsonPrimitive.content, rank, sourceIds)
                }
                ScenicTimelineBlock(block.getValue("category").jsonPrimitive.content, range,
                    block.getValue("confidence").jsonPrimitive.double.also { require(it.isFinite() && it in 0.0..1.0) }, roads,
                    (block["openness"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
                    (block["visibility_verified"] as? JsonPrimitive)?.booleanOrNull == true,
                    (block["arrival_time"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            }
            val relevantCategories = options.preferenceWeights.keys
            val longest = metrics.getValue("continuous").jsonObject.filterKeys { it in relevantCategories }.values.flatMap { it.jsonArray }
                .maxOfOrNull { val range = it.jsonArray; range[1].jsonPrimitive.double - range[0].jsonPrimitive.double } ?: 0.0
            fun measuredLength(value: JsonElement): Int = value.jsonPrimitive.double.also {
                require(it.isFinite() && it in 0.0..distance + 1)
            }.toInt()
            val repeatedMeters = metrics["repeated_m"]?.let(::measuredLength)
            val recommendedMeters = metrics["recommended_unique_m"]?.jsonObject.orEmpty().mapNotNull { (key, value) ->
                ScenicRoadRank.entries.firstOrNull { it.code == key }?.let { it to measuredLength(value) }
            }.toMap()
            val comparisons = root["counterfactuals"]?.jsonArray.orEmpty().map { it.jsonObject }.filter {
                it.getValue("route_id").jsonPrimitive.content == routeId
            }.map {
                val alternate = it.getValue("comparison_route").jsonObject
                require(alternate.getValue("snapshot_id").jsonPrimitive.content == snapshotId)
                val seconds = it.getValue("driving_seconds_difference").jsonPrimitive.double.also { require(it.isFinite()) }
                val sea = it.getValue("sea_proximity_m_difference").jsonPrimitive.double.also { require(it.isFinite()) }
                "主な景観区間を外した接続候補との差：走行 ${seconds.toInt()}秒 / 海に近い区間 ${sea.toInt()}m。全代替経路との比較ではありません。"
            }
            RouteAlternative(plan, ScenicMetrics(timing.getValue("extra_driving_seconds").jsonPrimitive.int,
                measuredLength(metrics.getValue("sea_proximity_m")),
                measuredLength(metrics.getValue("threshold_m").jsonObject.getValue("forest")),
                longest.toInt(), metrics.getValue("coverage").jsonPrimitive.double,
                metrics.getValue("threshold_m").jsonObject.filterKeys {
                    it in setOf("lake", "river", "valley", "farmland", "urban") &&
                        ((metrics["missingness"]?.jsonObject?.get(it)?.jsonPrimitive?.double ?: 0.0) <= .01)
                }.mapValues { measuredLength(it.value) }),
                RouteExplanation(root["portfolio_roles"]?.jsonObject?.get(routeId)?.jsonPrimitive?.content
                    ?: item.getValue("role").jsonPrimitive.content, snapshotId,
                    item.getValue("revision").jsonPrimitive.int, total, stops, reasons, timeline,
                    item.getValue("warnings").jsonArray.map { it.jsonPrimitive.content } + globalWarnings,
                    sources, root.getValue("status").jsonPrimitive.content == "limited", baselineSeconds,
                    metrics.getValue("load").jsonPrimitive.double, comparisons, reference, repeatedMeters, recommendedMeters,
                    parseScenicEnvironment(item["environment"], distance)))
        }
    }
}
