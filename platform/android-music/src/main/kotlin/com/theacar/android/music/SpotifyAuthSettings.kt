package com.theacar.android.music

internal object SpotifyAuthSettings {
    const val REDIRECT_URI = "com.theacar.android.spotify://oauth/callback"
    const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
    private val clientIdPattern = Regex("[A-Za-z0-9]{32}")

    fun isClientId(value: String) = clientIdPattern.matches(value)
}
