package com.theacar.android.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.theacar.android.navigation.AndroidNavigationOwner
import com.theacar.android.navigation.AndroidNavigationRuntime
import com.theacar.location.LocationFix
import com.theacar.model.Coordinate
import com.theacar.navigation.NavigationPhase
import com.theacar.route.Destination
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ドライブシミュレータ（`simulator/`）で走った位置を、現在地として受け取る口。Debug ビルドにしか無い。
 *
 * シミュレータの中継（TCP 8788）へつなぎ、1行ずつ届く位置を案内へ差し込む。
 * USB かワイヤレスデバッグでつないだスマホは `adb reverse` で、同じ Wi-Fi のスマホは PC の IP アドレスでつなぐ。
 *
 * ```
 * adb reverse tcp:8788 tcp:8788
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.SimulatorLinkReceiver -a com.theacar.debug.SIMULATOR
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.SimulatorLinkReceiver -a com.theacar.debug.SIMULATOR --es host 192.168.0.10
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.SimulatorLinkReceiver -a com.theacar.debug.SIMULATOR --ez stop true
 * ```
 */
class SimulatorLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = (context.applicationContext as? AndroidNavigationOwner)?.navigationRuntime ?: return
        val app = context.applicationContext
        if (intent.getBooleanExtra("stop", false)) {
            SimulatorLink.forget(app)
            SimulatorLink.stop(runtime)
            Toast.makeText(app, "シミュレータとの連動を止めました", Toast.LENGTH_SHORT).show()
            return
        }
        val host = intent.getStringExtra("host") ?: "127.0.0.1"
        val port = intent.getIntExtra("port", 8788)
        SimulatorLink.remember(app, host, port)
        SimulatorLink.start(app, runtime, host, port)
        Toast.makeText(app, "シミュレータ $host:$port へつなぎます", Toast.LENGTH_SHORT).show()
    }
}

/**
 * アプリが起動したとき、前に連動していたらつなぎ直す。
 * アプリが落ちて開き直されるたびに adb で放送し直さなくてよいように。Debug ビルドにしか無い。
 * ContentProvider は Application より先に作られるので、Application の準備が済んでから（Main の次の回で）つなぐ。
 */
