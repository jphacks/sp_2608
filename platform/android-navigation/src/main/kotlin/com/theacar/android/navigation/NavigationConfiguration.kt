package com.theacar.android.navigation

import android.content.Context
import android.content.pm.PackageManager
import java.net.URI

data class NavigationConfiguration(val routingUrl: String, val mapStyleUrl: String, val geocodingUrl: String,
    val routingToken: String = "") {
    val configured: Boolean get() = routingUrl.isNotBlank()

    companion object {
        fun read(context: Context): NavigationConfiguration {
            @Suppress("DEPRECATION")
            val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
            fun url(name: String): String = metadata?.getString("com.theacar.$name").orEmpty().trim().takeIf {
                it.isEmpty() || runCatching { URI(it).let { uri -> uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null } }.getOrDefault(false)
            }.orEmpty()
            // 共有の合言葉をビルド時に埋め込む。端末で保存した値があればそちらを優先する。
            val token = metadata?.getString("com.theacar.ROUTING_TOKEN").orEmpty().trim()
                .takeIf { value -> value.none { it.isWhitespace() || it.isISOControl() } }.orEmpty()
            return NavigationConfiguration(url("VALHALLA_URL"), url("MAP_STYLE_URL"), url("GEOCODING_URL"), token)
        }
    }
}
