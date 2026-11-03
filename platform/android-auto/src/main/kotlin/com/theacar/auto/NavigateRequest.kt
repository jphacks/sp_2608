package com.theacar.auto

import com.theacar.model.Coordinate
import java.net.URLDecoder

/**
 * ほかのアプリや音声アシスタントから届く「ここへ行きたい」（`CarContext.ACTION_NAVIGATE`）。
 * 中身は `geo:` の URI で、座標か、`q=` の検索の文字が入る。
 */
internal sealed interface NavigateRequest {
    data class At(val coordinate: Coordinate, val label: String?) : NavigateRequest
    data class Search(val text: String) : NavigateRequest
}

/** `geo:41.77,140.72`、`geo:0,0?q=函館駅`、`geo:0,0?q=41.77,140.72(函館駅)` を読む。読めなければ null。 */
internal fun navigateRequest(uri: String): NavigateRequest? {
    if (!uri.startsWith("geo:")) return null
    val body = uri.removePrefix("geo:")
    val query = body.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.removePrefix("q=")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
    if (query != null) {
        COORDINATE.matchEntire(query)?.let { match ->
            return at(match.groupValues[1], match.groupValues[2], match.groupValues[3].trim().ifEmpty { null })
        }
        return NavigateRequest.Search(query)
    }
    val parts = body.substringBefore('?').substringBefore(';').split(',')
    return at(parts.getOrNull(0), parts.getOrNull(1), null)
}

private val COORDINATE = Regex("""\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*""")

private fun at(latitude: String?, longitude: String?, label: String?): NavigateRequest? {
    val lat = latitude?.trim()?.toDoubleOrNull() ?: return null
    val lon = longitude?.trim()?.toDoubleOrNull() ?: return null
    // geo:0,0 は「座標なし」の意味で使われる
    if (lat == 0.0 && lon == 0.0) return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return NavigateRequest.At(Coordinate(lat, lon), label)
}
