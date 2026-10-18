package com.theacar.demo

import com.theacar.model.DriveContext
import com.theacar.model.DriveMode
import com.theacar.model.Mood
import com.theacar.model.Scenery
import com.theacar.model.UnusualSound
import com.theacar.model.Weather
import com.theacar.music.MusicRecommender
import com.theacar.music.TrackKind
import com.theacar.music.TrackSuggestion

class DemoMusicRecommender : MusicRecommender {
    override fun recommend(mode: DriveMode, context: DriveContext): TrackSuggestion = when {
        context.unusualSound == UnusualSound.FLATULENCE -> TrackSuggestion(
            "Brass Escape", "TheaCar Radio", "ちょっとした音を陽気なブラスで包みます", TrackKind.COMEDY,
        )
        context.weather == Weather.RAIN -> TrackSuggestion(
            "Rain on Glass", "Mellow Avenue", "雨のテンポと夜の路面に合わせて", TrackKind.RAINY,
        )
        context.scenery == Scenery.COASTAL || (context.coastDistanceKm ?: Double.MAX_VALUE) <= 5.0 -> TrackSuggestion(
            "Salt & Sun", "Blue Horizon", "海辺の景色に合わせて", TrackKind.COASTAL,
        )
        mode == DriveMode.GROUP || context.mood == Mood.ENERGETIC -> TrackSuggestion(
            "All Together", "Weekend Club", "車内の盛り上がりに合わせて", TrackKind.PARTY,
        )
        context.localHour >= 18 -> TrackSuggestion(
            "Neon Byway", "Night Signals", "夜の街並みに合うシティポップ", TrackKind.CITY_POP,
        )
        else -> TrackSuggestion(
            "Open Roads", "Paper Lanterns", "今の穏やかな空気に合わせて", TrackKind.ACOUSTIC,
        )
    }
}
