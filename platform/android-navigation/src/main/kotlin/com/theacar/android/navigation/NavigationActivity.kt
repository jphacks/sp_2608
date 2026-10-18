package com.theacar.android.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.theacar.model.Coordinate
import com.theacar.location.LocationStatus
import com.theacar.navigation.NavigationPhase
import com.theacar.route.Destination
import com.theacar.ui.CompactHeaderBand
import com.theacar.ui.MiniPlayer
import com.theacar.ui.Sky
import com.theacar.ui.Tab
import com.theacar.ui.TabiBottomBar
import com.theacar.ui.TheaCarTheme
import com.theacar.ui.openTab

class NavigationActivity : ComponentActivity() {
    private val runtime get() = (application as AndroidNavigationOwner).navigationRuntime
    private val companion get() = (application as? NavigationCompanionOwner)?.navigationCompanion
    private var mapController: NavigationMapController? = null
    private val viaPoints = mutableStateListOf<Coordinate>()
    private var selectedDestination by mutableStateOf<Destination?>(null)
    private var bandShown = true
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
                savedInstanceState.getString("selectedDestinationTitle") ?: "地図で選択した場所", Coordinate(point[0], point[1]))
        }
        val layout = FrameLayout(this)
        mapController = NavigationMapController(this, runtime, lifecycleScope).also { controller ->
            controller.showWaypoints(viaPoints.toList())
            controller.showSelectedDestination(selectedDestination)
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
                TheaCarTheme {
                    NavigationControls(state, location, runtime.configuration, voice, viaPoints.size,
                        selectedDestination = selectedDestination,
                        mapState = mapState,
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
                            if (missing.isEmpty()) startGuidance() else startPermissionRequest.launch(missing.toTypedArray())
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
                    )
                }
            }
        }
        layout.addView(controls, FrameLayout.LayoutParams(-1, -1))
        // 下端のシステムバー分の余白はタブバーが受け持つので、地図とコントロールには渡さない。
        // 上端も、見出し帯を出している間は帯が受け持つ
        ViewCompat.setOnApplyWindowInsetsListener(layout) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(nav.left, nav.top, nav.right, 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(status.left, if (bandShown) 0 else status.top, status.right, status.bottom))
                .build()
        }
        val band = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { TheaCarTheme { CompactHeaderBand("DRIVE", Sky) } }
        }
        // 案内中は案内カードに場所を譲るため、帯を隠す
        lifecycleScope.launch {
            runtime.session.state.map { it.phase != NavigationPhase.GUIDING && it.phase != NavigationPhase.ARRIVED }
                .distinctUntilChanged().collect { shown ->
                    bandShown = shown
                    band.visibility = if (shown) View.VISIBLE else View.GONE
                    ViewCompat.requestApplyInsets(layout)
                }
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
                            onAction = { miniPlayer.action(this@NavigationActivity) }, showIndicator = player.showIndicator)
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
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun openLocationSettings() {
        val intent = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        startActivity(intent)
    }
}
