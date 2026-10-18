package com.theacar.cabin

import com.theacar.model.DriveContext
import com.theacar.model.Weather

sealed interface CabinSuggestion {
    data class OpenWindow(val reason: String) : CabinSuggestion
    data class CloseWindow(val reason: String) : CabinSuggestion
}


class CabinPolicy {
    fun suggest(context: DriveContext): CabinSuggestion? {
        val distance = context.coastDistanceKm ?: return null
        return when {
            distance <= 2.0 && context.weather == Weather.CLEAR ->
                CabinSuggestion.OpenWindow("海まで $distance km。潮風を感じられそうです。")
            distance <= 2.0 && context.weather == Weather.RAIN ->
                CabinSuggestion.CloseWindow("海が近づきました。雨の吹き込みに注意してください。")
            else -> null
        }
    }
}
