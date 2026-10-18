package com.theacar.music

import com.theacar.environment.LocalWeather
import com.theacar.model.Weather

data class DriveMusicTheme(val title: String, val reason: String, val query: String)

class DriveMusicPolicy {
    fun theme(weather: LocalWeather): DriveMusicTheme = when {
        weather.weather == Weather.SNOW -> DriveMusicTheme("雪景色と、ピアノ", "雪の景色に、落ち着いた響きを。", "winter piano")
        weather.weather == Weather.RAIN -> DriveMusicTheme("雨の日の、チル", "雨のドライブに、ゆったりしたリズムを。", "rain chill")
        !weather.isDay -> DriveMusicTheme("夜の、シティポップ", "現在地は夜。街の灯りに合う音楽を。", "night city pop")
        weather.elevationMeters >= 600 -> DriveMusicTheme("高原の、アコースティック", "周辺の標高は約${weather.elevationMeters.toInt()}m。軽やかな音色を。", "acoustic folk")
        weather.weather == Weather.CLEAR -> DriveMusicTheme("晴れの日の、ドライブ", "晴れた昼のドライブに、明るい曲を。", "sunny drive")
        else -> DriveMusicTheme("くもり空と、インディー", "やわらかな空の色に、穏やかな音楽を。", "indie mellow")
    }
}
