package com.theacar.android.debug

import android.os.SystemClock
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.location.LocationFix
import com.theacar.model.Coordinate
import com.theacar.navigation.NavigationPhase
import com.theacar.route.RouteGeometryIndex
import com.theacar.route.ScenicRange
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 案内中の経路の上を走っていることにして、位置を作り続ける。机の上では車が動かず、
 * 速度で倍率が変わることを確かめられないため。測位の代わりに位置を差し込むだけで、
 * 案内そのものは実機と同じ道筋で動く。Debug ビルドにしか入らない。
 */
internal class DriveSimulator(private val runtime: AndroidNavigationRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 案内中でないときは何もせず false を返す。 */
    fun start(topSpeedKmh: Double): Boolean {
        stop()
        val begin = runtime.session.state.value
        if (begin.phase != NavigationPhase.GUIDING) return false
        val route = begin.route ?: return false
        if (route.geometry.size < 2) return false
        val index = RouteGeometryIndex(route)
        val total = route.distanceMeters.toDouble()
        val top = (topSpeedKmh / 3.6).coerceAtLeast(1.0)
        job = scope.launch {
            var travelled = 0.0
            var elapsed = 0.0
            try {
                while (isActive && travelled < total) {
                    // 案内が終わった・経路が入れ替わったあとも位置を作り続けると、次の検索まで狂う
                    val now = runtime.session.state.value
                    if (now.phase != NavigationPhase.GUIDING) break
                    if (now.sessionId != begin.sessionId || now.route?.id != route.id) break
                    val speed = speedAt(elapsed, top)
                    travelled = (travelled + speed * TICK_SECONDS).coerceAtMost(total)
                    elapsed += TICK_SECONDS
                    val here = pointAt(index, travelled, total) ?: break
                    val ahead = pointAt(index, travelled + LOOK_AHEAD_METERS, total) ?: here
                    runtime.locations.simulate(LocationFix(here, 5f, SystemClock.elapsedRealtime(),
                        speed.toFloat(), bearing(here, ahead)))
                    delay((TICK_SECONDS * 1_000).toLong())
                }
            } finally {
                // 差し込んだままだと、そこに居続けてしまう。打ち切られたときも必ず測位へ返す
                runtime.locations.simulate(null)
            }
        }
        return true
    }

    /** 実機の測位に戻す。 */
    fun stop() {
        job?.cancel()
        job = null
        runtime.locations.simulate(null)
    }

    /** 一定速だと倍率も傾きも変わらず、確かめたいものが見えない。加速と上げ下げを入れる。 */
    private fun speedAt(seconds: Double, top: Double): Double {
        val ramp = (seconds / RAMP_SECONDS).coerceIn(0.0, 1.0)
        return top * ramp * (.7 + .3 * sin(seconds / 12.0))
    }

    private fun pointAt(index: RouteGeometryIndex, meters: Double, total: Double): Coordinate? {
        val start = meters.coerceIn(0.0, total)
        return index.slice(ScenicRange(start, (start + 1.0).coerceAtMost(total).coerceAtLeast(start))).firstOrNull()
    }

    private fun bearing(from: Coordinate, to: Coordinate): Float {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
        val degrees = Math.toDegrees(atan2(y, x))
        return ((degrees + 360) % 360).toFloat()
    }

    private companion object {
        /** 位置を作る間隔。実機の測位と同じ速さにしている。 */
        const val TICK_SECONDS = 1.0
        const val RAMP_SECONDS = 15.0
        const val LOOK_AHEAD_METERS = 20.0
    }
}
