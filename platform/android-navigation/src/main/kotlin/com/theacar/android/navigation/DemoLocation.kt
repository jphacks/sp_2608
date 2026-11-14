package com.theacar.android.navigation

import android.os.SystemClock
import com.theacar.location.LocationFix
import com.theacar.model.Coordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 北海道の外でデモするために、現在地を函館駅に置く。地図画面の DRIVE を5回続けて押すと出し入れする。
 * 案内は10秒より古い位置を使わないので、置いている間は1秒ごとに差し込み直す。
 */
class DemoLocation(private val runtime: AndroidNavigationRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 出し入れして、置いている状態なら true を返す。 */
    fun toggle(): Boolean {
        job?.let {
            it.cancel()
            job = null
            runtime.locations.simulate(null)
            return false
        }
        job = scope.launch {
            var here = HAKODATE_STATION
            while (isActive) {
                // 自動走行が位置を作っている間は任せ、止まったらその場所に居続ける
                if (runtime.simulator.running.value) {
                    runtime.locations.location.value.fix?.let { here = it.coordinate }
                } else {
                    runtime.locations.simulate(LocationFix(here, 5f, SystemClock.elapsedRealtime(), 0f, null))
                }
                delay(1_000)
            }
        }
        return true
    }

    private companion object {
        val HAKODATE_STATION = Coordinate(41.773436, 140.726639)
    }
}
