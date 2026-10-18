package com.theacar.model

enum class DriveMode(val label: String, val subtitle: String) {
    GOALLESS("ゴールレス", "寄り道を楽しむ"),
    DATE("デート", "会話と景色を大切に"),
    GROUP("みんなで", "車内の一体感を上げる"),
    SOLO("ひとり", "自分のペースで整える"),
}

enum class Weather(val label: String) {
    CLEAR("晴れ"), CLOUDY("くもり"), RAIN("雨"), SNOW("雪")
}

enum class Mood(val label: String) {
    CALM("穏やか"), BRIGHT("楽しい"), ENERGETIC("盛り上がり"), QUIET("静か")
}

data class Coordinate(val latitude: Double, val longitude: Double)

data class DriveContext(
    val locationName: String,
    val coordinate: Coordinate,
    val weather: Weather,
    val mood: Mood,
    val conversationLevel: Float,
    val coastDistanceKm: Double?,
    val localHour: Int,
    val unusualSound: UnusualSound = UnusualSound.NONE,
    val scenery: Scenery = Scenery.UNKNOWN,
)

enum class UnusualSound { NONE, FLATULENCE }

enum class Scenery { UNKNOWN, COASTAL, FOREST, URBAN }
