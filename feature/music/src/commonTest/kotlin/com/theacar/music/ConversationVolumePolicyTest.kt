package com.theacar.music

import com.theacar.model.Coordinate
import com.theacar.model.DriveContext
import com.theacar.model.Mood
import com.theacar.model.Weather
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationVolumePolicyTest {
    private val policy = ConversationVolumePolicy()
    private val context = DriveContext(
        "横浜", Coordinate(35.44, 139.63), Weather.CLEAR, Mood.CALM, 0f, null, 17,
    )

    @Test
    fun speechDucksMusicAndQuietRestoresVolume() {
        val quiet = policy.volume(context)
        val speech = policy.volume(context.copy(conversationLevel = 0.9f))
        assertTrue(speech < quiet)
        assertEquals(quiet, policy.volume(context.copy(conversationLevel = -1f)))
        assertEquals(
            policy.volume(context.copy(conversationLevel = 1f)),
            policy.volume(context.copy(conversationLevel = 5f)),
        )
    }
}
