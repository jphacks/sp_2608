package com.theacar.route

enum class ScenicPreference(val label: String, val weights: Map<String, Double>) {
    SEA("海沿い", mapOf("sea" to 1.0)),
    FOREST("森林", mapOf("forest" to 1.0)),
    BALANCED("自然を楽しむ", mapOf("sea" to .25, "forest" to .25, "lake" to .15,
        "river" to .1, "valley" to .1, "open_nature" to .1, "farmland" to .05)),
    WATER("湖・川", mapOf("lake" to .5, "river" to .4, "valley" to .1)),
    VALLEY("渓谷", mapOf("valley" to .8, "river" to .2)),
    FARMLAND("田園", mapOf("farmland" to .8, "open_nature" to .2)),
    URBAN("市街地", mapOf("urban" to 1.0)),
}

data class ScenicOptions(
    val preference: ScenicPreference = ScenicPreference.BALANCED,
    val extraSeconds: Int = 900,
    val totalSeconds: Int = 7200,
    val desiredStops: Int = 0,
    val dwellSeconds: Int = 600,
    val variety: Double = .5,
    val requireVerifiedWidth: Boolean = false,
    val allowUnpaved: Boolean = true,
    val roundTrip: Boolean = false,
    val seed: Int = 0,
    val maxRoutes: Int = 6,
    val nightCorrection: Boolean = true,
    val customWeights: Map<String, Double>? = null,
    val passengerView: Double = 0.0,
    val certifiedRoad: Double = 0.0,
) {
    val preferenceWeights: Map<String, Double>
        get() = (customWeights ?: preference.weights).filterValues { it > 0 }

    fun withPreset(value: ScenicPreference) = copy(preference = value, customWeights = null)

    init {
        require(extraSeconds in 0..21600 && totalSeconds in 60..86400)
        require(desiredStops in 0..3 && dwellSeconds in 60..7200 && seed >= 0)
        require(variety.isFinite() && variety in 0.0..1.0)
        require(maxRoutes in 1..6)
        require(passengerView.isFinite() && passengerView in 0.0..1.0)
        require(certifiedRoad.isFinite() && certifiedRoad in 0.0..1.0)
        customWeights?.let { weights ->
            require(weights.isNotEmpty() && weights.keys.all { it in ScenicLandscape.entries.map { item -> item.code } })
            require(weights.values.all { it.isFinite() && it in 0.0..1.0 } && weights.values.any { it > 0 })
        }
    }
}

enum class ScenicLandscape(val code: String, val label: String) {
    SEA("sea", "海"), FOREST("forest", "森林"), LAKE("lake", "湖・池"), RIVER("river", "川"),
    VALLEY("valley", "渓谷"), OPEN_NATURE("open_nature", "開けた自然"), FARMLAND("farmland", "田園"), URBAN("urban", "市街地"),
}

data class ScenicMetrics(
    val extraSeconds: Int,
    val seaMeters: Int,
    val forestMeters: Int,
    val continuousMeters: Int,
    val coverage: Double,
    val categoryMeters: Map<String, Int> = emptyMap(),
) {
    init {
        require(extraSeconds >= 0 && seaMeters >= 0 && forestMeters >= 0 && continuousMeters >= 0)
        require(coverage.isFinite() && coverage in 0.0..1.0)
        require(categoryMeters.values.all { it >= 0 })
    }
}

data class ScenicRange(val startMeters: Double, val endMeters: Double) {
    init { require(startMeters.isFinite() && endMeters.isFinite() && startMeters >= 0 && endMeters >= startMeters) }
}

data class ScenicReason(val id: String, val template: String, val text: String,
    val evidenceIds: List<String>, val ranges: List<ScenicRange>) {
    companion object { const val SEA_REVEAL = "forest_to_sea_reveal" }
}
enum class ScenicRoadRank(val code: String, val label: String) {
    SIGNATURE("signature", "秀逸な道"), PANORAMA("panorama", "絶景ロード"),
    RECOMMENDED("recommended", "おすすめロード"), SCENIC("scenic", "景観ロード"),
}

data class ScenicRecommendedRoad(val id: String, val name: String, val rank: ScenicRoadRank, val sourceIds: List<String>)
data class ScenicTimelineBlock(val category: String, val range: ScenicRange, val confidence: Double,
    val recommendedRoads: List<ScenicRecommendedRoad> = emptyList(),
    val openness: Double? = null, val visibilityVerified: Boolean = false, val arrivalTime: String? = null) {
    init { require(openness == null || (openness.isFinite() && openness in 0.0..1.0)) }

    /** 認定の格付けのうち最上位。区間に無ければ null。宣言順が上位から並んでいる。 */
    val topRank: ScenicRoadRank? get() = recommendedRoads.minByOrNull { it.rank.ordinal }?.rank
}
data class ScenicSource(val id: String, val url: String, val license: String, val version: String)
data class RouteExplanation(
    val role: String,
    val snapshotId: String,
    val revision: Int,
    val totalSeconds: Int,
    val stopSeconds: Int,
    val reasons: List<ScenicReason>,
    val timeline: List<ScenicTimelineBlock>,
    val warnings: List<String>,
    val sources: List<ScenicSource>,
    val searchLimited: Boolean,
    val baselineSeconds: Int,
    val load: Double,
    val comparisons: List<String> = emptyList(),
    val reference: ScenicRouteReference? = null,
    val repeatedMeters: Int? = null,
    val recommendedMeters: Map<ScenicRoadRank, Int> = emptyMap(),
    val environment: ScenicEnvironment? = null,
) {
    init {
        require(snapshotId.isNotBlank() && revision > 0 && totalSeconds >= 0 && stopSeconds >= 0 && baselineSeconds >= 0)
        require(load.isFinite() && load in 0.0..1.0)
    }
}

data class ScenicRouteReference(
    val drivingSeconds: Int,
    val distanceMeters: Int,
    val stopSeconds: Int,
    val metrics: ScenicMetrics,
    val timeline: List<ScenicTimelineBlock>,
    val load: Double,
    val repeatedMeters: Int? = null,
    val recommendedMeters: Map<ScenicRoadRank, Int> = emptyMap(),
    val environment: ScenicEnvironment? = null,
)

data class RouteAlternative(val plan: RoutePlan, val scenic: ScenicMetrics? = null, val explanation: RouteExplanation? = null)

interface RouteAlternativesCalculator : RouteCalculator {
    suspend fun alternatives(query: RouteQuery): List<RouteAlternative>
    suspend fun alternatives(query: RouteQuery, onUpdate: suspend (List<RouteAlternative>) -> Unit): List<RouteAlternative> =
        alternatives(query)
}
