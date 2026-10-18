package com.theacar.android.music

import kotlin.test.*

class SpotifyAuthSettingsTest {
    @Test fun clientIdRequiresExactly32AlphanumericCharacters() {
        assertTrue(SpotifyAuthSettings.isClientId("0123456789abcdefABCDEF0123456789"))
        assertFalse(SpotifyAuthSettings.isClientId("a".repeat(31)))
        assertFalse(SpotifyAuthSettings.isClientId("a".repeat(33)))
        assertFalse(SpotifyAuthSettings.isClientId("a".repeat(31) + "-"))
        assertFalse(SpotifyAuthSettings.isClientId(" " + "a".repeat(32)))
    }
}
