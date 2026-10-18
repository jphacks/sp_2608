package com.theacar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// 旅れこのカラートークン。画面の見出し帯ごとに1色（DRIVE=Sky、RECORD=Sunset）。
val Sky = Color(0xFF75B1A9)
val Sunglow = Color(0xFFD9B44A)
val Sunset = Color(0xFFEB6B40)
// RECORD の「RECO」に使う、帯と同系色の濃い色
val SunsetDeep = Color(0xFF6E1F08)
val Mountains = Color(0xFF4F6457)
val Mist = Color(0xFFACD0C0)
val Paper = Color(0xFFF5F3EC)
val Surface = Color(0xFFFFFFFF)
val Ink = Color(0xFF1E2A24)
val Muted = Color(0xFF5C6B63)
val Danger = Color(0xFFB3261E)
// 景色の選び方の色。海沿い=Sea、自然（海と森林と草地の混ぜ合わせ）=Meadow、森林は Sky
val Sea = Color(0xFF6FA6D6)
val Meadow = Color(0xFFB9C97A)
// 湖・川
val Lake = Color(0xFF8FD0D8)
// エラーの面。Surface と同じ値にすると、白いカードの文字までエラー色になる
val DangerTint = Color(0xFFFBE9E7)

// 余白と角丸
val CardCorner = 14.dp
val ChipCorner = 6.dp
val CardGap = 16.dp
val SectionGap = 32.dp

/** 数字・英字用の Jost のスタイル群。 */
class JostTypography(
    val stat: TextStyle,
    val statUnit: TextStyle,
    val label: TextStyle,
    val date: TextStyle,
    val cardTitle: TextStyle,
)

private val LocalJostTypography = staticCompositionLocalOf<JostTypography> { error("TheaCarTheme が必要です") }

val MaterialTheme.jost: JostTypography
    @Composable @ReadOnlyComposable get() = LocalJostTypography.current

@Composable
internal expect fun jostFont(weight: FontWeight): Font

@Composable
private fun jostFamily(): FontFamily = FontFamily(
    jostFont(FontWeight.Normal), jostFont(FontWeight.Medium), jostFont(FontWeight.SemiBold), jostFont(FontWeight.Bold),
    jostFont(FontWeight.Black),
)

@Composable
internal expect fun notoSansJpFont(weight: FontWeight): Font

@Composable
private fun notoSansJpFamily(): FontFamily = FontFamily(
    notoSansJpFont(FontWeight.Normal), notoSansJpFont(FontWeight.Medium), notoSansJpFont(FontWeight.SemiBold),
    notoSansJpFont(FontWeight.Bold),
)

// 端末の言語が日本語でなくても日本語の字形で出すよう、全スタイルを Noto Sans JP と ja に揃える
private fun japaneseTypography(family: FontFamily): Typography {
    val ja = TextStyle(fontFamily = family, localeList = LocaleList("ja-JP"))
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.merge(ja), displayMedium = t.displayMedium.merge(ja), displaySmall = t.displaySmall.merge(ja),
        headlineLarge = t.headlineLarge.merge(ja), headlineMedium = t.headlineMedium.merge(ja), headlineSmall = t.headlineSmall.merge(ja),
        titleLarge = t.titleLarge.merge(ja), titleMedium = t.titleMedium.merge(ja), titleSmall = t.titleSmall.merge(ja),
        bodyLarge = t.bodyLarge.merge(ja), bodyMedium = t.bodyMedium.merge(ja), bodySmall = t.bodySmall.merge(ja),
        labelLarge = t.labelLarge.merge(ja), labelMedium = t.labelMedium.merge(ja), labelSmall = t.labelSmall.merge(ja),
    )
}

@Composable
fun TheaCarTheme(content: @Composable () -> Unit) {
    val jost = jostFamily()
    val jostTypography = JostTypography(
        stat = TextStyle(fontFamily = jost, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, fontFeatureSettings = "tnum", color = Ink),
        statUnit = TextStyle(fontFamily = jost, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, color = Ink),
        label = TextStyle(fontFamily = jost, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em, color = Muted),
        date = TextStyle(fontFamily = jost, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = Ink),
        cardTitle = TextStyle(fontFamily = jost, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, color = Ink),
    )
    val base = japaneseTypography(notoSansJpFamily())
    CompositionLocalProvider(LocalJostTypography provides jostTypography) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Mountains, onPrimary = Surface,
                primaryContainer = Mist, onPrimaryContainer = Ink,
                secondary = Sky, onSecondary = Ink,
                secondaryContainer = Sky, onSecondaryContainer = Ink,
                tertiary = Sunglow, onTertiary = Ink,
                error = Danger, onError = Surface,
                errorContainer = DangerTint, onErrorContainer = Danger,
                background = Paper, surface = Surface,
                surfaceContainerLow = Surface, surfaceContainer = Surface,
                surfaceContainerHigh = Surface, surfaceContainerHighest = Mist,
                surfaceVariant = Mist, onSurfaceVariant = Muted,
                outline = Muted, outlineVariant = Mist,
                onBackground = Ink, onSurface = Ink,
            ),
            typography = base.copy(
                headlineSmall = base.headlineSmall.copy(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
                titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
                bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
                labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
                bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, color = Muted),
            ),
            content = content,
        )
    }
}
