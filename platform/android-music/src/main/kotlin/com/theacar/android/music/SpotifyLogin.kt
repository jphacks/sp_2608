package com.theacar.android.music

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class SpotifyLogin(val state: String, val verifier: String, val createdAtMillis: Long) {
    fun accepts(returnedState: String?, nowMillis: Long): Boolean = returnedState != null &&
        nowMillis - createdAtMillis in 0..600_000 &&
        MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), returnedState.toByteArray(Charsets.UTF_8))

    companion object {
        fun create(nowMillis: Long): SpotifyLogin {
            val random = SecureRandom()
            fun nonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            return SpotifyLogin(nonce(), nonce(), nowMillis)
        }

        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
