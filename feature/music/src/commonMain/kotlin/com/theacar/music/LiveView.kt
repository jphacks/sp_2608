package com.theacar.music

/**
 * 走行中の選曲の様子を Web のライブ表示（/live/{受付番号}）へ送るための値。
 *
 * 送るのは画面に出す値だけ。音声・歌詞・同乗者の名前・プレイリストの持ち主は含めない。
 * 位置と経路を含むので、利用者が自分でオンにしたときだけ送る（[LiveReporter]）。
 */

/** 推薦サービスが1回の評価で見ていたもの。景色の内訳・理由・欲しい曲調・候補の点数。 */
data class BgmEvaluation(
    val windowStartMeters: Double? = null,
    val windowEndMeters: Double? = null,
    /** 次の曲が流れる区間の景色の内訳（景観カテゴリ → 0〜1）。 */
    val shares: Map<String, Double> = emptyMap(),
    val reasons: List<String> = emptyList(),
    val target: MusicTarget? = null,
    /** 場面に合う歌詞の題材（題材 → 0〜1）。 */
    val themes: Map<String, Double> = emptyMap(),
    /** 題材の枠（場面の題材を歌った曲から選ぶ回）で選んだか。 */
    val themeSlot: Boolean = false,
    val candidates: List<BgmCandidateScore> = emptyList(),
)

/** 候補1曲の点数の内訳。 */
data class BgmCandidateScore(
    val trackId: String,
    val title: String,
    val artist: String,
    val score: Double,
    /** くじ引きで選ばれる確率。 */
    val probability: Double,
    /** 欲しい曲調との離れ具合。 */
    val distance: Double,
    val theme: String? = null,
    val themeMatch: Double = 0.0,
    /** 同乗者のリストにある曲（track）・アーティスト（artist）による加点。 */
    val preference: String? = null,
    val sameArtist: Boolean = false,
    val popularity: Double = 0.0,
    val confidencePenalty: Double = 0.0,
    /** 曲の6軸。推薦サービスが返したときだけ。 */
    val axes: MusicTarget? = null,
)

/** いつ、どちらの評価か。[decided] は次の曲として決めたとき、それ以外は途中の評価。 */
data class LiveEvaluation(
    val atMillis: Long,
    val decided: Boolean,
    val evaluation: BgmEvaluation,
    val chosen: RecommendedTrack? = null,
)

data class LiveSegment(val startMeters: Double, val endMeters: Double, val category: String)

/** 案内中の経路。[key] は経路が変わったら変わる値。[geometry] は (経度, 緯度) の並び。 */
data class LiveRoute(
    val key: String,
    val destination: String?,
    val totalMeters: Double,
    val positionMeters: Double,
    val segments: List<LiveSegment>,
    val geometry: List<Pair<Double, Double>>,
    val arrived: Boolean = false,
)

data class LiveCar(val latitude: Double, val longitude: Double, val speedKmh: Double, val heading: Double?)

data class LiveCabin(val state: CabinState, val speechRatio: Double, val speaking: Boolean)

enum class LivePlaybackKind(val code: String) { BGM("bgm"), REQUEST("request"), OTHER("other") }

data class LiveNowPlaying(
    val title: String,
    val artist: String,
    val positionMillis: Int,
    val durationMillis: Int,
    val playing: Boolean,
    val kind: LivePlaybackKind,
)

/** 景観BGMで流した曲。[scene] はその曲を決めたときの区間で一番多かった景色。 */
data class LivePlayed(
    val title: String,
    val artist: String,
    val message: String?,
    val theme: String?,
    val scene: String?,
    val startMeters: Double?,
    val endMeters: Double?,
)

data class LiveSnapshot(
    val atMillis: Long,
    val arrived: Boolean,
    val destination: String?,
    val car: LiveCar?,
    val routeKey: String,
    val positionMeters: Double,
    val totalMeters: Double,
    val segments: List<LiveSegment>,
    val cabin: LiveCabin?,
    val nowPlaying: LiveNowPlaying?,
    val evaluation: LiveEvaluation?,
    val played: List<LivePlayed>,
)

interface LiveSender {
    /** 最新の様子を送る。[geometry] は経路が変わったときだけ渡す。届いたら true。 */
    suspend fun send(code: String, snapshot: LiveSnapshot, geometry: List<Pair<Double, Double>>?): Boolean
}
