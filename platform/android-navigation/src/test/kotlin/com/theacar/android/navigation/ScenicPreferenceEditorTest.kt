package com.theacar.android.navigation

import androidx.compose.runtime.saveable.SaverScope
import com.theacar.route.*
import kotlin.test.*

class ScenicPreferenceEditorTest {
    @Test fun weightSummaryUsesRelativeWeightsWithoutRequiringSumOne() {
        val settings = ScenicOptions(customWeights = mapOf("sea" to 1.0, "forest" to 1.0))
        assertEquals("海 50% · 森林 50%", scenicWeightSummary(settings))
    }

    @Test fun customWeightSaverKeepsEverySliderIncludingZeroAndDrafts() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val weights = mapOf("lake" to .75, "river" to .25, "sea" to 0.0)
        val saved = with(ScenicWeightSaver) { scope.save(weights) }!!
        val restored = ScenicWeightSaver.restore(saved)!!
        assertEquals(.75, restored["lake"])
        assertEquals(.25, restored["river"])
        assertEquals(0.0, restored["sea"])
        assertEquals(weights, restored)
        val empty = with(ScenicWeightSaver) { scope.save(emptyMap()) }!!
        assertEquals(emptyMap(), ScenicWeightSaver.restore(empty))
    }
}
