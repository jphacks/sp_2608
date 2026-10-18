package com.theacar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import com.theacar.ui.resources.Res
import com.theacar.ui.resources.noto_sans_jp
import org.jetbrains.compose.resources.Font as resourceFont

@Composable
internal actual fun notoSansJpFont(weight: FontWeight): Font = resourceFont(Res.font.noto_sans_jp, weight)
