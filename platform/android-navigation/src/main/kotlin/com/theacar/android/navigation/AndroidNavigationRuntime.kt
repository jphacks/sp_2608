package com.theacar.android.navigation

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.theacar.android.location.AndroidLocationSource
import com.theacar.navigation.LocalNavigationEngine
import com.theacar.navigation.NavigationSession
import com.theacar.navigation.NavigationPhase
import com.theacar.navigation.NavigationSessionOwner
import com.theacar.valhalla.OsmDestinationSearch
import com.theacar.valhalla.ScenicRecommendationClient
import com.theacar.valhalla.routingHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 地図画面のミニプレーヤーに出す内容。再生の実体はアプリ本体が持ち、この画面は表示と操作の通知だけを行う。
 * [subtitle] は今の状況（選曲の理由・車内の様子・問題）、[actionLabel] はその場でできる操作が1つあるときだけ。
 */
data class MiniPlayerState(
    val title: String,
    val isPlaying: Boolean,
    val subtitle: String? = null,
    val actionLabel: String? = null,
    val showIndicator: Boolean = false,
)

interface MiniPlayerSource {
    val state: StateFlow<MiniPlayerState>
    fun toggle()
    fun open(context: Context)
    /** [MiniPlayerState.actionLabel] のボタン。 */
    fun action(context: Context) = Unit
}

interface AndroidNavigationOwner : NavigationSessionOwner {
    val navigationRuntime: AndroidNavigationRuntime
    val miniPlayer: MiniPlayerSource? get() = null
    val tripRecording: NavigationTripRecording? get() = null
    override val navigationSession get() = navigationRuntime.session
}

class AndroidNavigationRuntime(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val configuration = NavigationConfiguration.read(application)
    private val client = routingHttpClient()
    private val credentials = RoutingCredentials(AndroidRoutingTokenStore(application), scope, configuration.routingToken)
    val routingToken = credentials.token
    val routingTokenLoaded = credentials.loaded
    val routingTokenError = credentials.error
    val routingTokenFromBuild = credentials.usingBuiltIn
    suspend fun setRoutingToken(token: String): Boolean = credentials.save(token)
    val locations = AndroidLocationSource(application, scope)
    val engine = LocalNavigationEngine(ScenicRecommendationClient(configuration.routingUrl, client,
        { java.time.OffsetDateTime.now().toString() }, { routingToken.value }), locations, scope, SystemClock::elapsedRealtime)
    val session = NavigationSession(OsmDestinationSearch(
        configuration.geocodingUrl.ifBlank { configuration.routingUrl }, client,
        { if (configuration.geocodingUrl.isBlank()) routingToken.value else "" }), engine, scope)
    private val recentStore = RecentDestinationStore(application)
    private val mutableRecentDestinations = MutableStateFlow(recentStore.read())
    val recentDestinations = mutableRecentDestinations.asStateFlow()
    init {
        scope.launch {
            session.state.collect { state ->
                val destination = state.route?.destination?.takeIf { state.phase == NavigationPhase.GUIDING } ?: return@collect
                val updated = mutableRecentDestinations.value.withRecent(destination)
                if (updated != mutableRecentDestinations.value) { mutableRecentDestinations.value = updated; recentStore.write(updated) }
            }
        }
    }
    private val mutableVoiceEnabled = MutableStateFlow(true)
    val voiceEnabled = mutableVoiceEnabled.asStateFlow()
    fun setVoiceEnabled(enabled: Boolean) { mutableVoiceEnabled.value = enabled }
}
