package com.theacar.android.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.theacar.music.*
import com.theacar.location.LocationState
import com.theacar.weather.WeatherReadState
import com.theacar.ui.CardCorner
import com.theacar.ui.Tab
import com.theacar.ui.TabiBottomBar

@Composable
internal fun SpotifyMusicScreen(
    connection: SpotifyConnectionState,
    state: MusicState,
    drive: DriveMusicState,
    bgm: BgmMusicState,
    guests: GuestRequestState,
    guestUrl: (String) -> String,
    cabin: CabinStatus,
    bgmMemberNames: String,
    bgmConfigured: Boolean,
    location: LocationState,
    weather: WeatherReadState,
    environmentCheck: EnvironmentCheckStatus,
    onInspectEnvironment: () -> Unit,
    onStartDriveMusic: () -> Unit,
    onStopDriveMusic: () -> Unit,
    onStartBgm: (List<MusicMember>) -> Unit,
    onStopBgm: () -> Unit,
    onSaveClientId: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSearch: (String) -> Unit,
    onDeviceSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (MusicTrack) -> Unit,
    onEnqueue: (MusicTrack) -> Unit,
    onOpenGuests: () -> Unit,
    onCloseGuests: () -> Unit,
    onMoveRequest: (String, Int) -> Unit,
    onRemoveRequest: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectTab: (Tab) -> Unit,
    playlists: PlaylistInputState,
    onImportPlaylist: (String, String) -> Unit,
    onCancelPlaylist: () -> Unit,
    onRemovePlaylist: (String, String) -> Unit,
    onPlaylistMode: (RecommendationMode) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    var showDevices by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = connection.connected && !connection.loading && !state.busy
    val canPlay = enabled && state.selectedDeviceId != null
    val selectedIsPlaying = state.playback.playing && state.playback.deviceId == state.selectedDeviceId
    fun search() { keyboard?.hide(); onSearch(query) }

    Scaffold(bottomBar = { TabiBottomBar(Tab.SETTINGS, onSelectTab) }, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.width(64.dp))
            Image(painterResource(R.drawable.spotify_logo_black), "Spotify", Modifier.width(110.dp).height(30.dp))
            if (connection.connected) TextButton(onClick = onDisconnect, enabled = !connection.loading && !state.busy) { Text("連携解除") }
            else Spacer(Modifier.width(64.dp))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text("ドライブに、好きな音楽を。", style = MaterialTheme.typography.headlineSmall)
                Text("操作は停車中に。音楽はSpotifyアプリから再生されます。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                EnvironmentDetailsPanel(location, weather, environmentCheck, drive, onInspectEnvironment, onOpenUrl)
            }
            if (connection.loading && !connection.configured && !connection.connected) {
                // 保存済みのアプリIDを読むまで、設定手順を先に出さない。
                item {
                    MusicPanel {
                        Text("Spotifyの設定を確認しています", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            } else if (!connection.configured) {
                item { SpotifySetupPanel(connection, onSaveClientId, onOpenUrl) }
            } else if (!connection.connected) {
                item {
                    MusicPanel {
                        Text("Spotifyと連携", style = MaterialTheme.typography.titleLarge)
                        Text("Premiumアカウントでログインしてください。ログイン情報は暗号化して保存します。")
                        Text("ログインを断られる場合は、Dashboard の User Management に自分のアカウントが入っているか確認してください。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onConnect, enabled = !connection.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Spotifyにログイン") }
                        TextButton(onClick = onOpenSpotify) { Text("Spotifyを開く") }
                    }
                }
                item { SpotifySetupPanel(connection, onSaveClientId, onOpenUrl) }
            } else {
                if (state.playback.shuffleEnabled == true) item {
                    Text("Spotifyのシャッフルがオンです。再生順とタイミングを合わせるには、Spotifyでオフにしてください。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenSpotify) { Text("Spotifyを開く") }
                }
                item {
                    DriveMusicPanel(drive, location.status, canPlay, onStartDriveMusic, onStopDriveMusic, onOpenUrl)
                }
                item {
                    PlaylistInputPanel(playlists, bgmConfigured && !bgm.enabled, onImportPlaylist, onCancelPlaylist, onRemovePlaylist,
                        onPlaylistMode, onConnect, onOpenUrl)
                }
                item {
                    BgmRecommendationPanel(bgm, cabin, bgmMemberNames, bgmConfigured,
                        canPlay && !playlists.importing && (playlists.mode != RecommendationMode.PLAYLIST || playlists.pool.isNotEmpty()),
                        onStartBgm, onStopBgm, playlists.members)
                }
                if (bgmConfigured || bgm.requests.isNotEmpty() || guests.code != null) item {
                    GuestRequestPanel(bgm, guests, guestUrl, onOpenGuests, onCloseGuests, onMoveRequest, onRemoveRequest, canReceive = bgmConfigured && canPlay)
                }
                item {
                    OutlinedTextField(query, { query = it }, label = { Text("曲名・アーティストを検索") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !connection.loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) search() }))
                    Button(onClick = ::search, enabled = !connection.loading && query.isNotBlank() && !state.searching,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("検索") }
                }
                item {
                    MusicPanel {
                        Text("再生先", style = MaterialTheme.typography.titleMedium)
                        val device = state.devices.firstOrNull { it.id == state.selectedDeviceId }
                        Box {
                            OutlinedButton(onClick = { showDevices = true }, enabled = enabled && state.devices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                Text(device?.name ?: "再生する端末を選択")
                            }
                            DropdownMenu(expanded = showDevices, onDismissRequest = { showDevices = false }) {
                                state.devices.forEach { candidate ->
                                    DropdownMenuItem(text = { Text(candidate.name + if (candidate.active) " · 使用中" else "") },
                                        onClick = { onDeviceSelected(candidate.id); showDevices = false })
                                }
                            }
                        }
                        if (state.devices.isEmpty()) Text("Spotifyで一度曲を再生してから「更新」を押してください。", style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = onOpenSpotify) { Text("Spotifyを開く") }
                            TextButton(onClick = onRefresh, enabled = enabled) { Text("更新") }
                        }
                    }
                }
                item {
                    MusicPanel {
                        val track = state.playback.track
                        Text(if (state.playback.playing) "Spotifyで再生中" else "Spotifyの再生", style = MaterialTheme.typography.labelLarge)
                        if (track != null) {
                            Text(track.title, style = MaterialTheme.typography.titleLarge)
                            Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { onOpenUrl(track.url) }) { Text("Spotifyで見る") }
                        } else Text("曲を検索して再生してください。")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPrevious, enabled = canPlay, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("前の曲") }
                            Button(onClick = onToggle, enabled = canPlay, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (selectedIsPlaying) "一時停止" else "再生") }
                            OutlinedButton(onClick = onNext, enabled = canPlay, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("次の曲") }
                        }
                    }
                }
                if (state.query.isNotBlank() && !state.searching && state.results.isEmpty() && state.failure == null) item { Text("曲が見つかりませんでした。") }
                items(state.results, key = { it.uri }) { track ->
                    MusicPanel {
                        Text(track.title, style = MaterialTheme.typography.titleMedium)
                        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onOpenUrl(track.url) }) { Text("Spotifyで見る") }
                            Spacer(Modifier.weight(1f))
                            // 景観BGMの間は、カラオケのように「予約」か「今すぐ」を選べる
                            if (bgm.enabled) {
                                OutlinedButton(onClick = { onEnqueue(track) }) { Text("予約") }
                                FilledTonalButton(onClick = { onPlay(track) }, enabled = canPlay) { Text("今すぐ再生") }
                            } else FilledTonalButton(onClick = { onPlay(track) }, enabled = canPlay) { Text("この曲を再生") }
                        }
                    }
                }
            }
            if (connection.loading || state.searching || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            (connection.failure ?: state.failure)?.let { failure -> item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(CardCorner)) {
                    Text(failure.message(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
        }
    }
}

@Composable
internal fun MusicPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(CardCorner), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

internal fun MusicFailure.message(): String = when (this) {
    MusicFailure.AUTHORIZATION -> "Spotifyへのログインが必要です。キャンセルした場合も、もう一度ログインできます。"
    MusicFailure.ACCESS_DENIED -> "Spotifyが操作を許可しませんでした。Premium契約、Dashboardの許可ユーザーとAPI設定を確認してください。"
    MusicFailure.NO_DEVICE -> "再生できる端末がありません。Spotifyで一度曲を再生し、再生先を更新してください。"
    MusicFailure.RATE_LIMIT -> "Spotifyの利用上限に達しました。時間を置いて再試行してください。"
    MusicFailure.STORAGE -> "ログイン情報を保存・復元できませんでした。端末の空き容量を確認して再ログインしてください。"
    MusicFailure.NETWORK -> "Spotifyへ接続できませんでした。通信状態を確認してください。"
    MusicFailure.UNKNOWN -> "Spotifyの操作を完了できませんでした。"
    MusicFailure.WEATHER_UNAVAILABLE -> "現在地の天気を取得できませんでした。通信状態を確認して、おまかせ再生をやり直してください。"
    MusicFailure.NO_RESULTS -> "この環境に合う検索候補が見つかりませんでした。曲名からの検索もお試しください。"
}
