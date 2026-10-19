package com.theacar.android.navigation

import android.content.Context
import android.content.pm.PackageManager
import java.net.URI

data class NavigationConfiguration(val routingUrl: String, val mapStyleUrl: String, val geocodingUrl: String,
    val routingToken: String = "", val builtInRoutingUrl: String = routingUrl) {
    val configured: Boolean get() = routingUrl.isNotBlank()

    companion object {
        // Tunnel の URL が変わるたびに再ビルド・再インストールすると、Android Auto がアプリを一覧から外し直す。
        // そのため経路サーバーの URL は端末で上書きでき、上書きした値をビルド時の値より優先する。
        private const val PREFERENCES = "routing-server"
        private const val URL_KEY = "url"

        private fun validUrl(value: String): String? = value.trim().takeIf {
            it.isEmpty() || runCatching { URI(it).let { uri -> uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null } }.getOrDefault(false)
        }

        /** 空文字でビルド時の URL に戻す。形式が不正なら保存せず false を返す。 */
        fun saveRoutingUrl(context: Context, value: String): Boolean {
            val url = validUrl(value)?.trimEnd('/') ?: return false
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(URL_KEY, url).commit()
            return true
        }

        fun read(context: Context): NavigationConfiguration {
            @Suppress("DEPRECATION")
            val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
            fun url(name: String): String = validUrl(metadata?.getString("com.theacar.$name").orEmpty()).orEmpty()
            // 共有の合言葉をビルド時に埋め込む。端末で保存した値があればそちらを優先する。
            val token = metadata?.getString("com.theacar.ROUTING_TOKEN").orEmpty().trim()
                .takeIf { value -> value.none { it.isWhitespace() || it.isISOControl() } }.orEmpty()
            val builtIn = url("VALHALLA_URL")
            val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(URL_KEY, null)
                ?.let(::validUrl).orEmpty()
            return NavigationConfiguration(saved.ifEmpty { builtIn }, url("MAP_STYLE_URL"), url("GEOCODING_URL"), token, builtIn)
        }
    }
}
