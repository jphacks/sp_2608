package com.theacar.route

data class ScenicRouteDifference(
    val drivingSeconds: Int,
    val distanceMeters: Int,
    val stopSeconds: Int,
    val seaMeters: Int,
    val forestMeters: Int,
    val continuousMeters: Int,
    val load: Double,
    val repeatedMeters: Int?,
)

fun RouteAlternative.scenicSummary(): ScenicRouteReference? = scenic?.let { metrics ->
    ScenicRouteReference(plan.durationSeconds, plan.distanceMeters, explanation?.stopSeconds ?: 0,
        metrics, explanation?.timeline.orEmpty(), explanation?.load ?: 0.0,
        explanation?.repeatedMeters, explanation?.recommendedMeters.orEmpty(), explanation?.environment)
}

fun ScenicRouteReference.differenceFrom(reference: ScenicRouteReference) = ScenicRouteDifference(
    drivingSeconds - reference.drivingSeconds,
    distanceMeters - reference.distanceMeters,
    stopSeconds - reference.stopSeconds,
    metrics.seaMeters - reference.metrics.seaMeters,
    metrics.forestMeters - reference.metrics.forestMeters,
    metrics.continuousMeters - reference.metrics.continuousMeters,
    load - reference.load,
    repeatedMeters?.let { meters -> reference.repeatedMeters?.let { meters - it } },
)

fun ScenicRouteReference.recommendedRoads(): List<ScenicRecommendedRoad> =
    timeline.flatMap { it.recommendedRoads }.distinctBy { it.id }.sortedBy { it.rank.ordinal }
