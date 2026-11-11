package com.theacar.android.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

// 地図の文字は、アプリに同梱した Noto Sans JP（assets/glyphs）で描く。端末の書体だと漢字が中国風に見えるため
private const val GLYPHS = "asset://glyphs/{fontstack}/{range}.pbf"
private const val REGULAR = "NotoSansJP-Regular"
private const val BOLD = "NotoSansJP-Bold"

/** 配信元のスタイルを読み、文字の読み込み先と書体を同梱の Noto Sans JP に替える */
internal suspend fun tabiStyleJson(url: String): String = withContext(Dispatchers.IO) {
    val style = JSONObject(URL(url).readText())
    style.put("glyphs", GLYPHS)
    val layers = style.getJSONArray("layers")
    for (i in 0 until layers.length()) {
        val layer = layers.getJSONObject(i)
        val layout = layer.optJSONObject("layout") ?: continue
        if (!layout.has("text-font")) continue
        layout.put("text-font", JSONArray(listOf(if (layer.getString("id").startsWith("label_")) BOLD else REGULAR)))
    }
    style.toString()
}
