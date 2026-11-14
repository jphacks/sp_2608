package com.theacar.android.navigation

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.theacar.model.Coordinate
import com.theacar.location.LocationStatus
import com.theacar.navigation.NavigationPhase
import com.theacar.route.Destination
import com.theacar.ui.MiniPlayer
import com.theacar.ui.MiniPlayerProgress
import com.theacar.ui.PermissionDisclosure
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.theacar.ui.Regent
import com.theacar.ui.Tab
import com.theacar.ui.TabiBottomBar
import com.theacar.ui.TheaCarTheme
import com.theacar.ui.disclose
import com.theacar.ui.openTab

class NavigationActivity : ComponentActivity() {
    private val runtime get() = (application as AndroidNavigationOwner).navigationRuntime
    private val companion get() = (application as? NavigationCompanionOwner)?.navigationCompanion
    private var mapController: NavigationMapController? = null
    private val viaPoints = mutableStateListOf<Coordinate>()
    private var selectedDestination by mutableStateOf<Destination?>(null)
    /** 地図を航空写真で見せるか。次に開いたときも同じ種類にする */
    private var mapSatellite by mutableStateOf(false)
    private val mapPreferences by lazy { getSharedPreferences(MAP_PREFERENCES, MODE_PRIVATE) }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runtime.locations.acquire(this)
    }
    /** 差し込んだ機能の権限。結果に関係なく案内は始める。 */
    private val startPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startGuidance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getDoubleArray("viaPoints")?.toList()?.chunked(2)?.forEach { pair ->
            if (pair.size == 2) viaPoints.add(Coordinate(pair[0], pair[1]))
        }
        savedInstanceState?.getDoubleArray("selectedDestination")?.takeIf { it.size == 2 }?.let { point ->
            selectedDestination = Destination("map:${point[0]},${point[1]}",
                savedInstanceState.getString("selectedDestinationTitle") ?: PICKED_PLACE_TITLE, Coordinate(point[0], point[1]))
        }
        // ほかの画面（RECORD タブの「この道へ行く」など）から目的地を渡されたとき
        if (savedInstanceState == null) requestedDestination(intent)?.let { selectedDestination = it }
        mapSatellite = mapPreferences.getBoolean(SATELLITE_KEY, false)
        val layout = FrameLayout(this)
        mapController = NavigationMapController(this, runtime, lifecycleScope).also { controller ->
            controller.setSatellite(mapSatellite)
            controller.showWaypoints(viaPoints.toList())
            controller.showSelectedDestination(selectedDestination)
            if (savedInstanceState == null) selectedDestination?.let(controller::focusDestination)
            layout.addView(controller.view, FrameLayout.LayoutParams(-1, -1))
            controller.onDestinationSelected = { destination ->
                if (runtime.session.state.value.phase in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW)) {
                    runtime.session.invalidatePreview()
                    selectedDestination = destination
                    controller.showSelectedDestination(destination)
                    runtime.session.clearFailure()
                }
            }
        }
        val controls = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by runtime.session.state.collectAsState()
                val location by runtime.locations.location.collectAsState()
                val voice by runtime.voiceEnabled.collectAsState()
                val token by runtime.routingToken.collectAsState()
                val tokenLoaded by runtime.routingTokenLoaded.collectAsState()
                val tokenError by runtime.routingTokenError.collectAsState()
                val tokenFromBuild by runtime.routingTokenFromBuild.collectAsState()
                val recording = (application as AndroidNavigationOwner).tripRecording
                val savedMemoryId = recording?.lastSavedId?.collectAsState()?.value
                val memorySaveFailed = recording?.saveFailed?.collectAsState()?.value == true
                val mapState = mapController?.displayState?.collectAsState()?.value ?: MapDisplayState.DISABLED
                TheaCarTheme { MapOverlayText {
                    NavigationControls(state, location, runtime.configuration, voice, viaPoints.size,
                        selectedDestination = selectedDestination,
                        mapState = mapState,
                        onAutoDrive = if (debuggable) ::toggleAutoDrive else null,
                        onRetryMap = { mapController?.reloadMap() },
                        onSelectDestination = { destination ->
                            runtime.session.invalidatePreview()
                            selectedDestination = destination
                            mapController?.showSelectedDestination(destination)
                            mapController?.focusDestination(destination)
                        },
                        onClearSelection = { selectedDestination = null; mapController?.showSelectedDestination(null) },
                        onAddViaPoint = {
                            selectedDestination?.let { destination ->
                                if (viaPoints.size < 126) {
                                    viaPoints.add(destination.coordinate)
                                    mapController?.showWaypoints(viaPoints.toList())
                                    selectedDestination = null
                                    mapController?.showSelectedDestination(null)
                                    if (runtime.session.state.value.phase == NavigationPhase.PREVIEW) runtime.session.stop()
                                }
                            }
                        },
                        routingToken = token,
                        routingTokenLoaded = tokenLoaded,
                        routingTokenError = tokenError,
                        routingTokenFromBuild = tokenFromBuild,
                        onRoutingTokenChanged = runtime::setRoutingToken,
                        onRoutingUrlChanged = { url ->
                            NavigationConfiguration.saveRoutingUrl(this@NavigationActivity, url).also { saved ->
                                // 接続先はアプリの起動時に組み立てるので、プロセスごと起動し直して反映する
                                if (saved) {
                                    startActivity(Intent.makeRestartActivityTask(ComponentName(this@NavigationActivity, NavigationActivity::class.java)))
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                        },
                        onSearch = runtime.session::search,
                        onPreview = { destination, options ->
                            runtime.session.preview(destination, options.copy(viaPoints = viaPoints.toList()))
                        },
                        onSelectAlternative = runtime.session::selectAlternative,
                        onInvalidatePreview = {
                            val current = runtime.session.state.value
                            if (selectedDestination == null && current.previewOptions?.scenic?.roundTrip != true &&
                                current.phase == NavigationPhase.PREVIEW) {
                                selectedDestination = current.previewDestination
                                mapController?.showSelectedDestination(selectedDestination)
                            }
                            runtime.session.invalidatePreview()
                        },
                        onHighlightEvidence = { mapController?.highlightEvidence(it) },
                        onHighlightRoad = { road ->
                            mapController?.highlightEvidence(road.ranges, "${road.road.rank.label} · ${road.road.name}", rankColor(road.road.rank).toArgb())
                        },
                        onStart = {
                            val missing = companion?.permissionsBeforeStart().orEmpty()
                                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                            when {
                                missing.isEmpty() -> startGuidance()
                                Manifest.permission.RECORD_AUDIO in missing -> disclose(PermissionDisclosure.MICROPHONE, ::startGuidance) {
                                    startPermissionRequest.launch(missing.toTypedArray())
                                }
                                else -> startPermissionRequest.launch(missing.toTypedArray())
                            }
                        },
                        onStop = runtime.session::stop,
                        onCreateMemory = savedMemoryId?.let { id -> { recording?.open(this@NavigationActivity, id) } },
                        memorySaveFailed = memorySaveFailed,
                        onRetryMemorySave = { recording?.retry() },
                        onRequestPermissions = ::requestPermissions,
                        onOpenLocationSettings = ::openLocationSettings,
                        onVoiceChanged = runtime::setVoiceEnabled,
                        onClearViaPoints = { viaPoints.clear(); mapController?.showWaypoints(emptyList()) },
                        onRecenter = {
                            mapController?.recenter()
                            runtime.locations.acquire(this@NavigationActivity)
                            when (runtime.locations.location.value.status) {
                                LocationStatus.PERMISSION_REQUIRED -> requestPermissions()
                                LocationStatus.DISABLED -> openLocationSettings()
                                else -> Unit
                            }
                        },
                        onMapInsetsChanged = { top, right, bottom -> mapController?.setPadding(0, top, right, bottom) },
                        companion = companion,
                        mapSatellite = mapSatellite,
                        onMapSatelliteChanged = { enabled ->
                            mapSatellite = enabled
                            mapController?.setSatellite(enabled)
                            mapPreferences.edit().putBoolean(SATELLITE_KEY, enabled).apply()
                        },
                    )
                } }
            }
        }
        layout.addView(controls, FrameLayout.LayoutParams(-1, -1))
        // システムバー分の余白は、上はタブの色の帯が、下はタブバーが受け持つので、地図とコントロールには渡さない
        ViewCompat.setOnApplyWindowInsetsListener(layout) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(nav.left, nav.top, nav.right, 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(status.left, 0, status.right, status.bottom))
                .build()
        }
        // ほかのタブの見出し帯と色をそろえ、地図の面積は削らないよう、ステータスバーの裏だけに色を敷く
        val band = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { TheaCarTheme { Box(Modifier.fillMaxWidth().background(Regent).statusBarsPadding()) } }
        }
        val miniPlayer = (application as AndroidNavigationOwner).miniPlayer
        val player = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (miniPlayer != null && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                    val player by miniPlayer.state.collectAsState()
                    TheaCarTheme {
                        MiniPlayer(player.title, player.isPlaying, miniPlayer::toggle, { miniPlayer.open(this@NavigationActivity) },
                            subtitle = player.subtitle, actionLabel = player.actionLabel,
                            onAction = { miniPlayer.action(this@NavigationActivity) }, showIndicator = player.showIndicator,
                            actionReplacesPlay = player.actionReplacesPlay,
                            progress = player.progress?.let { MiniPlayerProgress(it.positionMillis, it.durationMillis, it.anchorMillis, it.playing) },
                            clock = miniPlayer::clock)
                    }
                }
            }
        }
        val tabBar = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { TheaCarTheme { TabiBottomBar(Tab.MAP, ::openTab) } }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(band, LinearLayout.LayoutParams(-1, -2))
            addView(layout, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(player, LinearLayout.LayoutParams(-1, -2))
            addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        if (savedInstanceState == null && permissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        mapController?.view?.onStart()
    }

    override fun onResume() {
        super.onResume()
        runtime.locations.acquire(this)
        mapController?.view?.onResume()
        companion?.onScreenResumed()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putDoubleArray("viaPoints", viaPoints.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
        selectedDestination?.let {
            outState.putDoubleArray("selectedDestination", doubleArrayOf(it.coordinate.latitude, it.coordinate.longitude))
            outState.putString("selectedDestinationTitle", it.title)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { mapController?.view?.onPause(); super.onPause() }
    override fun onStop() {
        mapController?.view?.onStop()
        runtime.locations.release(this)
        super.onStop()
    }
    override fun onDestroy() { mapController?.close(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapController?.view?.onLowMemory() }

    private fun startGuidance() {
        try {
            startForegroundService(Intent(this, NavigationService::class.java))
        } catch (_: IllegalStateException) {
            android.widget.Toast.makeText(this, "画面を開いた状態で開始してください", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(this, "正確な位置情報を許可してください", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(permissions())
        } else {
            disclose(PermissionDisclosure.LOCATION) { permissionRequest.launch(permissions()) }
        }
    }

    // 案内の通知（車載画面の曲がり角の知らせも含む）は、Android 13 以上では許可がないと出ない
    private fun permissions(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private fun openLocationSettings() {
        val intent = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val destination = requestedDestination(intent) ?: return
        if (runtime.session.state.value.phase !in setOf(NavigationPhase.IDLE, NavigationPhase.PREVIEW)) return
        runtime.session.invalidatePreview()
        selectedDestination = destination
        mapController?.showSelectedDestination(destination)
        mapController?.focusDestination(destination)
    }

    private fun requestedDestination(intent: Intent?): Destination? {
        intent ?: return null
        if (!intent.hasExtra(EXTRA_DESTINATION_LATITUDE) || !intent.hasExtra(EXTRA_DESTINATION_LONGITUDE)) return null
        val latitude = intent.getDoubleExtra(EXTRA_DESTINATION_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, Double.NaN)
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Destination("map:$latitude,$longitude", intent.getStringExtra(EXTRA_DESTINATION_TITLE) ?: PICKED_PLACE_TITLE,
            Coordinate(latitude, longitude))
    }

    private val debuggable get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** 隠しボタン（残り時間・距離の長押し）：案内中の経路を目的地まで自動で走る。もう一度で止める。Debug 版だけ。 */
    private fun toggleAutoDrive() {
        val simulator = runtime.simulator
        val message = when {
            simulator.running.value -> { simulator.stop(); "自動走行を止めました" }
            simulator.start(AUTO_DRIVE_KMH) -> "自動走行を始めました（${AUTO_DRIVE_KMH.toInt()}km/h）"
            else -> "案内中の経路がありません"
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 隠しボタンの自動走行の速さ（Debug 版だけ） */
        private const val AUTO_DRIVE_KMH = 60.0
        private const val EXTRA_DESTINATION_LATITUDE = "com.theacar.navigation.DESTINATION_LATITUDE"
        private const val EXTRA_DESTINATION_LONGITUDE = "com.theacar.navigation.DESTINATION_LONGITUDE"
        private const val EXTRA_DESTINATION_TITLE = "com.theacar.navigation.DESTINATION_TITLE"

        /** 地図の画面を開き、[title] の地点を目的地として選んだ状態にする（経路はまだ探さない） */
        fun destinationIntent(context: android.content.Context, title: String, latitude: Double, longitude: Double): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(EXTRA_DESTINATION_LATITUDE, latitude)
                .putExtra(EXTRA_DESTINATION_LONGITUDE, longitude)
                .putExtra(EXTRA_DESTINATION_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

private const val MAP_PREFERENCES = "map-display"
private const val SATELLITE_KEY = "satellite"
