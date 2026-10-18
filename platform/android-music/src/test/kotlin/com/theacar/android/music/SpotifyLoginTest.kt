package com.theacar.android.music

import kotlin.test.*

class SpotifyLoginTest {
    @Test fun pkceMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyLogin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun noncesAreDistinctUrlSafeAndSufficientlyLong() {
        val first = SpotifyLogin.create(1_000)
        val second = SpotifyLogin.create(1_000)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first.verifier))
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first.state))
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.state, first.verifier)
    }
    @Test fun rejectsMissingWrongExpiredAndFutureState() {
        val pending = SpotifyLogin("expected", "verifier", 1_000)
        assertTrue(pending.accepts("expected", 601_000))
        assertFalse(pending.accepts("other", 1_000))
        assertFalse(pending.accepts(null, 1_000))
        assertFalse(pending.accepts("expected", 601_001))
        assertFalse(pending.accepts("expected", 999))
    }
}
