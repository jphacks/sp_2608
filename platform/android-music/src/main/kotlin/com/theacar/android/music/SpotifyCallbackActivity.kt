package com.theacar.android.music

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent.data
        intent = Intent()
        (application as AndroidMusicOwner).spotifyRuntime.finishLogin(callback)
        startActivity(Intent(this, SpotifyMusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
