package com.theacar.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.theacar.android.navigation.AndroidNavigationOwner
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * 経路を探すときの出発時刻を、今日（days で何日後か）のある時刻に固定する口。Debug ビルドにしか無い。
 *
 * ```
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.DepartureTimeReceiver --es at 14:00
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.DepartureTimeReceiver --es at 14:00 --ei days 1
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.DepartureTimeReceiver --ez clear true
 * ```
 */
class DepartureTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = (context.applicationContext as? AndroidNavigationOwner)?.navigationRuntime ?: return
        val at = intent.getStringExtra("at")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        runtime.departureOverride = if (intent.getBooleanExtra("clear", false) || at == null) null
            else OffsetDateTime.now().plusDays(intent.getIntExtra("days", 0).toLong()).with(at).withSecond(0).withNano(0)
        Toast.makeText(context, runtime.departureOverride?.let { "出発時刻を ${it.toLocalDate()} ${it.toLocalTime()} にしました" } ?: "出発時刻を今に戻しました",
            Toast.LENGTH_SHORT).show()
    }
}
