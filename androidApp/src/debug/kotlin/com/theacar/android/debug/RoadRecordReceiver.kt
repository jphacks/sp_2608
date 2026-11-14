package com.theacar.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.theacar.android.TheaCarApplication
import com.theacar.android.memory.RecordCelebrationActivity
import com.theacar.memory.records.RecordSong
import com.theacar.memory.records.RoadRecords
import com.theacar.memory.records.roadRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 走らずにレコードの見た目を確かめるための口。Debug ビルドにしか無い。走り切りの判定は通らない。
 *
 * ```
 * # 黄金道路のレコードを手に入れたことにする（読み上げも鳴る。音声案内がオンのとき）
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.RoadRecordReceiver --es id golden-road
 * # 収録曲も付ける
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.RoadRecordReceiver --es id ororon --es song "曲名" --es artist "歌手"
 * # 手に入れたあと、すぐ着いたときの演出を開く
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.RoadRecordReceiver --es id golden-road --ez show true
 * # 15枚すべて手に入れたことにする / 全部消す
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.RoadRecordReceiver --ez all true
 * adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.RoadRecordReceiver --ez clear true
 * ```
 */
class RoadRecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? TheaCarApplication ?: return
        val pending = goAsync()
        scope.launch {
            val message = try {
                when {
                    intent.getBooleanExtra("clear", false) -> { app.roadRecords.clear(); "レコードを全部消しました" }
                    intent.getBooleanExtra("all", false) -> {
                        RoadRecords.forEach { app.roadRecords.unlock(it.id, System.currentTimeMillis(), song(intent)) }
                        "15枚すべて手に入れたことにしました"
                    }
                    else -> {
                        val id = intent.getStringExtra("id")
                        val info = id?.let(::roadRecord)
                        if (info == null) "id がありません。使える id: ${RoadRecords.joinToString(",") { it.id }}"
                        else {
                            app.roadRecords.unlock(info.id, System.currentTimeMillis(), song(intent))
                            app.announceRecord("${info.name}のレコードを手に入れました")
                            if (intent.getBooleanExtra("show", false)) {
                                context.startActivity(RecordCelebrationActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            "${info.name}のレコードを手に入れたことにしました"
                        }
                    }
                }
            } catch (error: Exception) {
                "失敗しました: ${error.message}"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            pending.finish()
        }
    }

    private fun song(intent: Intent): RecordSong? {
        val title = intent.getStringExtra("song") ?: return null
        return RecordSong("spotify:track:debug", title, intent.getStringExtra("artist") ?: "", "https://open.spotify.com/")
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
