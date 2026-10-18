package com.theacar.demo

import com.theacar.environment.DriveContextSource
import com.theacar.model.Coordinate
import com.theacar.model.DriveContext
import com.theacar.model.Mood
import com.theacar.model.Weather
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DemoDriveContextSource : DriveContextSource {
    private val mutableContext = MutableStateFlow(
        DriveContext(
            locationName = "横浜・山下町",
            coordinate = Coordinate(35.4437, 139.6380),
            weather = Weather.CLEAR,
            mood = Mood.BRIGHT,
            conversationLevel = 0.24f,
            coastDistanceKm = 1.6,
            localHour = 17,
        )
    )
    override val context: StateFlow<DriveContext> = mutableContext.asStateFlow()

}
