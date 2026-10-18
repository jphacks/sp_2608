package com.theacar.drive

import com.theacar.environment.DriveContextSource
import com.theacar.model.Coordinate
import com.theacar.model.DriveContext
import com.theacar.model.DriveMode
import com.theacar.model.Mood
import com.theacar.model.Weather
import com.theacar.music.MusicRecommender
import com.theacar.music.PlaybackGateway
import com.theacar.music.TrackKind
import com.theacar.music.TrackSuggestion
import com.theacar.route.NavigationGateway
import com.theacar.route.RouteIdea
import com.theacar.route.RouteRecommender
import com.theacar.route.RouteRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSessionTest {
    @Test
    fun failedPlaybackDoesNotReportPlayingAndCanBeRetried() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.player.fail = true
        fixture.session.togglePlayback().join()

        assertFalse(fixture.session.state.value.isPlaying)
        assertEquals(DriveFailure.PLAYBACK, fixture.session.state.value.failure)

        fixture.player.fail = false
        fixture.session.togglePlayback().join()
        assertTrue(fixture.session.state.value.isPlaying)
        assertNull(fixture.session.state.value.failure)
    }

    @Test
    fun rapidPlaybackCommandsStayInOrder() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.session.togglePlayback()
        fixture.session.togglePlayback()
        runCurrent()

        assertEquals(listOf("volume", "play", "pause"), fixture.player.calls)
        assertFalse(fixture.session.state.value.isPlaying)
    }

    @Test
    fun conversationChangesVolumeWithoutRequestingAnotherRoute() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.session.togglePlayback().join()
        val previousVolume = fixture.session.state.value.volume
        fixture.source.context.value = fixture.source.context.value.copy(conversationLevel = 0.95f)
        runCurrent()

        assertTrue(fixture.session.state.value.volume < previousVolume)
        assertEquals(1, fixture.routes.calls)
        assertEquals(1, fixture.player.calls.count { it == "play" })
    }

    @Test
    fun dismissingWindowSuggestionDoesNotChangeLocationOrMusic() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val before = fixture.session.state.value
        fixture.session.dismissCabinSuggestion().join()
        fixture.source.context.value = before.context.copy(conversationLevel = 0.4f)
        runCurrent()

        assertNull(fixture.session.state.value.cabinSuggestion)
        assertEquals(before.context.coastDistanceKm, fixture.source.context.value.coastDistanceKm)
        assertEquals(before.selectedTrack, fixture.session.state.value.selectedTrack)
    }

    @Test
    fun activeRouteSurvivesNewSuggestionsAndFailedNavigation() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.navigator.fail = true
        fixture.session.startRoute().join()
        assertNull(fixture.session.state.value.activeRoute)
        assertEquals(DriveFailure.NAVIGATION, fixture.session.state.value.failure)

        fixture.navigator.fail = false
        fixture.session.startRoute().join()
        val active = fixture.session.state.value.activeRoute
        fixture.session.selectMode(DriveMode.DATE).join()
        runCurrent()

        assertEquals(active, fixture.session.state.value.activeRoute)
        assertEquals("DATE", fixture.session.state.value.suggestedRoute?.title)
        assertEquals(1, fixture.navigator.starts)
    }

    @Test
    fun slowRouteRequestsDoNotBlockPlaybackAndOldRequestsAreCancelled() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.routes.gate = CompletableDeferred()
        runCurrent()
        assertTrue(fixture.session.state.value.isRouteLoading)

        fixture.session.togglePlayback().join()
        assertTrue(fixture.session.state.value.isPlaying)
        fixture.session.selectMode(DriveMode.DATE).join()
        runCurrent()
        fixture.routes.gate!!.complete(Unit)
        runCurrent()

        assertEquals("DATE", fixture.session.state.value.suggestedRoute?.title)
        assertFalse(fixture.session.state.value.isRouteLoading)
        assertEquals(1, fixture.routes.cancelled)
    }

    @Test
    fun failedRouteRequestCanBeRetried() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.routes.fail = true
        runCurrent()
        assertEquals(DriveFailure.ROUTE_RECOMMENDATION, fixture.session.state.value.failure)
        assertFalse(fixture.session.state.value.isRouteLoading)

        fixture.routes.fail = false
        fixture.session.retrySuggestions().join()
        runCurrent()
        assertEquals("GOALLESS", fixture.session.state.value.suggestedRoute?.title)
        assertNull(fixture.session.state.value.failure)
    }

    @Test
    fun closingSessionStopsEnvironmentalUpdates() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val before = fixture.session.state.value
        fixture.session.close()
        fixture.source.context.value = before.context.copy(weather = Weather.RAIN)
        runCurrent()
        assertEquals(before, fixture.session.state.value)
    }

    private class Fixture(scope: CoroutineScope) {
        val source = Source()
        val routes = Routes()
        val player = Player()
        val navigator = Navigator()
        val session = DriveSession(
            source, routes, Music(), player, navigator, scope,
        )
    }

    private class Source : DriveContextSource {
        override val context = MutableStateFlow(
            DriveContext(
                "横浜", Coordinate(35.44, 139.63), Weather.CLEAR, Mood.CALM,
                conversationLevel = 0.1f, coastDistanceKm = 1.6, localHour = 17,
            )
        )
    }

    private class Routes : RouteRecommender {
        var calls = 0
        var cancelled = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun recommend(request: RouteRequest): RouteIdea {
            calls++
            try {
                gate?.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw error
            }
            check(!fail) { "Route unavailable" }
            return RouteIdea(request.mode.name, "", 30, 20.0, emptyList())
        }
    }

    private class Music : MusicRecommender {
        override fun recommend(mode: DriveMode, context: DriveContext) =
            TrackSuggestion("Coast", "Artist", "Near the sea", TrackKind.COASTAL)
    }

    private class Player : PlaybackGateway {
        val calls = mutableListOf<String>()
        var fail = false
        override suspend fun play(track: TrackSuggestion) {
            check(!fail) { "Player unavailable" }
            calls += "play"
        }
        override suspend fun pause() { calls += "pause" }
        override suspend fun setVolume(volume: Float) { calls += "volume" }
    }

    private class Navigator : NavigationGateway {
        var fail = false
        var starts = 0
        override suspend fun start(route: RouteIdea) {
            check(!fail) { "Navigation unavailable" }
            starts++
        }
        override suspend fun stop() = Unit
    }
}
