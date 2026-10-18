package com.theacar.music

import com.theacar.environment.LocalWeather

/** 次の約1曲（210秒）の過半を占める景観を採用し、短い区間での曲調の揺れを抑える。 */
class SceneryMusicPolicy {
    fun theme(weather: LocalWeather, cues: List<MusicSceneryCue>, offset: Int): DriveMusicTheme? {
        val windowEnd = offset + 210
        val category = cues.filter { it.confidence == null || it.confidence >= .25 }
            .groupBy { it.category }.mapValues { (_, blocks) ->
                // 同じカテゴリの重複区間を二重に数えない。
                var end = offset
                var covered = 0
                for (block in blocks.sortedBy { it.startSeconds }) {
                    val start = maxOf(offset, end, block.startSeconds)
                    val stop = minOf(windowEnd, block.endSeconds)
                    if (stop > start) { covered += stop - start; end = stop }
                }
                covered
            }.maxByOrNull { it.value }?.takeIf { it.value > 105 }?.key ?: return null
        val night = !weather.isDay
        return when (category) {
            "sea" -> DriveMusicTheme("海沿いの、開放感", "海沿いの区間に合わせ、広がりのある音楽を。", if (night) "chill soul" else "summer soft pop")
            "forest", "valley" -> DriveMusicTheme("森と山間の、アコースティック", "森林・谷の区間に合わせ、落ち着いた音色を。", if (night) "ambient acoustic" else "acoustic folk")
            "urban", "townscape" -> DriveMusicTheme("街並みと、グルーヴ", "市街地の区間に合わせ、心地よいリズムを。", if (night) "night city pop" else "mellow soul")
            "lake", "river", "inland_water" -> DriveMusicTheme("水辺の、ひと息", "湖や川の区間に合わせ、穏やかな響きを。", "acoustic chill")
            "farmland", "open_nature" -> DriveMusicTheme("広い景色と、軽やかさ", "田園や開けた自然に合わせ、軽やかな曲を。", if (night) "ambient folk" else "indie folk")
            else -> null
        }
    }
}
