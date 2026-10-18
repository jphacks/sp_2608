package com.theacar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import com.theacar.ui.resources.Res
import com.theacar.ui.resources.jost
import org.jetbrains.compose.resources.Font as resourceFont

@Composable
internal actual fun jostFont(weight: FontWeight): Font = resourceFont(Res.font.jost, weight)
