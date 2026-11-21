package app.viewfinder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.viewfinder.R

/**
 * Jade on obsidian: green-black surfaces, cool ivory text and a jade accent, with a faint gold note
 * ([Gold]) kept for the background glow. Normal text stays at ≥ 4.5:1 on [Background] (secondary 9.8:1,
 * muted 6.3:1); the accent carries near-black text (8:1) for the same reason.
 */
object Palette {
    val Background = Color(0xFF121614)
    val Surface = Color(0xFF1A1F1C)
    val Raised = Color(0xFF232925)
    val Overlay = Color(0xFF2C332E)
    val Hairline = Color(0x1FDDE5DD)
    val Text = Color(0xFFF2F5F0)
    val TextSecondary = Color(0xFFB9C2BA)
    val Muted = Color(0xFF8E9990)
    val Accent = Color(0xFF4FC28E)
    val AccentPressed = Color(0xFF3FA97A)
    val OnAccent = Color(0xFF08130D)
    val AccentSoft = Color(0x294FC28E)
    /** Deep teal and old gold, only for the connect screen's ambient glow. */
    val DeepTeal = Color(0xFF1F6F5C)
    val Gold = Color(0xFFB89B5E)
    val Danger = Color(0xFFEF8A7A)
    /** Lighter and paler than [Accent], so "saved" reads as a state, not a button. */
    val Success = Color(0xFFA3DDBC)
}

/** Spacing and size tokens: an 8dp rhythm, 48dp minimum touch targets. */
object Dimens {
    val Gutter = 16.dp
    val Touch = 48.dp
    val IconSm = 18.dp
    val Icon = 22.dp
    val Radius = 16.dp
    val RadiusLg = 24.dp
}

private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** UI text: Inter. */
val Sans = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

/** Headings: a book serif, as in the Claude app. */
val Serif = FontFamily(
    variable(R.font.source_serif, 400),
    variable(R.font.source_serif, 500),
    variable(R.font.source_serif, 600),
)

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.TextSecondary,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Raised,
    surfaceContainerHighest = Palette.Overlay,
    outline = Palette.Hairline,
    outlineVariant = Palette.Hairline,
    error = Palette.Danger,
    scrim = Color.Black,
)

private val base = Typography()

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight(500), fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight(500), fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight(500), fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight(500), fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(700), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
    // Unused roles keep Material defaults but in our families.
    displayLarge = base.displayLarge.copy(fontFamily = Serif),
    displayMedium = base.displayMedium.copy(fontFamily = Serif),
    headlineLarge = base.headlineLarge.copy(fontFamily = Serif),
)

@Composable
fun ViewfinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type) {
        CompositionLocalProvider(LocalContentColor provides Palette.Text, LocalMotion provides rememberMotion(), content = content)
    }
}

/** Digits all the same width, for numbers that change while you watch (counts, timers, percentages). */
fun androidx.compose.ui.text.TextStyle.tabular(): androidx.compose.ui.text.TextStyle = copy(fontFeatureSettings = "tnum")
