package com.theacar.music

/**
 * 景観と車内の雰囲気から次の1曲を選ぶ外部サービスの契約。
 * feature/route やナビの型には依存しない。景観タイムラインの変換は feature/drive が担当する。
 */

enum class CabinState(val code: String) { LIVELY("lively"), NORMAL("normal"), QUIET("quiet"), RESTING("resting") }

enum class RecommendationMode(val code: String) { DISCOVER("discover"), BLEND("blend"), PLAYLIST("playlist") }

data class MusicMember(val id: String, val name: String) {
    init { require(id.isNotBlank() && name.isNotBlank()) }
}

/** 同乗者のプレイリストの曲と、その曲を持っている人。 */
data class PlaylistEntry(val trackId: String, val owners: List<String>) {
    init { require(trackId.isNotBlank()) }
}

data class PlayedTrack(val trackId: String, val ownerId: String? = null, val artist: String? = null) {
    init { require(trackId.isNotBlank()) }
}

/**
 * 景観タイムラインの1区間。固有名と物理道路IDは持たない。
 * 座標を含まなくても経路が特定できるため、外部へ送る値から除いている。
 */
data class SceneryBlock(
    val startMeters: Double,
    val endMeters: Double,
    val category: String,
    val confidence: Double? = null,
    val openness: Double? = null,
    val visibilityVerified: Boolean? = null,
    val recommendedRank: String? = null,
    val arrivalTime: String? = null,
) {
    init {
        require(startMeters.isFinite() && startMeters >= 0)
        require(endMeters.isFinite() && endMeters > startMeters)
        require(category.isNotBlank())
        require(confidence == null || (confidence.isFinite() && confidence in 0.0..1.0))
        require(openness == null || (openness.isFinite() && openness in 0.0..1.0))
    }
}

/**
 * 現在位置と、その先の景観。区間は現在位置の周辺だけに絞って渡す。
 * [routeId] は景観エンジンのルート ID。分かるときは推薦サービスがサーバー側でタイムラインを取るので、
 * [blocks] は送らずに済む。サーバー側で取れなかったときのために [blocks] も持っておく。
 */
data class SceneryRoute(
    val positionMeters: Double,
    val blocks: List<SceneryBlock>,
    val revealStartMeters: List<Double> = emptyList(),
    val nextDurationMillis: Int? = null,
    val routeId: String? = null,
) {
    init {
        require(positionMeters.isFinite() && positionMeters >= 0)
        require(blocks.isNotEmpty())
        require(nextDurationMillis == null || nextDurationMillis > 0)
        require(routeId == null || ROUTE_ID.matches(routeId))
    }

    companion object {
        /** 景観エンジンが発行するルート ID の形。 */
        val ROUTE_ID = Regex("route_[a-f0-9]{32}")
    }
}

/** 景観エンジンが区間単位では返さない運転条件。アプリ側で分かるぶんだけ送る。 */
data class DrivingConditions(val highLoad: Boolean = false, val curvy: Boolean = false)

/** 選曲のターゲット（6軸）。次の要求へ渡して曲調の急変を抑える。 */
data class MusicTarget(
    val energy: Double,
    val valence: Double,
    val tempo: Double,
    val acoustic: Double,
    val spacious: Double,
    val vocal: Double,
) {
    init { require(listOf(energy, valence, tempo, acoustic, spacious, vocal).all { it.isFinite() && it in 0.0..1.0 }) }
}

data class RecommendedTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val durationMillis: Int? = null,
    val ownerId: String? = null,
    val message: String? = null,
) {
    init {
        require(trackId.isNotBlank())
        require(durationMillis == null || durationMillis > 0)
    }
}

data class BgmRecommendationRequest(
    val members: List<MusicMember>,
    val pool: List<PlaylistEntry> = emptyList(),
    val mode: RecommendationMode = RecommendationMode.BLEND,
    val cabin: CabinState = CabinState.NORMAL,
    val scenery: SceneryRoute? = null,
    val conditions: DrivingConditions = DrivingConditions(),
    val history: List<PlayedTrack> = emptyList(),
    val recentTrackIds: List<String> = emptyList(),
    val previousTarget: MusicTarget? = null,
    val topK: Int = 5,
) {
    init { require(members.isNotEmpty() && topK in 1..20) }
}

data class BgmRecommendation(
    val chosen: RecommendedTrack?,
    val candidates: List<RecommendedTrack> = emptyList(),
    val volume: Float = .6f,
    val target: MusicTarget? = null,
    val note: String? = null,
    val missing: List<String> = emptyList(),
    val coverage: Double? = null,
    val revealEtaSeconds: Int? = null,
) {
    init {
        require(volume.isFinite() && volume in 0f..1f)
        require(coverage == null || (coverage.isFinite() && coverage in 0.0..1.0))
        require(revealEtaSeconds == null || revealEtaSeconds >= 0)
    }
}

enum class TrackLinkStatus { FOUND, NOT_FOUND, PENDING, NO_ISRC }

data class TrackLink(val trackId: String, val status: TrackLinkStatus, val uri: String? = null) {
    init { require(trackId.isNotBlank()) }
}

data class TrackLinks(val links: List<TrackLink>, val retryAfterSeconds: Int? = null) {
    init { require(retryAfterSeconds == null || retryAfterSeconds >= 0) }
}

interface BgmRecommendationService {
    suspend fun recommend(request: BgmRecommendationRequest): BgmRecommendation

    /** 再生直前の照合。1回 [MAX_LINKS] 件まで。 */
    suspend fun link(trackIds: List<String>): TrackLinks

    companion object { const val MAX_LINKS = 5 }
}
