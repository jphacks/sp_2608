package com.theacar.android

import androidx.compose.runtime.Composable
import com.theacar.android.music.DriveBgmLink
import com.theacar.android.music.DriveBgmPreview
import com.theacar.android.music.AndroidSpotifyRuntime
import com.theacar.android.navigation.CarShareState
import com.theacar.android.navigation.NavigationCompanion
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ナビ画面へ景観BGMを差し込み、案内の開始・終了を伝える。
 * ナビと音楽は互いに依存しないので、両方を知っているこの層だけが対応づけを持つ。
 */
internal class NavigationMusicCompanion(
    private val link: DriveBgmLink,
    private val music: AndroidSpotifyRuntime,
    navigation: StateFlow<NavigationState>,
    scope: CoroutineScope,
) : NavigationCompanion {
    /** Spotifyの再生先があれば景観BGMなしでもQR受付を開ける。 */
    override val carShare: StateFlow<CarShareState> = combine(music.connection, music.session.state, music.guests.state) { connection, player, guests ->
        val ready = music.bgmConfigured && connection.connected && player.selectedDeviceId != null
        CarShareState(ready || guests.code != null, guests.code?.let(music::guestUrl), "読み取って曲をリクエスト")
    }.stateIn(scope, SharingStarted.Eagerly, CarShareState())

    override fun prepareCarShare() {
        music.openGuestRequests()
    }

    init {
        // 再探索中も GUIDING のままなので、ルートの差し替えでは推薦を止めない。
        scope.launch {
            navigation.map { it.phase == NavigationPhase.GUIDING }.distinctUntilChanged().collect { guiding ->
                if (guiding) link.onGuidanceStarted() else link.onGuidanceEnded()
            }
        }
    }

    @Composable
    override fun PreviewContent() = DriveBgmPreview(link)

    override fun permissionsBeforeStart() = link.permissionsBeforeStart()

    override fun onScreenResumed() = link.refreshDevices()
}
