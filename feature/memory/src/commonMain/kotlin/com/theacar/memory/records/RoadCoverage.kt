package com.theacar.memory.records

import com.theacar.location.LocationFix
import com.theacar.location.LocationUse
import com.theacar.model.Coordinate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 道の形。台帳の形状部分ごとの線（上下線や枝道で、いくつかに分かれていることがある） */
data class RoadShape(val id: String, val parts: List<List<Coordinate>>)

/** export_road_registry.py --format geojson の出力（MultiLineString、座標は [経度, 緯度]）から道の形を読む */
fun parseRoadShapes(geojson: String): List<RoadShape> =
    Json.parseToJsonElement(geojson).jsonObject.getValue("features").jsonArray.map { feature ->
        val id = feature.jsonObject.getValue("properties").jsonObject.getValue("id").jsonPrimitive.content
        val lines = feature.jsonObject.getValue("geometry").jsonObject.getValue("coordinates") as JsonArray
        RoadShape(id, lines.map { line ->
            line.jsonArray.map { point ->
                val (lon, lat) = point.jsonArray.map { it.jsonPrimitive.double }
                Coordinate(lat, lon)
            }
        })
    }

/**
 * 1回の案内の中で、名のある道のどれだけを走ったかを数え、走り切った道を知らせる。
 *
 * 道の線を [SAMPLE_METERS] おきの点に分け、GPS の位置から [REACH_METERS] 以内の点を「通った」とする。
 * 通った点が [threshold] の割合を超えたら、その道のレコードが手に入る。
 * 位置は案内の軌跡と同じ条件（誤差 40m 以内・10 秒以内）で使い、位置が飛んだときは数えない。
 * 位置と位置の間は、離れすぎていなければ直線でつないで数える（トンネルや GPS の更新が遅いとき）。
 */
class RoadCoverage(shapes: List<RoadShape>) {
    private class Road(val id: String, val samples: List<Coordinate>, val threshold: Double) {
        val covered = BooleanArray(samples.size)
        var count = 0
        var unlocked = false
    }

    private val roads = shapes.mapNotNull { shape ->
        val samples = shape.parts.flatMap(::resample)
        if (samples.isEmpty()) null else Road(shape.id, samples, threshold(shape.parts.sumOf(::lengthMeters)))
    }

    // 点を緯度経度の升目に分けておき、位置の近くの升目だけを調べる
    private val grid = HashMap<Long, MutableList<Pair<Road, Int>>>().apply {
        roads.forEach { road -> road.samples.forEachIndexed { i, p -> getOrPut(cell(p)) { mutableListOf() } += road to i } }
    }
    private var last: LocationFix? = null

    /** 新しい案内が始まったら呼ぶ。数えた点を消す */
    fun reset() {
        roads.forEach { it.covered.fill(false); it.count = 0; it.unlocked = false }
        last = null
    }

    /** 道ごとに、走った割合（0〜1） */
    fun progress(): Map<String, Double> = roads.associate { it.id to it.count.toDouble() / it.samples.size }

