package com.theacar.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.theacar.location.LocationFix
import com.theacar.location.LocationSource
import com.theacar.location.LocationState
import com.theacar.location.LocationStatus
import com.theacar.location.shouldReplace
import com.theacar.model.Coordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidLocationSource(private val context: Context, private val scope: CoroutineScope) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val owners = mutableSetOf<Any>()
    private val mutableLocation = MutableStateFlow(LocationState())
    override val location = mutableLocation.asStateFlow()
    private var monitoring: Job? = null
    private var listening = false
    private var simulated = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(value: Location) {
            accept(value)
        }
        override fun onProviderDisabled(provider: String) { refreshStatus() }
        override fun onProviderEnabled(provider: String) { refreshStatus() }
        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun acquire(owner: Any) {
        owners.add(owner)
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            mutableLocation.value = LocationState(LocationStatus.PERMISSION_REQUIRED)
            return
        }
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in manager.allProviders }
            if (listening) {
                providers.forEach { manager.getLastKnownLocation(it)?.let(::accept) }
                refreshStatus()
                return
            }
            for (provider in providers) {
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                manager.getLastKnownLocation(provider)?.let(::accept)
            }
            listening = true
            refreshStatus()
            monitoring = scope.launch { while (true) { delay(1_000); refreshStatus() } }
        } catch (_: SecurityException) {
            mutableLocation.value = LocationState(LocationStatus.PERMISSION_REQUIRED)
        } catch (_: IllegalArgumentException) {
            mutableLocation.value = LocationState(LocationStatus.DISABLED)
        }
    }

    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.isNotEmpty()) return
        manager.removeUpdates(listener)
        listening = false
        monitoring?.cancel()
        monitoring = null
        refreshStatus()
    }

    /**
     * 検証用に位置を差し込む。差し込んでいる間は実機の測位を取り込まない。
     * null を渡すと測位に戻す。呼び出し側は Debug ビルドにしか無い。
     */
    fun simulate(fix: LocationFix?) {
        simulated = fix != null
        if (fix == null) {
            // 差し込んだ位置を残すと、次の測位が届くまでそこに居続ける。
            // 屋内では測位が来ないこともあるので、捨ててから測り直す。
            mutableLocation.value = LocationState()
            prime()
            return
        }
        mutableLocation.value = LocationState(LocationStatus.AVAILABLE, fix)
    }

    /** 直近の測位を取り込んで状態を見直す。 */
    private fun prime() {
        runCatching {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@runCatching
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in manager.allProviders }
                .forEach { provider -> manager.getLastKnownLocation(provider)?.let(::accept) }
        }
        refreshStatus()
    }

    private fun accept(value: Location) {
        if (simulated) return
        if (!value.hasAccuracy() || !value.accuracy.isFinite() || value.accuracy < 0) return
        if (!value.latitude.isFinite() || value.latitude !in -90.0..90.0 ||
            !value.longitude.isFinite() || value.longitude !in -180.0..180.0) return
        val elapsed = value.elapsedRealtimeNanos / 1_000_000
        if (elapsed < 0) return
        val fix = LocationFix(Coordinate(value.latitude, value.longitude), value.accuracy, elapsed,
            value.speed.takeIf { value.hasSpeed() && it.isFinite() && it >= 0 },
            value.bearing.takeIf { value.hasBearing() && it.isFinite() && it in 0f..360f })
        if (!fix.shouldReplace(location.value.fix, SystemClock.elapsedRealtime())) return
        mutableLocation.value = location.value.copy(fix = fix)
        refreshStatus()
    }

    private fun refreshStatus() {
        if (simulated) return
        val previous = location.value
        val status = when {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> LocationStatus.PERMISSION_REQUIRED
            !listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .any { it in manager.allProviders && manager.isProviderEnabled(it) } -> LocationStatus.DISABLED
            previous.fix == null -> LocationStatus.WAITING
            SystemClock.elapsedRealtime() - requireNotNull(previous.fix).elapsedRealtimeMillis > 10_000 -> LocationStatus.STALE
            else -> LocationStatus.AVAILABLE
        }
        mutableLocation.value = previous.copy(status = status)
    }
}
