package com.theacar.memory

import com.theacar.model.Coordinate
import com.theacar.location.LocationFix
import com.theacar.location.LocationUse
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.RoutePlan
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Web 側が受け付ける経路の点数の上限。 */
const val GEOMETRY_POINT_LIMIT = 20_000

/** 軌跡に点を足す最小間隔（m）。これより近い位置は捨てる。 */
const val TRACK_POINT_SPACING_METERS = 15.0

private fun distanceMeters(a: Coordinate, b: Coordinate): Double {
    val radians = PI / 180
    val latitude = (b.latitude - a.latitude) * radians
    val longitude = (b.longitude - a.longitude) * radians
    val square = sin(latitude / 2).pow(2) + cos(a.latitude * radians) * cos(b.latitude * radians) * sin(longitude / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(square.coerceIn(0.0, 1.0)))
}

/** [limit] を超える経路を等間隔に間引く。始点と終点は必ず残す。 */
fun thinGeometry(geometry: List<Coordinate>, limit: Int = GEOMETRY_POINT_LIMIT): List<Coordinate> {
    require(limit >= 2)
    if (geometry.size <= limit) return geometry
    val last = geometry.size - 1
    return List(limit) { i -> geometry[(i.toLong() * last / (limit - 1)).toInt()] }
}

/**
 * ナビの状態と現在地を順に受け取り、案内開始から到着までを 1 件の [TripMemory] にまとめる。
 * 距離はナビの進捗から積算し、軌跡が欠けている場合は開始時の計画経路を描く。
 * 到着した瞬間に 1 回だけ記録を返し、到着前に案内をやめたら何も返さない。
 */
@OptIn(ExperimentalUuidApi::class)
class TripMemoryDraftBuilder(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timeZoneId: () -> String = { TimeZone.currentSystemDefault().id },
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private var sessionId = 0L
    private var startedAt = 0L
    private var route: RoutePlan? = null
    private var initialRoute: RoutePlan? = null
    private var progressKey: String? = null
    private var coveredOnRoute = 0
    private var traveledMeters = 0L
    private var hasProgress = false
    private var guiding = false
    private val track = mutableListOf<Coordinate>()
    private var trackMeters = 0.0
    private var lastSample: LocationFix? = null
    private var firstTrackAt: Long? = null
    private var lastTrackAt: Long? = null
    private var continuousTrack = true

    fun onState(state: NavigationState): TripMemory? {
        when (state.phase) {
            NavigationPhase.GUIDING -> {
                // 引き直しでは開始時刻を変えない。別のセッションになったときだけ取り直す
                if (state.sessionId != sessionId) {
                    reset()
                    sessionId = state.sessionId
                    startedAt = now()
                }
                guiding = true
                route = state.route ?: route
                if (initialRoute == null) initialRoute = route
                updateProgress(state)
                return null
            }
            NavigationPhase.ARRIVED -> {
                guiding = false
                val plan = (state.route ?: route) ?: return null
                if (state.sessionId != sessionId || sessionId == 0L) return null
                val arrivedAt = now()
                val began = startedAt
                if (hasProgress) updateProgress(state)
                val original = initialRoute ?: plan
                val distance = if (hasProgress) traveledMeters.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else original.distanceMeters
                val complete = continuousTrack && track.size >= 2 && distance > 0 &&
                    trackMeters / distance in 0.8..1.2 &&
                    (firstTrackAt ?: Long.MAX_VALUE) - began <= MAX_TRACK_GAP_MILLIS &&
                    arrivedAt - (lastTrackAt ?: 0) <= MAX_TRACK_GAP_MILLIS
                val geometry = if (complete) track.toList() else original.geometry
                reset()
                return TripMemory(
                    id = newId(),
                    startedAtEpochMillis = began,
                    arrivedAtEpochMillis = arrivedAt,
                    distanceMeters = distance,
                    durationSeconds = ((arrivedAt - began) / 1000).toInt().coerceAtLeast(0),
                    destinationTitle = plan.destination.title,
                    destinationCoordinate = plan.destination.coordinate,
                    geometry = thinGeometry(geometry),
                    timeZoneId = timeZoneId(),
                )
            }
            NavigationPhase.STARTING, NavigationPhase.STOPPING -> { guiding = false; return null }
            else -> { reset(); return null }
        }
    }

    fun onLocation(fix: LocationFix, elapsedNowMillis: Long) {
        if (!guiding) return
        if (!fix.coordinate.latitude.isFinite() || fix.coordinate.latitude !in -90.0..90.0 ||
            !fix.coordinate.longitude.isFinite() || fix.coordinate.longitude !in -180.0..180.0) {
            continuousTrack = false
            return
        }
        if (fix.accuracyMeters > LocationUse.GUIDANCE.maximumAccuracyMeters ||
            elapsedNowMillis - fix.elapsedRealtimeMillis !in 0..LocationUse.GUIDANCE.maximumAgeMillis) {
            continuousTrack = false
            return
        }
        val previous = lastSample
        if (previous != null) {
            val elapsed = fix.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
            if (elapsed <= 0) return
            if (elapsed > MAX_TRACK_GAP_MILLIS) continuousTrack = false
            if (distanceMeters(previous.coordinate, fix.coordinate) / (elapsed / 1_000.0) > MAX_TRACK_SPEED) {
                continuousTrack = false
                return
            }
        }
        lastSample = fix
        if (firstTrackAt == null) firstTrackAt = now()
        lastTrackAt = now()
        val coordinate = fix.coordinate
        val last = track.lastOrNull()
        if (last == null) { track += coordinate; return }
        val step = distanceMeters(last, coordinate)
        if (step < TRACK_POINT_SPACING_METERS) return
        track += coordinate
        trackMeters += step
        if (track.size > GEOMETRY_POINT_LIMIT * 2) {
            val compact = thinGeometry(track)
            track.clear()
            track.addAll(compact)
        }
    }

    private fun updateProgress(state: NavigationState) {
        val plan = state.route ?: return
        val remaining = state.progress?.remainingMeters ?: if (state.phase == NavigationPhase.ARRIVED) 0 else return
        val key = "${plan.id}:${state.routeRevision}"
        if (key != progressKey) { progressKey = key; coveredOnRoute = 0 }
        val covered = (plan.distanceMeters - remaining).coerceIn(0, plan.distanceMeters)
        traveledMeters += (covered - coveredOnRoute).coerceAtLeast(0)
        coveredOnRoute = maxOf(coveredOnRoute, covered)
        hasProgress = true
    }

    private fun reset() {
        sessionId = 0
        startedAt = 0
        route = null
        initialRoute = null
        guiding = false
        track.clear()
        trackMeters = 0.0
        lastSample = null
        firstTrackAt = null
        lastTrackAt = null
        continuousTrack = true
        progressKey = null
        coveredOnRoute = 0
        traveledMeters = 0
        hasProgress = false
    }

    private companion object {
        const val MAX_TRACK_GAP_MILLIS = 30_000L
        const val MAX_TRACK_SPEED = 70.0
    }
}
