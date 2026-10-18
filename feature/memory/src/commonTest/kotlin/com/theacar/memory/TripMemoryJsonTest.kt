package com.theacar.memory

import com.theacar.model.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals

class TripMemoryJsonTest {
    @Test fun roundTripsAllFields() {
        val memory = TripMemory(
            id = "8f2c", startedAtEpochMillis = 1_000, arrivedAtEpochMillis = 17_201_000, distanceMeters = 357_600, durationSeconds = 17_200,
            destinationTitle = "八雲", destinationCoordinate = Coordinate(42.25, 140.26), placeName = "北海道 八雲町",
            geometry = listOf(Coordinate(41.77, 140.73), Coordinate(42.25, 140.26)), members = listOf("Honoka", "Masa"),
            photoStyle = PhotoStyle.POSTER, photoLocation = Coordinate(42.0, 140.5), timeZoneId = "Asia/Tokyo",
            galleryUri = "content://media/external/images/media/42",
        )
        assertEquals(memory, parseTripMemory(memory.toJson()))
    }

    @Test fun roundTripsOptionalNulls() {
        val memory = TripMemory("1", 0, 0, 0, 0, "x", Coordinate(0.0, 0.0))
        assertEquals(memory, parseTripMemory(memory.toJson()))
    }
}
