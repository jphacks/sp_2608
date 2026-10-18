package com.theacar.cabin

import com.theacar.model.Coordinate
import com.theacar.model.DriveContext
import com.theacar.model.Mood
import com.theacar.model.Weather
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class CabinPolicyTest {
    private val policy = CabinPolicy()
    private val context = DriveContext(
        "横浜", Coordinate(35.44, 139.63), Weather.CLEAR, Mood.CALM, 0f, 1.6, 17,
    )

    @Test
    fun windowAdviceUsesWeatherAndRequiresCoastalLocation() {
        assertIs<CabinSuggestion.OpenWindow>(policy.suggest(context))
        assertIs<CabinSuggestion.CloseWindow>(policy.suggest(context.copy(weather = Weather.RAIN)))
        assertNull(policy.suggest(context.copy(coastDistanceKm = null)))
        assertNull(policy.suggest(context.copy(coastDistanceKm = 8.0)))
    }
}
