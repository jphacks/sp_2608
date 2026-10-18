package com.theacar.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.theacar.android.navigation.AndroidNavigationOwner

/**
 * 机の上で模擬走行を出し入れするための口。Debug ビルドにしか無い。
 *
 * 受信口は必ず `-n` で名指しする。アクション名だけの放送は、Android 8 以降の
 * バックグラウンド制限でマニフェストの受信口に届かない。
 *
 * ```
 * adb shell am broadcast -n com.theacar.android/.debug.DemoDriveReceiver -a com.theacar.debug.DRIVE --ei kmh 60
 * adb shell am broadcast -n com.theacar.android/.debug.DemoDriveReceiver -a com.theacar.debug.DRIVE --ez stop true
 * ```
 */
class DemoDriveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = (context.applicationContext as? AndroidNavigationOwner)?.navigationRuntime ?: return
        val simulator = simulator(runtime)
        if (intent.getBooleanExtra("stop", false)) {
            simulator.stop()
            Toast.makeText(context, "模擬走行を止めました", Toast.LENGTH_SHORT).show()
            return
        }
        val kmh = intent.getIntExtra("kmh", 60)
        val started = simulator.start(kmh.toDouble())
        Toast.makeText(context,
            if (started) "模擬走行 ${kmh}km/h" else "案内中の経路がありません",
            Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 同じ走行を1つだけ持つ。放送が来るたびに作り直すと、前の走行が残ってしまう。 */
        private var shared: Pair<Any, DriveSimulator>? = null

        fun simulator(runtime: com.theacar.android.navigation.AndroidNavigationRuntime): DriveSimulator {
            shared?.takeIf { it.first === runtime }?.let { return it.second }
            val created = DriveSimulator(runtime)
            shared = runtime to created
            return created
        }
    }
}
