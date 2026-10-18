package com.theacar.demo

import com.theacar.inference.ExecutionLocation
import com.theacar.inference.InferenceEngine
import com.theacar.inference.ModelDescriptor
import com.theacar.inference.Prediction
import com.theacar.perception.AudioAssessment
import com.theacar.perception.SceneAssessment
import com.theacar.perception.SceneType
import com.theacar.perception.SoundType
import com.theacar.sensing.AudioFrame
import com.theacar.sensing.CameraFrame

class DemoAudioInference : InferenceEngine<AudioFrame, AudioAssessment> {
    override val model = ModelDescriptor("demo-audio-marker", "1", ExecutionLocation.ON_DEVICE)
    override suspend fun infer(input: AudioFrame): Prediction<AudioAssessment> = Prediction(
        AudioAssessment(
            conversationLevel = input.sampleAt(1),
            sound = if (input.sampleAt(0) == -1f) SoundType.FLATULENCE else SoundType.NONE,
        ),
        confidence = 1f,
    )
}

class DemoSceneInference : InferenceEngine<CameraFrame, SceneAssessment> {
    override val model = ModelDescriptor("demo-scene-marker", "1", ExecutionLocation.ON_DEVICE)
    override suspend fun infer(input: CameraFrame): Prediction<SceneAssessment> = Prediction(
        SceneAssessment(SceneType.entries.getOrElse(input.channelAt(0)) { SceneType.UNKNOWN }),
        confidence = 1f,
    )
}
