package com.theacar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

// Jost と同じく、可変フォントを太さごとに使い分けるため assets から直接読む
@Composable
internal actual fun notoSansJpFont(weight: FontWeight): Font = Font(
    path = "composeResources/com.theacar.ui.resources/font/noto_sans_jp.ttf",
    assetManager = LocalContext.current.assets,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)
