package com.theacar.android.navigation

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface NavigationTripRecording {
    val lastSavedId: StateFlow<String?>
    val saveFailed: StateFlow<Boolean>
    fun retry()
    fun open(context: Context, id: String)
}
