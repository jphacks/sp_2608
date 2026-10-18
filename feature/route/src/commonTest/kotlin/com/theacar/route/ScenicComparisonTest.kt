package com.theacar.route

import kotlin.test.*

class ScenicComparisonTest {
    private val reference = ScenicRouteReference(1200, 10000, 0,
        ScenicMetrics(0, 2000, 3000, 1000, .9), emptyList(), .3, 500)

    @Test fun differencesRetainTheirSignAndIncludeStopsAndRoadRepetition() {
        val other = reference.copy(drivingSeconds = 1500, distanceMeters = 14000, stopSeconds = 600,
            metrics = ScenicMetrics(300, 6000, 1000, 4000, .95), load = .2, repeatedMeters = 200)
        val delta = other.differenceFrom(reference)
        assertEquals(300, delta.drivingSeconds)
        assertEquals(4000, delta.distanceMeters)
        assertEquals(600, delta.stopSeconds)
        assertEquals(4000, delta.seaMeters)
        assertEquals(-2000, delta.forestMeters)
        assertEquals(3000, delta.continuousMeters)
        assertEquals(-.1, delta.load, .00001)
        assertEquals(-300, delta.repeatedMeters)
    }

    @Test fun missingRepeatMeasurementIsNotTreatedAsZero() {
        assertNull(reference.copy(repeatedMeters = null).differenceFrom(reference).repeatedMeters)
        assertNull(reference.differenceFrom(reference.copy(repeatedMeters = null)).repeatedMeters)
    }

    @Test fun routeCountLimitIsExplicitAndBounded() {
        assertEquals(6, ScenicOptions().maxRoutes)
        assertEquals(1, ScenicOptions(maxRoutes = 1).maxRoutes)
        assertFailsWith<IllegalArgumentException> { ScenicOptions(maxRoutes = 0) }
        assertFailsWith<IllegalArgumentException> { ScenicOptions(maxRoutes = 7) }
    }
}
