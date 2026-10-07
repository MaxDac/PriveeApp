package com.privee.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun PriveeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
