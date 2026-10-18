package com.theacar.android.navigation

import android.content.Context
import com.theacar.model.Coordinate
import com.theacar.route.Destination
import java.net.URLDecoder
import java.net.URLEncoder

const val RECENT_DESTINATION_LIMIT = 5

fun List<Destination>.withRecent(destination: Destination, limit: Int = RECENT_DESTINATION_LIMIT): List<Destination> =
    (listOf(destination) + filterNot { it.id == destination.id }).take(limit)

fun encodeRecentDestinations(destinations: List<Destination>): String = destinations.joinToString("\n") {
    listOf(it.id, it.title, it.coordinate.latitude.toString(), it.coordinate.longitude.toString())
        .joinToString(",") { field -> URLEncoder.encode(field, "UTF-8") }
}

fun decodeRecentDestinations(text: String, limit: Int = RECENT_DESTINATION_LIMIT): List<Destination> = text.lines()
    .mapNotNull { line ->
        runCatching {
            val (id, title, lat, lon) = line.split(",").map { URLDecoder.decode(it, "UTF-8") }
            Destination(id, title, Coordinate(lat.toDouble(), lon.toDouble()))
        }.getOrNull()
    }
    .fold(emptyList<Destination>()) { list, item -> if (list.any { it.id == item.id }) list else list + item }
    .take(limit)

internal class RecentDestinationStore(context: Context) {
    private val preferences = context.getSharedPreferences("recent-destinations", Context.MODE_PRIVATE)
    fun read(): List<Destination> = decodeRecentDestinations(preferences.getString("items", "").orEmpty())
    fun write(destinations: List<Destination>) { preferences.edit().putString("items", encodeRecentDestinations(destinations)).apply() }
}
