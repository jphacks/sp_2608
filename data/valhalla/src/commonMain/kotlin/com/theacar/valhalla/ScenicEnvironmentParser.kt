package com.theacar.valhalla

import com.theacar.route.*
import kotlinx.serialization.json.*

internal fun parseScenicEnvironment(element: JsonElement?, distance: Double): ScenicEnvironment? {
    if (element == null || element is JsonNull) return null
    val body = element.jsonObject
    fun JsonObject.optionalNumber(key: String, low: Double, high: Double): Double? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.double?.also {
            require(it.isFinite() && it in low..high)
        }
    fun JsonObject.number(key: String, low: Double, high: Double): Double = requireNotNull(optionalNumber(key, low, high))
    fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    fun JsonObject.range() = ScenicRange(number("start_m", 0.0, distance + 1), number("end_m", 0.0, distance + 1))
    val raw = body.getValue("timeline").jsonArray
    require(raw.size <= 192)
    val sections = raw.map { value ->
        val item = value.jsonObject
        val features = item.getValue("features").jsonObject.mapValues { (_, value) ->
            if (value is JsonNull) null else value.jsonPrimitive.double.also { require(it.isFinite() && it in 0.0..1.0) }
        }
        val weatherBounds = mapOf("temperature_2m" to -100.0..70.0, "snowfall" to 0.0..200.0,
            "snow_depth" to 0.0..100.0, "wind_speed_10m" to 0.0..150.0, "wind_gusts_10m" to 0.0..200.0,
            "precipitation_probability" to 0.0..100.0, "relative_humidity_2m" to 0.0..100.0)
        val weather = item["weather_details"]?.jsonObject.orEmpty().filterKeys { it in weatherBounds }
            .mapNotNull { (key, value) -> if (value is JsonNull) null else key to value.jsonPrimitive.double.also {
                require(it.isFinite() && it in weatherBounds.getValue(key))
            } }.toMap()
        ScenicEnvironmentSection(item.range(), item.text("start_time"), item.text("end_time"), item.text("arrival_time"),
            item.number("elapsed_seconds", 0.0, 604800.0).toInt(), item.number("solar_altitude", -90.0, 90.0),
            item.number("solar_azimuth", 0.0, 360.0), item.text("light_phase"), item.text("sky"),
            item.optionalNumber("cloud_cover", 0.0, 100.0), item.optionalNumber("precipitation", 0.0, 1000.0),
            features, item.optionalNumber("urban_ratio", 0.0, 1.0), item.getValue("tunnel").jsonPrimitive.boolean,
            item.optionalNumber("elevation_m", -500.0, 10000.0),
            item.optionalNumber("terrain_valley", 0.0, 1.0) != null, weather)
    }
    require(sections.zipWithNext().all { (a, b) -> a.range.endMeters <= b.range.startMeters + .02 })
    val spots = body.getValue("best_spots").jsonArray.also { require(it.size <= 4) }.map { value ->
        val item = value.jsonObject
        val range = item.range()
        val position = item.number("distance_m", range.startMeters, range.endMeters)
        require(!item.getValue("stopping_verified").jsonPrimitive.boolean)
        ScenicBestSpot(range, position, item.text("arrival_time"), item.text("category"),
            item.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }, item.text("confidence"))
    }
    val sampling = body["sampling_meters"]?.jsonPrimitive?.int ?: 0
    require(sampling in 0..20_000_000)
    return ScenicEnvironment(sections, spots, sampling,
        body["landscape_available"]?.jsonPrimitive?.boolean ?: false, body["warning"]?.jsonPrimitive?.content.orEmpty())
}
