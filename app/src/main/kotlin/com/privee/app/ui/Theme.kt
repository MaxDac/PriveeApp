package com.privee.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privee.app.AppAccent
import kotlin.math.abs

private val Violet = Color(0xFF7C5CFF)
private val VioletLight = Color(0xFFB4A3FF)
private val Ink = Color(0xFF0F1020)

private val DarkColors = darkColorScheme(
    primary = VioletLight,
    onPrimary = Color(0xFF22106B),
    primaryContainer = Color(0xFF3A2A8C),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF7FD8C6),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1F4D45),
    onSecondaryContainer = Color(0xFFB9F2E5),
    background = Ink,
    onBackground = Color(0xFFE5E3F0),
    surface = Ink,
    onSurface = Color(0xFFE5E3F0),
    surfaceVariant = Color(0xFF262842),
    onSurfaceVariant = Color(0xFFC6C4D8),
    surfaceContainer = Color(0xFF181A2E),
    surfaceContainerHigh = Color(0xFF202238),
    surfaceContainerHighest = Color(0xFF2A2C44),
    outline = Color(0xFF8F8DA5),
    outlineVariant = Color(0xFF3B3D57),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF22106B),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F2E5),
    onSecondaryContainer = Color(0xFF00201A),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE5E1F0),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainer = Color(0xFFF1EDF8),
    surfaceContainerHigh = Color(0xFFEBE7F3),
    surfaceContainerHighest = Color(0xFFE5E1ED),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Monospace style for safety numbers. */
val SafetyNumberStyle = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 18.sp,
    lineHeight = 28.sp,
    letterSpacing = 1.sp,
)

/** The accent-specific roles of a colour scheme. */
internal data class AccentTones(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

internal data class AccentPalette(val light: AccentTones, val dark: AccentTones)

private fun palette(
    light: Long, lightOn: Long, lightContainer: Long, lightOnContainer: Long,
    dark: Long, darkOn: Long, darkContainer: Long, darkOnContainer: Long,
) = AccentPalette(
    AccentTones(Color(light), Color(lightOn), Color(lightContainer), Color(lightOnContainer)),
    AccentTones(Color(dark), Color(darkOn), Color(darkContainer), Color(darkOnContainer)),
)

internal fun accentPalette(accent: AppAccent): AccentPalette = when (accent) {
    AppAccent.Lilac -> palette(0xFF7C5CFF, 0xFFFFFFFF, 0xFFE6DEFF, 0xFF22106B, 0xFFB4A3FF, 0xFF22106B, 0xFF3A2A8C, 0xFFE6DEFF)
    AppAccent.Blue -> palette(0xFF2B5CC8, 0xFFFFFFFF, 0xFFDAE2FF, 0xFF001849, 0xFFB1C5FF, 0xFF002C71, 0xFF1E4296, 0xFFDAE2FF)
    AppAccent.Teal -> palette(0xFF006A60, 0xFFFFFFFF, 0xFF9EF2E3, 0xFF00201C, 0xFF82D5C7, 0xFF003731, 0xFF005048, 0xFF9EF2E3)
    AppAccent.Green -> palette(0xFF386A20, 0xFFFFFFFF, 0xFFB8F397, 0xFF042100, 0xFF9DD67D, 0xFF0C3900, 0xFF205107, 0xFFB8F397)
    AppAccent.Amber -> palette(0xFF785900, 0xFFFFFFFF, 0xFFFFDF9E, 0xFF261A00, 0xFFFABD00, 0xFF3F2E00, 0xFF5B4300, 0xFFFFDF9E)
    AppAccent.Orange -> palette(0xFF9C4400, 0xFFFFFFFF, 0xFFFFDBC8, 0xFF321300, 0xFFFFB68B, 0xFF532200, 0xFF763300, 0xFFFFDBC8)
    AppAccent.Red -> palette(0xFFB8233A, 0xFFFFFFFF, 0xFFFFDADC, 0xFF40000F, 0xFFFFB2B8, 0xFF67001B, 0xFF911A2C, 0xFFFFDADC)
    AppAccent.Pink -> palette(0xFF984061, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E001D, 0xFFFFB0C8, 0xFF5E1133, 0xFF7B2949, 0xFFFFD9E2)
}

/** The colour shown for [accent] in the settings. */
fun accentSwatch(accent: AppAccent): Color = accentPalette(accent).light.primary

internal fun Color.hue(): Float {
    val max = maxOf(red, green, blue)
    val delta = max - minOf(red, green, blue)
    if (delta == 0f) return 0f
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}

/** Same saturation and lightness, with [hue]: the neutrals keep their tint strength, in the accent's hue. */
internal fun Color.withHue(hue: Float): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    val saturation = if (delta == 0f) 0f else delta / (1f - abs(2f * lightness - 1f))
    return Color.hsl(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f), alpha)
}

internal fun colorSchemeFor(accent: AppAccent, dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    if (accent == AppAccent.Lilac) return base
    val palette = accentPalette(accent)
    val tones = if (dark) palette.dark else palette.light
    val hue = palette.light.primary.hue()
    fun Color.tinted() = withHue(hue)
    return base.copy(
        primary = tones.primary,
        onPrimary = tones.onPrimary,
        primaryContainer = tones.primaryContainer,
        onPrimaryContainer = tones.onPrimaryContainer,
        surfaceTint = tones.primary,
        background = base.background.tinted(),
        onBackground = base.onBackground.tinted(),
        surface = base.surface.tinted(),
        onSurface = base.onSurface.tinted(),
        surfaceVariant = base.surfaceVariant.tinted(),
        onSurfaceVariant = base.onSurfaceVariant.tinted(),
        surfaceBright = base.surfaceBright.tinted(),
        surfaceDim = base.surfaceDim.tinted(),
        surfaceContainerLowest = base.surfaceContainerLowest.tinted(),
        surfaceContainerLow = base.surfaceContainerLow.tinted(),
        surfaceContainer = base.surfaceContainer.tinted(),
        surfaceContainerHigh = base.surfaceContainerHigh.tinted(),
        surfaceContainerHighest = base.surfaceContainerHighest.tinted(),
        inverseSurface = base.inverseSurface.tinted(),
        inverseOnSurface = base.inverseOnSurface.tinted(),
        outline = base.outline.tinted(),
        outlineVariant = base.outlineVariant.tinted(),
    )
}

@Composable
fun PriveeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accent: AppAccent = AppAccent.Default,
    content: @Composable () -> Unit,
) {
    val colors = remember(accent, dark) { colorSchemeFor(accent, dark) }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
