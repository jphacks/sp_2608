package com.theacar.route

import kotlin.test.*

class ScenicOptionsTest {
    @Test fun customWeightsReplaceRatherThanMergeThePreset() {
        val options = ScenicOptions(ScenicPreference.SEA, customWeights = mapOf("lake" to .8, "forest" to .4, "sea" to 0.0))
        assertEquals(mapOf("lake" to .8, "forest" to .4), options.preferenceWeights)
        assertFalse("sea" in options.preferenceWeights)
        assertEquals(1.2, options.preferenceWeights.values.sum(), .000001)
    }

    @Test fun invalidOrEmptyCustomWeightsAndExperienceValuesAreRejected() {
        for (weights in listOf(emptyMap(), mapOf("sea" to 0.0), mapOf("sea" to -1.0),
            mapOf("sea" to 1.1), mapOf("sea" to Double.NaN), mapOf("unknown" to .5))) {
            assertFailsWith<IllegalArgumentException> { ScenicOptions(customWeights = weights) }
        }
        for (value in listOf(-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { ScenicOptions(passengerView = value) }
            assertFailsWith<IllegalArgumentException> { ScenicOptions(certifiedRoad = value) }
        }
    }

    @Test fun oneTapPresetClearsOnlyCustomLandscapeWeights() {
        val options = ScenicOptions(customWeights = mapOf("river" to 1.0), extraSeconds = 5400,
            nightCorrection = false, requireVerifiedWidth = true, allowUnpaved = false, roundTrip = true,
            variety = .8, passengerView = .6, certifiedRoad = .4)
        val preset = options.withPreset(ScenicPreference.FARMLAND)
        assertNull(preset.customWeights)
        assertEquals(ScenicPreference.FARMLAND.weights, preset.preferenceWeights)
        assertEquals(options.copy(preference = ScenicPreference.FARMLAND, customWeights = null), preset)
    }
}
