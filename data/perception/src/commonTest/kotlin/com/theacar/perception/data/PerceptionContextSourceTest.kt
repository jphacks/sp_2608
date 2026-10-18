package com.theacar.perception.data

import com.theacar.environment.DriveContextSource
import com.theacar.inference.ExecutionLocation
import com.theacar.inference.ModelDescriptor
import com.theacar.model.Coordinate
import com.theacar.model.DriveContext
import com.theacar.model.Mood
import com.theacar.model.Scenery
import com.theacar.model.UnusualSound
import com.theacar.model.Weather
import com.theacar.perception.AudioAssessment
import com.theacar.perception.Observation
import com.theacar.perception.PerceptionState
import com.theacar.perception.SceneAssessment
import com.theacar.perception.SceneType
import com.theacar.perception.SoundEvent
import com.theacar.perception.SoundType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PerceptionContextSourceTest {
    @Test
    fun cameraHintDoesNotInventDistanceOrOverrideMeasuredWeather() = runTest {
        val environment = Source()
        val perception = MutableStateFlow(
            PerceptionState(scene = observe(SceneAssessment(SceneType.COASTAL)))
        )
        val source = PerceptionContextSource(environment, perception, backgroundScope)
        runCurrent()

        assertEquals(Scenery.COASTAL, source.context.value.scenery)
        assertNull(source.context.value.coastDistanceKm)
        assertEquals(Weather.RAIN, source.context.value.weather)
        assertEquals(environment.context.value.coordinate, source.context.value.coordinate)
    }

    @Test
    fun eventExpiryRestoresMusicContextEvenWhenSoundClassificationPersists() = runTest {
        val assessment = observe(AudioAssessment(0.8f, SoundType.FLATULENCE))
        val perception = MutableStateFlow(
            PerceptionState(audio = assessment, soundEvent = SoundEvent(1, SoundType.FLATULENCE, 0))
        )
        val source = PerceptionContextSource(Source(), perception, backgroundScope)
        runCurrent()
        assertEquals(UnusualSound.FLATULENCE, source.context.value.unusualSound)

        perception.value = perception.value.copy(soundEvent = null)
        runCurrent()
        assertEquals(UnusualSound.NONE, source.context.value.unusualSound)
        assertEquals(0.8f, source.context.value.conversationLevel)
        assertEquals(Mood.CALM, source.context.value.mood)
    }

    @Test
    fun expiredObservationsFallBackToEnvironmentalDefaults() = runTest {
        val environment = Source()
        val perception = MutableStateFlow(
            PerceptionState(audio = observe(AudioAssessment(0.9f)))
        )
        val source = PerceptionContextSource(environment, perception, backgroundScope)
        runCurrent()
        perception.value = PerceptionState()
        runCurrent()
        assertEquals(environment.context.value, source.context.value)
    }

    private class Source : DriveContextSource {
        override val context = MutableStateFlow(
            DriveContext("横浜", Coordinate(35.44, 139.63), Weather.RAIN, Mood.CALM, 0f, null, 17)
        )
    }
}

private fun <T> observe(value: T) = Observation(
    value, 0.9f, 0, ModelDescriptor("test", "1", ExecutionLocation.ON_DEVICE),
)
