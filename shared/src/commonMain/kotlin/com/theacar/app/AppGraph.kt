package com.theacar.app

import com.theacar.demo.DemoDriveContextSource
import com.theacar.demo.DemoAudioInput
import com.theacar.demo.DemoCameraInput
import com.theacar.demo.DemoAudioInference
import com.theacar.demo.DemoSceneInference
import com.theacar.demo.DemoMusicRecommender
import com.theacar.demo.DemoNavigationGateway
import com.theacar.demo.DemoPlaybackGateway
import com.theacar.demo.DemoRouteRecommender
import com.theacar.drive.DriveSession
import com.theacar.environment.DriveContextSource
import com.theacar.music.MusicRecommender
import com.theacar.music.PlaybackGateway
import com.theacar.inference.InferenceEngine
import com.theacar.perception.AudioAssessment
import com.theacar.perception.SceneAssessment
import com.theacar.perception.PerceptionPipeline
import com.theacar.perception.PerceptionPolicy
import com.theacar.perception.PerceptionState
import com.theacar.perception.data.PerceptionContextSource
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.CameraFrame
import com.theacar.sensing.CaptureClock
import com.theacar.sensing.SensorSource
import com.theacar.sensing.SessionClock
import com.theacar.sensing.SharedSensorFeed
import com.theacar.route.NavigationGateway
import com.theacar.route.RouteRecommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

data class PerceptionDependencies(
    val clock: CaptureClock,
    val audio: SensorSource<AudioFrame>? = null,
    val camera: SensorSource<CameraFrame>? = null,
    val audioEngine: InferenceEngine<AudioFrame, AudioAssessment>? = null,
    val cameraEngine: InferenceEngine<CameraFrame, SceneAssessment>? = null,
    val policy: PerceptionPolicy = PerceptionPolicy(),
    val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
)

data class DemoControls(
    val setConversationLevel: (Float) -> Unit,
    val emitUnusualSound: () -> Unit,
    val cycleScene: () -> Unit,
)

class AppGraph(
    source: DriveContextSource,
    routes: RouteRecommender,
    music: MusicRecommender,
    playback: PlaybackGateway,
    navigation: NavigationGateway,
    val demoControls: DemoControls? = null,
    perception: PerceptionDependencies? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pipeline = perception?.let {
        PerceptionPipeline(
            audio = it.audio?.let { input -> SharedSensorFeed(input, scope) },
            camera = it.camera?.let { input -> SharedSensorFeed(input, scope) },
            audioEngine = it.audioEngine,
            cameraEngine = it.cameraEngine,
            scope = scope,
            clock = it.clock,
            policy = it.policy,
            inferenceDispatcher = it.inferenceDispatcher,
        )
    }
    val perceptionState: StateFlow<PerceptionState>? get() = pipeline?.state
    private val driveContext = pipeline?.let {
        PerceptionContextSource(source, it.state, scope)
    } ?: source
    val driveSession = DriveSession(
        source = driveContext,
        routes = routes,
        music = music,
        playback = playback,
        navigation = navigation,
        scope = scope,
    )

    fun close() {
        driveSession.close()
        pipeline?.close()
        scope.cancel()
    }

    companion object {
        fun demo(): AppGraph {
            val source = DemoDriveContextSource()
            val clock = SessionClock()
            val audio = DemoAudioInput(clock)
            val camera = DemoCameraInput(clock)
            return AppGraph(
                source = source,
                routes = DemoRouteRecommender(),
                music = DemoMusicRecommender(),
                playback = DemoPlaybackGateway(),
                navigation = DemoNavigationGateway(),
                demoControls = DemoControls(audio::setConversationLevel, audio::emitUnusualSound, camera::cycleScene),
                perception = PerceptionDependencies(
                    clock = clock,
                    audio = audio,
                    camera = camera,
                    audioEngine = DemoAudioInference(),
                    cameraEngine = DemoSceneInference(),
                ),
            )
        }
    }
}
