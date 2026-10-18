package com.theacar.valhalla

import io.ktor.http.Url

/**
 * 合言葉をこの接続先へ付けてよいか。空なら付けないので true。
 * ヘッダに入れられない文字を含むもの、HTTPS でも loopback でもない先へは送らない。
 */
fun canSendBearerToken(endpoint: String, token: String): Boolean {
    if (token.isEmpty()) return true
    if (token.any { it.isWhitespace() || it.isISOControl() }) return false
    val url = Url(endpoint)
    return url.protocol.name == "https" || url.host in setOf("127.0.0.1", "localhost", "::1")
}
