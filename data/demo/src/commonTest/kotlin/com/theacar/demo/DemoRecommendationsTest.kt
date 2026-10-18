package com.theacar.demo

import com.theacar.model.Coordinate
import com.theacar.model.DriveContext
import com.theacar.model.DriveMode
import com.theacar.model.Mood
import com.theacar.model.UnusualSound
import com.theacar.model.Weather
import com.theacar.music.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoRecommendationsTest {
    @Test
    fun soundCoverTakesPriorityOverWeatherAndCoast() {
        val context = DriveContext(
            "横浜", Coordinate(35.44, 139.63), Weather.RAIN, Mood.CALM,
            0.1f, 1.6, 17, UnusualSound.FLATULENCE,
        )
        assertEquals(
            TrackKind.COMEDY,
            DemoMusicRecommender().recommend(DriveMode.DATE, context).kind,
        )
    }
}
