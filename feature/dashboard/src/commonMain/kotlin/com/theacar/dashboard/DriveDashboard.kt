package com.theacar.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.theacar.cabin.CabinNotice
import com.theacar.dashboard.components.CabinNoticeSheet
import com.theacar.drive.DriveState
import com.theacar.ui.Paper
import com.theacar.ui.Tab
import com.theacar.ui.TabiBottomBar
import com.theacar.ui.TheaCarTheme

data class DemoActions(
    val setConversationLevel: (Float) -> Unit,
    val emitUnusualSound: () -> Unit,
    val cycleScene: () -> Unit,
)

@Composable
fun DriveDashboard(
    state: DriveState,
    screen: DashboardScreen = DashboardScreen.RECORD,
    onScreenChange: (DashboardScreen) -> Unit = {},
    demoActions: DemoActions? = null,
    onOpenNavigation: (() -> Unit)? = null,
    onOpenMusic: (() -> Unit)? = null,
    onOpenWindow: (() -> Unit)? = null,
    trips: List<TripRecord> = emptyList(),
    onOpenTrip: (TripRecord) -> Unit = {},
    loadTripPhoto: suspend (TripRecord) -> ImageBitmap? = { null },
    notice: CabinNotice? = null,
    onDismissNotice: () -> Unit = {},
) {
    TheaCarTheme {
        Scaffold(
            containerColor = Paper,
            // 旅の記録は見出し帯がステータスバーの裏まで色を敷くので、上の余白は帯が受け持つ
            contentWindowInsets = if (screen == DashboardScreen.RECORD) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                TabiBottomBar(
                    selected = when (screen) {
                        DashboardScreen.RECORD -> Tab.RECORD
                        DashboardScreen.SETTINGS -> Tab.SETTINGS
                    },
                    onSelect = { tab ->
                        when (tab) {
                            Tab.RECORD -> onScreenChange(DashboardScreen.RECORD)
                            Tab.SETTINGS -> onScreenChange(DashboardScreen.SETTINGS)
                            Tab.MAP -> onOpenNavigation?.invoke()
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    DashboardScreen.RECORD -> RecordScreen(trips, onOpenTrip, Modifier.fillMaxSize().padding(padding), loadTripPhoto)
                    DashboardScreen.SETTINGS -> SettingsScreen(
                        state = state,
                        demoActions = demoActions,
                        onOpenWindow = onOpenWindow,
                        onOpenMusic = onOpenMusic,
                        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                    )
                }
                // 換気の知らせは画面を覆わないよう、タブバーのすぐ上にそっと重ねる
                CabinNoticeSheet(
                    notice,
                    onDismissNotice,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding()),
                )
            }
        }
    }
}
