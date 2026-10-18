package com.theacar.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.theacar.android.memory.MemoryActivity
import com.theacar.cabin.CabinNotice
import com.theacar.android.music.SpotifyMusicActivity
import com.theacar.android.navigation.NavigationActivity
import com.theacar.android.window.WindowActivity
import com.theacar.app.TheaCarApp

class MainActivity : ComponentActivity() {
    // 地図など他の画面のタブバーから「旅の記録」「設定」を選んだときは、その画面を表示する（指定なしは旅の記録）
    private var screen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = savedInstanceState?.getString(STATE_SCREEN) ?: intent.getStringExtra(EXTRA_TAB)
        val application = this.application as TheaCarApplication
        val graph = application.graph
        setContent {
            // 換気の知らせは一度きりなので、受け取ったら表示して、消えたら捨てる
            var notice by remember { mutableStateOf<CabinNotice?>(null) }
            LaunchedEffect(Unit) {
                application.cabinNotices.notices.collect { notice = it }
            }
            val trips by application.tripRecords.collectAsState()
            TheaCarApp(graph,
                screen = screen,
                onScreenChange = { screen = it },
                onOpenNavigation = {
                    startActivity(Intent(this@MainActivity, NavigationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                },
                onOpenMusic = {
                    startActivity(Intent(this@MainActivity, SpotifyMusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                },
                onOpenWindow = { startActivity(Intent(this@MainActivity, WindowActivity::class.java)) },
                trips = trips,
                onOpenTrip = { startActivity(MemoryActivity.intent(this@MainActivity, it.id)) },
                loadTripPhoto = { application.memoryRuntime.loadThumbnail(it.id) },
                notice = notice,
                onDismissNotice = { notice = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { screen = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN, screen)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        // core/ui の EXTRA_TAB / Tab.name（RECORD・SETTINGS）と同じ値（androidApp は core:ui に直接依存しない）
        const val EXTRA_TAB = "com.theacar.TAB"
        const val STATE_SCREEN = "screen"
    }
}
