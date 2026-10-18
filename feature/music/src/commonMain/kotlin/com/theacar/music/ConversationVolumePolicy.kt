package com.theacar.music

import com.theacar.model.DriveContext
import com.theacar.model.UnusualSound

class ConversationVolumePolicy {
    fun volume(context: DriveContext): Float {
        val conversationDucking = context.conversationLevel.coerceIn(0f, 1f) * 0.55f
        val base = if (context.unusualSound == UnusualSound.FLATULENCE) 0.82f else 0.72f
        return (base - conversationDucking).coerceIn(0.18f, 0.9f)
    }


}