    /**
     * 位置を1つ足す。返すのは、この位置で近くを通っていた道の id と、この位置で初めて走り切った道の id。
     * [elapsedNowMillis] は端末の経過時間（[LocationFix.elapsedRealtimeMillis] と同じ時計）。
     */
    fun onLocation(fix: LocationFix, elapsedNowMillis: Long): CoverageUpdate {
        val point = fix.coordinate
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            fix.accuracyMeters > LocationUse.GUIDANCE.maximumAccuracyMeters ||
            elapsedNowMillis - fix.elapsedRealtimeMillis !in 0..LocationUse.GUIDANCE.maximumAgeMillis) return CoverageUpdate.NONE
        val previous = last
        val path = if (previous == null) listOf(point) else {
            val elapsed = fix.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
            if (elapsed <= 0) return CoverageUpdate.NONE
            val step = distanceMeters(previous.coordinate, point)
            // 位置が飛んだ（車では出ない速さ）ときは、その位置を捨てる
            if (step / (elapsed / 1_000.0) > MAX_SPEED) return CoverageUpdate.NONE
            if (elapsed <= MAX_GAP_MILLIS && step <= MAX_JOIN_METERS) between(previous.coordinate, point) else listOf(point)
        }
        last = fix
        val near = mutableSetOf<String>()
        val finished = mutableListOf<String>()
        path.forEach { p -> touch(p, near) }
        roads.filter { !it.unlocked && it.count >= ceil(it.threshold * it.samples.size) }.forEach {
            it.unlocked = true
            finished += it.id
        }
        return CoverageUpdate(near, finished)
    }

    private fun touch(p: Coordinate, near: MutableSet<String>) {
        val row = floor(p.latitude / CELL_DEGREES).toLong()
        val column = floor(p.longitude / CELL_DEGREES).toLong()
        for (dr in -1L..1L) for (dc in -1L..1L) {
            grid[key(row + dr, column + dc)]?.forEach { (road, index) ->
                if (distanceMeters(p, road.samples[index]) <= REACH_METERS) {
                    near += road.id
                    if (!road.covered[index]) { road.covered[index] = true; road.count++ }
                }
            }
        }
    }

    companion object {
        const val SAMPLE_METERS = 50.0
        const val REACH_METERS = 35.0
        /** この長さより短い道（橋や湖畔の短い道）は、ほとんど全部を走らないと手に入らない */
        const val SHORT_ROAD_METERS = 5_000.0
        fun threshold(lengthMeters: Double) = if (lengthMeters < SHORT_ROAD_METERS) .9 else .7

        private const val MAX_SPEED = 70.0
        private const val MAX_GAP_MILLIS = 30_000L
        private const val MAX_JOIN_METERS = 1_500.0
        private const val CELL_DEGREES = .005

        private fun cell(p: Coordinate) = key(floor(p.latitude / CELL_DEGREES).toLong(), floor(p.longitude / CELL_DEGREES).toLong())
        private fun key(row: Long, column: Long) = row * 1_000_000 + column

        /** 2つの位置の間を、点を数え漏らさない細かさ（20m おき）でつなぐ */
        private fun between(a: Coordinate, b: Coordinate): List<Coordinate> {
            val steps = ceil(distanceMeters(a, b) / 20.0).toInt().coerceAtLeast(1)
            return (1..steps).map { i ->
                val t = i.toDouble() / steps
                Coordinate(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
            }
        }

        private fun lengthMeters(line: List<Coordinate>) = line.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

        /** 線を [SAMPLE_METERS] おきの点にする。始点を必ず含める */
        internal fun resample(line: List<Coordinate>): List<Coordinate> {
            if (line.isEmpty()) return emptyList()
            val result = mutableListOf(line.first())
            var carried = 0.0
            line.zipWithNext().forEach { (a, b) ->
                val length = distanceMeters(a, b)
                if (length <= 0) return@forEach
                var at = SAMPLE_METERS - carried
                while (at <= length) {
                    val t = at / length
                    result += Coordinate(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
                    at += SAMPLE_METERS
                }
                carried = length - (at - SAMPLE_METERS)
            }
            return result
        }

        internal fun distanceMeters(a: Coordinate, b: Coordinate): Double {
            val radians = PI / 180
            val latitude = (b.latitude - a.latitude) * radians
            val longitude = (b.longitude - a.longitude) * radians
            val square = sin(latitude / 2).pow(2) + cos(a.latitude * radians) * cos(b.latitude * radians) * sin(longitude / 2).pow(2)
            return 6_371_000 * 2 * asin(sqrt(square.coerceIn(0.0, 1.0)))
        }
    }
}

/** [RoadCoverage.onLocation] の結果。[near] は今まさに走っている道、[unlocked] は今走り切った道 */
data class CoverageUpdate(val near: Set<String>, val unlocked: List<String>) {
    companion object { val NONE = CoverageUpdate(emptySet(), emptyList()) }
}
