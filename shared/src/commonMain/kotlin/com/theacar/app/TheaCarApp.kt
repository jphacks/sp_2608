package com.theacar.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.theacar.cabin.CabinNotice
import com.theacar.dashboard.DashboardScreen
import com.theacar.dashboard.DemoActions
import com.theacar.dashboard.DriveDashboard
import com.theacar.dashboard.TripRecord

@Composable
fun TheaCarApp(
    graph: AppGraph,
    screen: String? = null,
    onScreenChange: (String) -> Unit = {},
    onOpenNavigation: (() -> Unit)? = null,
    onOpenMusic: (() -> Unit)? = null,
    onOpenWindow: (() -> Unit)? = null,
    trips: List<TripRecord> = emptyList(),
    onOpenTrip: (TripRecord) -> Unit = {},
    loadTripPhoto: suspend (TripRecord) -> ImageBitmap? = { null },
    notice: CabinNotice? = null,
    onDismissNotice: () -> Unit = {},
) {
    val session = graph.driveSession
    val state by session.state.collectAsState()
    var shown by remember(screen) { mutableStateOf(DashboardScreen.of(screen)) }
    DriveDashboard(
        state = state,
        screen = shown,
        onScreenChange = { shown = it; onScreenChange(it.name) },
        onOpenNavigation = onOpenNavigation,
        onOpenMusic = onOpenMusic,
        onOpenWindow = onOpenWindow,
        trips = trips,
        onOpenTrip = onOpenTrip,
        loadTripPhoto = loadTripPhoto,
        demoActions = graph.demoControls?.let {
            DemoActions(it.setConversationLevel, it.emitUnusualSound, it.cycleScene)
        },
        notice = notice,
        onDismissNotice = onDismissNotice,
    )
}
