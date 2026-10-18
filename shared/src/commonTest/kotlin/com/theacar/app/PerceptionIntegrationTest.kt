package com.theacar.app

import com.theacar.demo.DemoAudioInference
import com.theacar.demo.DemoAudioInput
import com.theacar.demo.DemoCameraInput
import com.theacar.demo.DemoDriveContextSource
import com.theacar.demo.DemoMusicRecommender
import com.theacar.demo.DemoNavigationGateway
import com.theacar.demo.DemoPlaybackGateway
import com.theacar.demo.DemoRouteRecommender
import com.theacar.demo.DemoSceneInference
import com.theacar.model.Scenery
import com.theacar.model.UnusualSound
import com.theacar.music.TrackKind
import com.theacar.sensing.CaptureClock
import com.theacar.sensing.InputStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PerceptionIntegrationTest {
    @Test
    fun syntheticInputsReachDriveDecisionsAndCloseReleasesCapture() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val clock = CaptureClock { testScheduler.currentTime }
        val audio = DemoAudioInput(clock)
        val camera = DemoCameraInput(clock)
        val graph = AppGraph(
            DemoDriveContextSource(), DemoRouteRecommender(), DemoMusicRecommender(),
            DemoPlaybackGateway(), DemoNavigationGateway(),
            perception = PerceptionDependencies(
                clock, audio, camera, DemoAudioInference(), DemoSceneInference(),
                inferenceDispatcher = dispatcher,
            ),
        )
        try {
            runCurrent()
            val originalVolume = graph.driveSession.state.value.volume
            audio.setConversationLevel(0.9f)
            runCurrent()
            assertEquals(0.9f, graph.driveSession.state.value.context.conversationLevel)
            assertTrue(graph.driveSession.state.value.volume < originalVolume)

            audio.emitUnusualSound()
            runCurrent()
            assertEquals(TrackKind.COMEDY, graph.driveSession.state.value.selectedTrack.kind)
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(UnusualSound.NONE, graph.driveSession.state.value.context.unusualSound)
            assertEquals(TrackKind.COASTAL, graph.driveSession.state.value.selectedTrack.kind)

            camera.cycleScene()
            runCurrent()
            assertEquals(Scenery.COASTAL, graph.driveSession.state.value.context.scenery)
            assertEquals(1.6, graph.driveSession.state.value.context.coastDistanceKm)
        } finally {
            graph.close()
            runCurrent()
            Dispatchers.resetMain()
        }
        assertEquals(InputStatus.Idle, audio.status.value)
        assertEquals(InputStatus.Idle, camera.status.value)
    }
}
