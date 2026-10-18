package com.theacar.valhalla

import com.theacar.model.Coordinate

internal fun decodePolyline6(encoded: String): List<Coordinate> {
    val points = mutableListOf<Coordinate>()
    var offset = 0
    var latitude = 0L
    var longitude = 0L
    fun component(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            require(offset < encoded.length && shift <= 30) { "Invalid polyline" }
            val part = encoded[offset++].code - 63
            require(part in 0..63)
            value = value or ((part and 31).toLong() shl shift)
            if (part < 32) break
            shift += 5
        }
        return if (value and 1L != 0L) (value shr 1).inv() else value shr 1
    }
    while (offset < encoded.length) {
        require(points.size < 200_000) { "Route geometry too large" }
        latitude += component()
        longitude += component()
        val lat = latitude / 1_000_000.0
        val lon = longitude / 1_000_000.0
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        points += Coordinate(lat, lon)
    }
    return points
}
