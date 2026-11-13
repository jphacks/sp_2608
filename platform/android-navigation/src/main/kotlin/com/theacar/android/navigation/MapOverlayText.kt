package com.theacar.android.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// 地図の上に重ねる部品は、地図を隠しすぎないよう文字を一回り小さくする
private const val SMALLER_BY = 2f

@Composable
internal fun MapOverlayText(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = MaterialTheme.typography.smaller(), content = content)
}

private fun Typography.smaller(): Typography {
    fun TextStyle.s() = copy(fontSize = (fontSize.value - SMALLER_BY).sp, lineHeight = (lineHeight.value - SMALLER_BY).sp)
    return copy(
        displayLarge = displayLarge.s(), displayMedium = displayMedium.s(), displaySmall = displaySmall.s(),
        headlineLarge = headlineLarge.s(), headlineMedium = headlineMedium.s(), headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(), titleMedium = titleMedium.s(), titleSmall = titleSmall.s(),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s(), labelMedium = labelMedium.s(), labelSmall = labelSmall.s(),
    )
}
