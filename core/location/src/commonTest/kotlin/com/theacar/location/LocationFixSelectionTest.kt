package com.theacar.location

import com.theacar.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationFixSelectionTest {
    private fun fix(time: Long, accuracy: Float = 10f) = LocationFix(Coordinate(43.061, 141.354), accuracy, time)

    @Test fun acceptsRecentCachedLocation() {
        assertTrue(fix(50_000).shouldReplace(null, 100_000))
    }

    @Test fun rejectsOldAndFutureLocations() {
        assertFalse(fix(1).shouldReplace(null, 130_000))
        assertFalse(fix(101).shouldReplace(null, 100))
    }

    @Test fun rejectsOutOfOrderUpdates() {
        assertFalse(fix(90).shouldReplace(fix(95), 100))
        assertFalse(fix(95).shouldReplace(fix(95), 100))
    }

    @Test fun keepsFreshPreciseLocationOverCoarseNetworkLocation() {
        assertFalse(fix(99_000, 500f).shouldReplace(fix(95_000), 100_000))
        assertTrue(fix(99_000).shouldReplace(fix(95_000), 100_000))
    }

    @Test fun acceptsNetworkLocationWhenPreciseLocationIsStale() {
        assertTrue(fix(99_000, 500f).shouldReplace(fix(80_000), 100_000))
    }
}
