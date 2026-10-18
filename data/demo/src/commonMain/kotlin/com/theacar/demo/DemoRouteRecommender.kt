package com.theacar.demo

import com.theacar.model.DriveMode
import com.theacar.model.Weather
import com.theacar.route.RouteIdea
import com.theacar.route.RouteRecommender
import com.theacar.route.RouteRequest

class DemoRouteRecommender : RouteRecommender {
    override suspend fun recommend(request: RouteRequest): RouteIdea {
        val coastIsNear = (request.coastDistanceKm ?: Double.MAX_VALUE) <= 5.0
        return when {
            coastIsNear && request.weather == Weather.CLEAR -> RouteIdea(
                title = "海沿いへ、少し遠回り",
                description = "視界が開ける海岸線をつないだ、信号の少ないルートです。",
                durationMinutes = 42,
                distanceKm = 27.4,
                tags = listOf("海", "夕景", "ゆったり"),
            )
            request.weather == Weather.RAIN -> RouteIdea(
                title = "雨音を楽しむ街路へ",
                description = "急なカーブを避け、灯りのきれいな大通りを巡ります。",
                durationMinutes = 35,
                distanceKm = 18.2,
                tags = listOf("雨", "夜景", "安心"),
            )
            request.mode == DriveMode.DATE -> RouteIdea(
                title = "景色のいい丘まで",
                description = "会話を邪魔しない穏やかな道で、小さな展望台へ向かいます。",
                durationMinutes = 48,
                distanceKm = 31.0,
                tags = listOf("景色", "会話", "寄り道"),
            )
            request.mode == DriveMode.GROUP -> RouteIdea(
                title = "みんなで選ぶ寄り道コース",
                description = "休憩しやすいスポットを挟みながら、賑やかな道を走ります。",
                durationMinutes = 55,
                distanceKm = 34.8,
                tags = listOf("グループ", "休憩", "発見"),
            )
            else -> RouteIdea(
                title = "風の向くまま、緑の道へ",
                description = "交通量を避け、今の空気に合う道を選びました。",
                durationMinutes = 38,
                distanceKm = 23.6,
                tags = listOf("緑", "静か", "気分転換"),
            )
        }
    }
}
