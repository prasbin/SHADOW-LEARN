package com.prasbin.shadowlearn.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Solo-Leveling-inspired atmosphere: deep dark blue, arcane blue/purple
// accents. Original palette, no copied assets.
val ShadowBackground = Color(0xFF0A0E1A)
val ShadowSurface = Color(0xFF121829)
val ShadowSurfaceVariant = Color(0xFF1A2340)
val AccentBlue = Color(0xFF7C8CFF)
val AccentPurple = Color(0xFF9D7BFF)
val AccentTeal = Color(0xFF5EEAD4)
val TextPrimary = Color(0xFFE8ECF8)
val TextSecondary = Color(0xFF9AA3C0)
/** Restrained warning amber: AGAIN ratings and genuine attention states only. */
val WarningAmber = Color(0xFFD9A13B)

private val DarkScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF060913),
    secondary = AccentPurple,
    onSecondary = Color(0xFF060913),
    tertiary = AccentTeal,
    background = ShadowBackground,
    onBackground = TextPrimary,
    surface = ShadowSurface,
    onSurface = TextPrimary,
    surfaceVariant = ShadowSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF2A3560)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4353C8),
    secondary = Color(0xFF6D4FD0),
    tertiary = Color(0xFF0E7C6B)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun ShadowLearnTheme(darkMode: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkMode) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
