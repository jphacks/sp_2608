package com.theacar.perception.data

import com.theacar.environment.DriveContextSource
import com.theacar.model.DriveContext
import com.theacar.model.Scenery
import com.theacar.model.UnusualSound
import com.theacar.perception.PerceptionState
import com.theacar.perception.SceneType
import com.theacar.perception.SoundType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PerceptionContextSource(
    environment: DriveContextSource,
    perception: StateFlow<PerceptionState>,
    scope: CoroutineScope,
) : DriveContextSource {
    override val context: StateFlow<DriveContext> =
        combine(environment.context, perception, ::fuse)
            .stateIn(
                scope, SharingStarted.Eagerly,
                fuse(environment.context.value, perception.value),
            )
}

private fun fuse(environment: DriveContext, perception: PerceptionState): DriveContext =
    environment.copy(
        conversationLevel = perception.audio?.value?.conversationLevel ?: environment.conversationLevel,
        unusualSound = when (perception.soundEvent?.type) {
            SoundType.FLATULENCE -> UnusualSound.FLATULENCE
            else -> environment.unusualSound
        },
        scenery = when (perception.scene?.value?.scene) {
            SceneType.COASTAL -> Scenery.COASTAL
            SceneType.FOREST -> Scenery.FOREST
            SceneType.URBAN -> Scenery.URBAN
            else -> environment.scenery
        },
    )