class SimulatorLinkStarter : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        Handler(Looper.getMainLooper()).post {
            SimulatorLink.resume(app)
            // スマホにネットがなく、USB の線で Mac の中継（simulator/tools/phone-proxy.mjs）を使っているとき。
            // MapLibre は端末が「つながっていない」と見ると地図を取りに行かないので、つながっている扱いにする
            val routing = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.theacar.VALHALLA_URL").orEmpty()
            if (routing.startsWith("http://127.0.0.1")) {
                MapLibre.getInstance(app)
                MapLibre.setConnected(true)
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** つなぎ先は1つだけ持つ。放送のたびに増やすと、前のつなぎ先からの位置と混ざる。 */
internal object SimulatorLink {
    @Volatile private var thread: Thread? = null
    @Volatile private var socket: Socket? = null
    private val main = Handler(Looper.getMainLooper())

    fun remember(context: Context, host: String, port: Int) {
        prefs(context).edit().putString("host", host).putInt("port", port).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 前に連動していたら、同じつなぎ先へつなぎ直す。 */
    fun resume(context: Context) {
        val host = prefs(context).getString("host", null) ?: return
        val runtime = (context as? AndroidNavigationOwner)?.navigationRuntime ?: return
        start(context, runtime, host, prefs(context).getInt("port", 8788))
    }

    private fun prefs(context: Context) = context.getSharedPreferences("simulator-link", Context.MODE_PRIVATE)

    fun start(context: Context, runtime: AndroidNavigationRuntime, host: String, port: Int) {
        stop(runtime)
        thread = Thread({ run(context, runtime, host, port) }, "simulator-link").apply {
            isDaemon = true
            start()
        }
    }

    fun stop(runtime: AndroidNavigationRuntime) {
        thread?.interrupt()
        thread = null
        // 読み取りで待っている間は interrupt では止まらないので、つなぎ先も閉じる
        runCatching { socket?.close() }
        socket = null
        main.post { runtime.locations.simulate(null) }
    }

    private fun run(context: Context, runtime: AndroidNavigationRuntime, host: String, port: Int) {
        val me = Thread.currentThread()
        // シミュレータを開き直しても続くよう、切れたらつなぎ直す
        while (!me.isInterrupted) {
            try {
                Socket().use { socket ->
                    this.socket = socket
                    socket.connect(InetSocketAddress(host, port), 3_000)
                    notify(context, "シミュレータにつながりました")
                    val reader = socket.getInputStream().bufferedReader()
                    while (!me.isInterrupted) {
                        val line = reader.readLine() ?: break
                        val destination = parseSimulatorDestination(line)
                        if (destination != null) {
                            main.post { if (thread === me) SimulatorRoute.go(runtime, destination) }
                            continue
                        }
                        val fix = parseSimulatorLine(line, SystemClock.elapsedRealtime())
                        // 差し込みは案内と同じスレッドで。DriveSimulator も Main で差し込んでいる
                        main.post { if (thread === me) runtime.locations.simulate(fix) }
                    }
                }
            } catch (_: IOException) {
                // まだ開いていない・切れた。少し待ってつなぎ直す
            } catch (e: RuntimeException) {
                // 想定していない失敗でも連動を黙って止めず、記録してつなぎ直す
                Log.w("SimulatorLink", "シミュレータとの連動で失敗しました", e)
            }
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun notify(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * シミュレータで道を選んだとき、その道の目的地で案内を始める。デモの最中にスマホで検索して打たなくてよいように。
 * 前の案内が残っていれば止めてから、ルートを引いて（車の画面と同じ、景観の指定なし）すぐ案内を始める。
 */
internal object SimulatorRoute {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun go(runtime: AndroidNavigationRuntime, destination: Destination) {
        val session = runtime.session
        val now = session.state.value
        // 中継につなぎ直すたびに同じ目的地が届く。同じ目的地へ案内中なら引き直さない
        if (now.phase == NavigationPhase.GUIDING && now.route?.destination?.coordinate == destination.coordinate) return
        job?.cancel()
        job = scope.launch {
            withTimeoutOrNull(180_000) {
                if (session.state.value.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)) {
                    session.stop()
                    session.state.first { it.phase == NavigationPhase.IDLE }
                }
                session.preview(destination)
                val ready = session.state.first {
                    (it.phase == NavigationPhase.PREVIEW && !it.previewImproving) || it.phase == NavigationPhase.IDLE
                }
                if (ready.phase == NavigationPhase.PREVIEW) session.start()
                else Log.w("SimulatorLink", "${destination.title} へのルートを引けませんでした: ${ready.failure}")
            }
        }
    }
}

/** `DEST <緯度> <経度> <名前>`（形は `simulator/bridge/link.ts` の formatDestination）。それ以外の行は null。 */
internal fun parseSimulatorDestination(line: String): Destination? {
    val parts = line.trim().split(' ', limit = 4)
    if (parts.size != 4 || parts[0] != "DEST") return null
    val lat = parts[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
    val lon = parts[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
    val name = parts[3].trim().ifEmpty { return null }
    return Destination("simulator:$lat,$lon", name, Coordinate(lat, lon))
}

/**
 * 中継から届く1行を読む（形は `simulator/bridge/link.ts` の formatFix）。
 * `FIX <緯度> <経度> <速さ m/s> <向き 度>` は位置、`STOP` は実機の測位へ戻す（null）。
 * 読めない行も null にして、実機の測位へ戻す。おかしな位置で案内を続けないように。
 */
internal fun parseSimulatorLine(line: String, elapsedRealtimeMillis: Long): LocationFix? {
    val parts = line.trim().split(' ')
    if (parts.size != 5 || parts[0] != "FIX") return null
    val lat = parts[1].toDoubleOrNull() ?: return null
    val lon = parts[2].toDoubleOrNull() ?: return null
    val speed = parts[3].toFloatOrNull() ?: return null
    val bearing = parts[4].toFloatOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || !speed.isFinite() || speed < 0 || bearing !in 0f..360f) return null
    return LocationFix(Coordinate(lat, lon), 5f, elapsedRealtimeMillis, speed, bearing)
}
