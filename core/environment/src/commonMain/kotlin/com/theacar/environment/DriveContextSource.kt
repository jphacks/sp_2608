package com.theacar.environment

import com.theacar.model.DriveContext
import kotlinx.coroutines.flow.StateFlow

interface DriveContextSource {
    val context: StateFlow<DriveContext>
}
