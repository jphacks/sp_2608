package com.theacar.android.navigation

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theacar.location.LocationState
import com.theacar.location.LocationStatus
import com.theacar.location.LocationUse
import com.theacar.location.usableFix
import com.theacar.navigation.NavigationFailure
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationState
import com.theacar.route.Destination
import com.theacar.route.RouteOptions
import com.theacar.route.ScenicOptions
import com.theacar.route.ScenicPreference
import com.theacar.route.ScenicRange
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.unit.sp
import com.theacar.ui.CardCorner
import com.theacar.ui.Ink
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Surface as SurfaceColor
import com.theacar.ui.jost

@Composable
internal fun NavigationControls(
    state: NavigationState,
    location: LocationState,
    configuration: NavigationConfiguration,
    voiceEnabled: Boolean,
    viaPointCount: Int,
    selectedDestination: Destination?,
    mapState: MapDisplayState,
    onRetryMap: () -> Unit,
    onSelectDestination: (Destination) -> Unit,
    onClearSelection: () -> Unit,
    onAddViaPoint: () -> Unit,
    routingToken: String,
    routingTokenLoaded: Boolean,
    routingTokenError: String?,
    routingTokenFromBuild: Boolean,
    onRoutingTokenChanged: suspend (String) -> Boolean,
    onSearch: (String) -> Unit,
    onPreview: (Destination, RouteOptions) -> Unit,
    onSelectAlternative: (String) -> Unit,
    onInvalidatePreview: () -> Unit,
    onHighlightEvidence: (List<ScenicRange>) -> Unit,
    onHighlightRoad: (ScenicRoadHighlight) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCreateMemory: (() -> Unit)? = null,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onVoiceChanged: (Boolean) -> Unit,
    onClearViaPoints: () -> Unit,
    onRecenter: () -> Unit,
    onMapInsetsChanged: (Int, Int, Int) -> Unit,
    companion: NavigationCompanion? = null,
) {
    var avoidTolls by rememberSaveable { mutableStateOf(false) }
    var avoidHighways by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showRoundTrip by rememberSaveable { mutableStateOf(false) }
    var awaitingPreview by rememberSaveable { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var routeDetailRequest by rememberSaveable { mutableIntStateOf(0) }
    var scenicPreference by rememberSaveable { mutableStateOf<ScenicPreference?>(ScenicPreference.BALANCED) }
    var extraMinutes by rememberSaveable { mutableIntStateOf(15) }
    var totalMinutes by rememberSaveable { mutableIntStateOf(120) }
    var desiredStops by rememberSaveable { mutableIntStateOf(0) }
    var variety by rememberSaveable { mutableDoubleStateOf(.5) }
    var allowUnpaved by rememberSaveable { mutableStateOf(true) }
    var requireWidth by rememberSaveable { mutableStateOf(false) }
    var nightCorrection by rememberSaveable { mutableStateOf(true) }
    var customWeights by rememberSaveable(stateSaver = ScenicWeightSaver) { mutableStateOf(emptyMap<String, Double>()) }
    var passengerView by rememberSaveable { mutableDoubleStateOf(0.0) }
    var certifiedRoad by rememberSaveable { mutableDoubleStateOf(0.0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scenicSettings = ScenicOptions(scenicPreference ?: ScenicPreference.BALANCED, extraMinutes * 60,
        totalSeconds = totalMinutes * 60, desiredStops = desiredStops, variety = variety,
        allowUnpaved = allowUnpaved, requireVerifiedWidth = requireWidth, nightCorrection = nightCorrection,
        customWeights = customWeights.takeIf { it.isNotEmpty() }, passengerView = passengerView, certifiedRoad = certifiedRoad)
    val options = RouteOptions(avoidTolls, avoidHighways,
        scenic = scenicSettings.takeIf { scenicPreference != null && viaPointCount == 0 })
    val updateScenic: (ScenicOptions) -> Unit = {
        if (it != scenicSettings) onInvalidatePreview()
        totalMinutes = it.totalSeconds / 60; desiredStops = it.desiredStops; variety = it.variety
        allowUnpaved = it.allowUnpaved; requireWidth = it.requireVerifiedWidth
        nightCorrection = it.nightCorrection
        scenicPreference = it.preference
        customWeights = it.customWeights.orEmpty()
        passengerView = it.passengerView; certifiedRoad = it.certifiedRoad
    }
    val roundTripMode = selectedDestination == null
    val canPlan = state.phase in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW, NavigationPhase.PREVIEWING)
    val busy = state.searching || state.phase == NavigationPhase.PREVIEWING || !routingTokenLoaded
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { value = SystemClock.elapsedRealtime(); delay(1_000) }
    }
    val locationNow = maxOf(now, SystemClock.elapsedRealtime())
    val canGuide = location.status == LocationStatus.AVAILABLE && location.usableFix(LocationUse.GUIDANCE_START, locationNow) != null
    val startRouteSearch: () -> Unit = {
        keyboard?.hide()
        if (selectedDestination != null) {
            awaitingPreview = true
            onPreview(selectedDestination, options)
        } else location.fix?.let { fix ->
            // 寄り道があるときの周遊は景観探索ができないので、出発地点へ戻る通常の経路にする
            if (viaPointCount > 0) onPreview(Destination("return", "出発地点へ戻る", fix.coordinate), options)
            else onPreview(Destination("roundtrip", "出発地点へ戻る", fix.coordinate),
                options.copy(scenic = scenicSettings.copy(roundTrip = true)))
        }
    }
    // 景観APIが使えないときのために、景観なしで同じ検索をやり直す
    val retryWithoutScenic: (() -> Unit)? = if (selectedDestination != null) {
        { keyboard?.hide(); awaitingPreview = true; onPreview(selectedDestination, options.copy(scenic = null)) }
    } else location.fix?.let { fix ->
        { keyboard?.hide(); onPreview(Destination("return", "出発地点へ戻る", fix.coordinate), options.copy(scenic = null)) }
    }
    val compactTimeline = state.alternatives.firstOrNull { it.plan.id == state.route?.id }
        ?.explanation?.timeline.orEmpty().takeIf {
            !panelExpanded && state.phase == NavigationPhase.PREVIEW && selectedDestination == null &&
                (it.lastOrNull()?.range?.endMeters ?: 0.0) > 0
        }.orEmpty()
    val panelScroll = rememberScrollState()
    LaunchedEffect(panelExpanded, state.route?.id) {
        if (!panelExpanded) panelScroll.scrollTo(0)
    }
    LaunchedEffect(selectedDestination?.id, state.phase) {
        panelExpanded = false
        routeDetailRequest = 0
    }
    LaunchedEffect(selectedDestination?.id, state.route?.id) {
        if (selectedDestination != null || state.route != null) showRoundTrip = false
    }
    LaunchedEffect(state.phase, awaitingPreview) {
        if (awaitingPreview && state.phase == NavigationPhase.PREVIEW) {
            onClearSelection()
            awaitingPreview = false
        } else if (awaitingPreview && state.phase == NavigationPhase.IDLE) {
            awaitingPreview = false
        }
    }
    val roundTripSheet = showRoundTrip && canPlan && state.route == null && selectedDestination == null && viaPointCount == 0
    val screen = LocalConfiguration.current
    val wide = screen.screenWidthDp > screen.screenHeightDp
    val maxPanelHeight = if (wide) (screen.screenHeightDp - 100).coerceAtLeast(120).dp
        else (screen.screenHeightDp * if (panelExpanded) .54f else if (compactTimeline.isNotEmpty() || roundTripSheet) .44f else .33f).dp
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    var panelWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(topHeight, bottomHeight, panelWidth, wide) {
        onMapInsetsChanged(topHeight, if (wide) panelWidth else 0, if (wide) 0 else bottomHeight)
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.align(if (wide) Alignment.TopStart else Alignment.TopCenter)
            .widthIn(max = if (wide) (screen.screenWidthDp - 340).coerceAtLeast(180).dp else 600.dp).fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
            .onSizeChanged { topHeight = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 探す操作は地図の上端にまとめる。経路の確認中・案内中は案内カードに場所を譲る
            val planning = state.phase == NavigationPhase.IDLE && configuration.configured
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (planning) {
                    // 押すと検索画面が開く。ボタンではなく検索窓に見えるよう、白地に緑の縁で置く
                    Surface(onClick = { showSearch = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("目的地を検索", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Surface(onClick = {
                            // 目的地も寄り道もないときは、すぐ探さず周遊ルートの設定を下から出す
                            if (selectedDestination == null && viaPointCount == 0) { keyboard?.hide(); showRoundTrip = true }
                            else startRouteSearch()
                        }, enabled = !busy && (selectedDestination != null || viaPointCount == 0 || location.fix != null),
                        modifier = Modifier.size(48.dp), shape = CircleShape, color = Mountains) {
                        Icon(Icons.Rounded.ArrowForward, contentDescription = "経路を探す",
                            tint = SurfaceColor, modifier = Modifier.size(48.dp).padding(12.dp))
                    }
                } else Spacer(Modifier.weight(1f))
                MapIconButton(Icons.Rounded.Tune, "ナビの設定", { showSettings = true })
            }
            if (planning && viaPointCount == 0) {
                ScenicChipRow(scenicPreference, { if (scenicPreference != it) onInvalidatePreview(); scenicPreference = it },
                    scenicSettings.copy(roundTrip = roundTripMode), updateScenic, enabled = !busy)
            }
            when (mapState) {
                MapDisplayState.LOADING -> Surface(shape = RoundedCornerShape(CardCorner), border = FloatingBorder) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("地図を読み込んでいます…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                MapDisplayState.ERROR -> Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(CardCorner), border = FloatingBorder) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("地図を読み込めません。通信を確認してください。", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onRetryMap) { Text("再試行") }
                    }
                }
                else -> Unit
            }
            if (state.phase == NavigationPhase.GUIDING) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(CardCorner)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (state.rerouting) "ルートを再検索中" else "次の案内", style = MaterialTheme.typography.labelMedium)
                        state.maneuver?.let {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${it.distanceMeters}", style = MaterialTheme.jost.stat.copy(color = LocalContentColor.current))
                                Text("m 先", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            Text(it.instruction, style = MaterialTheme.typography.titleMedium)
                        } ?: Text("ルートに沿って進んでください", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Column(Modifier.align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter)
            .widthIn(max = if (wide) 340.dp else 600.dp).fillMaxWidth().padding(12.dp)
            .onSizeChanged { bottomHeight = it.height; panelWidth = it.width }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapIconButton(Icons.Rounded.MyLocation, "現在地", onRecenter, Modifier.align(Alignment.End))
            if (state.phase == NavigationPhase.GUIDING) companion?.GuidanceContent()
            Surface(shape = RoundedCornerShape(CardCorner), border = FloatingBorder) {
                Column(Modifier.fillMaxWidth().heightIn(max = maxPanelHeight)) {
                    val panelTitle = selectedDestination?.title ?: state.route?.destination?.title
                    var showRoundTripHelp by rememberSaveable { mutableStateOf(false) }
                    // 目的地を入れずに矢印を押したときだけ、周遊ルートの設定が下から出る
                    AnimatedVisibility(roundTripSheet, modifier = Modifier.weight(1f, fill = false),
                        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("周遊ルート", style = MaterialTheme.typography.titleLarge, color = Ink)
                                IconButton(onClick = { showRoundTripHelp = true }) {
                                    Icon(Icons.Rounded.Info, contentDescription = "周遊ルートの説明", tint = Muted)
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { showRoundTrip = false }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "周遊ルートを閉じる", tint = Ink)
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ScenicSearchControls(scenicPreference, extraMinutes,
                                    { if (scenicPreference != it) onInvalidatePreview(); scenicPreference = it },
                                    { if (extraMinutes != it) onInvalidatePreview(); extraMinutes = it }, enabled = !busy,
                                    settings = scenicSettings.copy(roundTrip = true), onSettings = updateScenic, compact = true, showChips = false,
                                    onQuickSearch = if (location.fix != null) { quick ->
                                        location.fix?.let { fix ->
                                            keyboard?.hide()
                                            onPreview(Destination("roundtrip", "出発地点へ戻る", fix.coordinate),
                                                options.copy(scenic = quick.copy(roundTrip = true)))
                                        }
                                    } else null)
                                Button(onClick = startRouteSearch, enabled = !busy && location.fix != null,
                                    shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Mountains, contentColor = SurfaceColor),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("${totalMinutes}分以内の周遊ルートを探す") }
                                if (location.fix == null) Text("現在地を取得すると周遊ルートを探せます", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                    if (showRoundTripHelp) AlertDialog(onDismissRequest = { showRoundTripHelp = false },
                        title = { Text("周遊ルート") },
                        text = { Text("目的地を入れずに矢印を押すと、現在地から出発して現在地に戻るルートを探します。長さは「周遊全体の上限」で決められます。目的地を入れると、目的地までのルートを探します。") },
                        confirmButton = { TextButton(onClick = { showRoundTripHelp = false }) { Text("閉じる") } })
                    if (panelTitle != null || state.route != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (panelTitle != null) Text(panelTitle, style = MaterialTheme.typography.titleLarge, color = Ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (compactTimeline.isNotEmpty()) state.route?.let { route ->
                                    Text(jostNumbers("${(route.durationSeconds + 59) / 60}分 · ${(route.distanceMeters / 100) / 10.0}km"),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { panelExpanded = !panelExpanded }) {
                                Icon(if (panelExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                    contentDescription = if (panelExpanded) "パネルを縮める" else "パネルを広げる", tint = Ink)
                            }
                        }
                    }
                    // 周遊ルートの設定を出している間は、こちらを内容の高さだけにして設定側に高さを譲る
                    Column((if (roundTripSheet) Modifier else Modifier.weight(1f, fill = false)).fillMaxWidth().verticalScroll(panelScroll)
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (panelExpanded || location.usableFix(LocationUse.ROUTE_PREVIEW, locationNow) == null) {
                            LocationNotice(location, locationNow, onRequestPermissions, onOpenLocationSettings)
                        }
                        if (!configuration.configured) {
                            Text("接続先を設定してください", style = MaterialTheme.typography.titleLarge)
                            Text("local.properties の VALHALLA_URL を設定して再ビルドしてください。", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            if (canPlan && selectedDestination != null) {
                                if (viaPointCount == 0) ScenicSearchControls(scenicPreference, extraMinutes,
                                    { if (scenicPreference != it) onInvalidatePreview(); scenicPreference = it },
                                    { if (extraMinutes != it) onInvalidatePreview(); extraMinutes = it }, enabled = !busy,
                                    settings = scenicSettings, onSettings = updateScenic, compact = !panelExpanded, showChips = false,
                                    onQuickSearch = { quick ->
                                        keyboard?.hide(); awaitingPreview = true
                                        onPreview(selectedDestination, options.copy(scenic = quick))
                                    })
                                if (panelExpanded) {
                                    Text(selectedDestination.title, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text("目的地 · ${coordinateLabel(selectedDestination)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { keyboard?.hide(); awaitingPreview = true; onPreview(selectedDestination, options) },
                                        enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("ここまでのルートを見る") }
                                    // 地図を誤ってタップして選んだ目的地を、すぐ取り消して選び直せるようにする
                                    Surface(onClick = onClearSelection, modifier = Modifier.size(52.dp), shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)) {
                                        Icon(Icons.Rounded.Close, contentDescription = "目的地の選択を取り消す", tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(14.dp))
                                    }
                                }
                                if (panelExpanded) TextButton(onClick = onAddViaPoint, enabled = !busy && viaPointCount < 126) { Text("寄り道に追加") }
                            } else if (canPlan && state.route == null && !busy) {
                                if (viaPointCount > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("寄り道 $viaPointCount 件", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                        TextButton(onClick = onClearViaPoints) { Text("クリア") }
                                    }
                                }
                            }
                            if (state.previewNeedsRefresh) {
                                Text("条件を変更しました。再検索してください。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (busy) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (options.scenic != null && state.phase == NavigationPhase.PREVIEWING) {
                                    Text("景観区間をつなぎ、候補を改善しています（最大約2分）", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            state.route?.takeIf { selectedDestination == null }?.let { route ->
                                if (panelExpanded || state.phase == NavigationPhase.ARRIVED) Text(if (state.phase == NavigationPhase.ARRIVED) "到着しました" else route.destination.title,
                                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!panelExpanded && compactTimeline.isEmpty()) {
                                    Text(jostNumbers("${((state.progress?.remainingSeconds ?: route.durationSeconds) + 59) / 60}分 · ${((state.progress?.remainingMeters ?: route.distanceMeters) / 100) / 10.0}km"),
                                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                } else if (panelExpanded) Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                                    RouteMetric("${((state.progress?.remainingSeconds ?: route.durationSeconds) + 59) / 60}", "分目安")
                                    RouteMetric("${((state.progress?.remainingMeters ?: route.distanceMeters) / 100) / 10.0}", "km")
                                }
                                if (state.phase == NavigationPhase.PREVIEW) {
                                    if (state.previewImproving) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text("候補を表示しました。ほかの経路も探索中です。選択・案内開始できます。",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    ScenicAlternatives(state.alternatives, route.id,
                                        onSelect = {
                                            onSelectAlternative(it)
                                            panelExpanded = true
                                            routeDetailRequest++
                                        },
                                        onHighlight = onHighlightEvidence, compact = !panelExpanded,
                                        onHighlightRoad = onHighlightRoad, detailRequest = routeDetailRequest)
                                }
                                // 到着して記録が保存できたときだけ、ステッカー作りへ進むボタンを出す
                                if (state.phase == NavigationPhase.ARRIVED && onCreateMemory != null) {
                                    Button(onClick = onCreateMemory, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("思い出をつくる") }
                                }
                                if (panelExpanded || state.phase != NavigationPhase.PREVIEW) Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    LabeledSwitch("音声案内", voiceEnabled, onVoiceChanged)
                                    TextButton(onClick = if (state.phase == NavigationPhase.PREVIEW) onInvalidatePreview else onStop, enabled = state.phase != NavigationPhase.STOPPING) {
                                        Text(if (state.phase == NavigationPhase.PREVIEW) "経路を変更" else "案内を終了")
                                    }
                                }
                            }
                            state.failure?.let {
                                // 案内中の位置不良は失敗ではなく、精度が上がるのを待っている状態として伝える。
                                if (it == NavigationFailure.LOCATION && state.phase == NavigationPhase.GUIDING) {
                                    Text("GPSの精度が上がるのを待っています。屋外では数秒で右左折の案内が始まります。",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                    return@let
                                }
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(CardCorner)) {
                                    Text(it.message(), color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                                }
                                if (it == NavigationFailure.SCENIC_UNAVAILABLE && retryWithoutScenic != null) {
                                    TextButton(onClick = retryWithoutScenic, enabled = !busy,
                                        colors = ButtonDefaults.textButtonColors(contentColor = Mountains)) { Text("景観なしで探す") }
                                }
                            }
                            if (configuration.mapStyleUrl.isBlank()) {
                                Text("背景地図なし · 現在地と経路を表示", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // 出典表示は常に見える固定位置に置く
                    Text("© OpenMapTiles · © OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall,
                        color = Muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp))
                    if (state.phase == NavigationPhase.PREVIEW && selectedDestination == null) {
                        // 開始ボタンと同じ固定領域に置き、詳細のスクロール位置に関係なく開始前に確認できるようにする。
                        companion?.let { Box(Modifier.padding(horizontal = 16.dp)) { it.PreviewContent() } }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onStart, enabled = canGuide,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(if (canGuide) "案内を開始" else "現在地を確認中…")
                            }
                            TextButton(onClick = onInvalidatePreview) { Text("変更") }
                        }
                    }
                }
            }
        }
    }
    if (showSettings) {
        NavigationSettings(routingToken, onRoutingTokenChanged, avoidTolls,
            { if (avoidTolls != it) onInvalidatePreview(); avoidTolls = it },
            avoidHighways, { if (avoidHighways != it) onInvalidatePreview(); avoidHighways = it }, voiceEnabled, onVoiceChanged, canPlan, { showSettings = false },
            tokenLoaded = routingTokenLoaded, tokenError = routingTokenError,
            tokenFromBuild = routingTokenFromBuild)
    }
    if (showSearch && canPlan) {
        DestinationSearchDialog(state, true, onSearch,
            onSelect = { keyboard?.hide(); onSelectDestination(it); showSearch = false },
            onDismiss = { keyboard?.hide(); showSearch = false })
    }
}

private fun coordinateLabel(destination: Destination): String =
    String.format(java.util.Locale.ROOT, "%.5f, %.5f", destination.coordinate.latitude, destination.coordinate.longitude)

@Composable
private fun RouteMetric(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.jost.stat.copy(fontSize = 32.sp, lineHeight = 36.sp))
        Text(unit, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocationNotice(location: LocationState, now: Long, onRequestPermissions: () -> Unit, onOpenSettings: () -> Unit) {
    if (location.status == LocationStatus.PERMISSION_REQUIRED) {
        FilledTonalButton(colors = mistButtonColors(), onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("現在地の利用を許可") }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("アプリの位置情報設定を開く") }
        return
    }
    if (location.status == LocationStatus.DISABLED) {
        FilledTonalButton(colors = mistButtonColors(), onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("端末の位置情報をオンにする") }
        return
    }
    val canPreview = location.usableFix(LocationUse.ROUTE_PREVIEW, now) != null
    val message = when (location.status) {
        LocationStatus.DISABLED -> null
        LocationStatus.WAITING -> "現在地を取得しています…"
        LocationStatus.STALE -> if (canPreview) "前回の位置からルート検索できます · 現在地を更新中" else "現在地を再取得しています…"
        LocationStatus.AVAILABLE -> when {
            !canPreview -> "位置の誤差が大きいため、より正確な位置を待っています"
            (location.fix?.accuracyMeters ?: 101f) > 100 -> "おおよその現在地から検索できます · 案内は精度向上を待機"
            (location.fix?.accuracyMeters ?: 100f) > 40 -> "案内を開始できます · 右左折の案内は精度が上がってから"
            else -> "青い点が現在地です"
        }
        else -> null
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    location.fix?.let { fix ->
        Text("測位精度 約 ${kotlin.math.ceil(fix.accuracyMeters).toInt()} m · ${((now - fix.elapsedRealtimeMillis) / 1_000).coerceAtLeast(0)} 秒前",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
