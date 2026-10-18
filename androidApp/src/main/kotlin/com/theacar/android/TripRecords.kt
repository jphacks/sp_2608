package com.theacar.android

import com.theacar.dashboard.TripRecord
import com.theacar.memory.TripMemorySummary
import com.theacar.memory.distanceKm
import com.theacar.memory.durationParts
import com.theacar.memory.dateLines
import com.theacar.memory.destinationShortName
import com.theacar.memory.memberLines

internal fun TripMemorySummary.toTripRecord(): TripRecord {
    val (year, monthDay) = dateLines(arrivedAtEpochMillis, timeZoneId)
    val (hours, minutes) = durationParts(durationSeconds)
    return TripRecord(id, "$year-${monthDay.replaceFirst('.', '-')}", "@${placeName ?: destinationShortName(destinationTitle)}",
        distanceKm(distanceMeters), hours * 60 + minutes, memberLines(members), photoVersion)
}
