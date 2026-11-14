package com.kaia.meshvpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette Kaia Mesh : obsidienne + jade (identique aux masques de l'app).
val Jade = Color(0xFF2BB673)
val JadeDim = Color(0xFF1E8C57)
val JadeSoft = Color(0xFF5FE0A0)
val Obsidian = Color(0xFF0B0F14)
val Surface1 = Color(0xFF141B23)
val Surface2 = Color(0xFF1C2632)
val Outline = Color(0xFF2E3B4A)
val Ink = Color(0xFFE9F1F7)
val Muted = Color(0xFF93A4B5)
val Danger = Color(0xFFE5484D)
val Amber = Color(0xFFD9A233)

private val DarkScheme = darkColorScheme(
    primary = Jade,
    onPrimary = Color(0xFF04120B),
    primaryContainer = JadeDim,
    onPrimaryContainer = Ink,
    secondary = JadeSoft,
    onSecondary = Color(0xFF04120B),
    background = Obsidian,
    onBackground = Ink,
    surface = Surface1,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Outline,
    error = Danger,
    onError = Ink,
)

private val LightScheme = lightColorScheme(
    primary = JadeDim,
    onPrimary = Color.White,
    background = Color(0xFFF5F8FA),
    onBackground = Color(0xFF0F1720),
    surface = Color.White,
    onSurface = Color(0xFF0F1720),
    surfaceVariant = Color(0xFFE7EDF3),
    onSurfaceVariant = Color(0xFF4A5A6A),
    outline = Color(0xFFC6D2DD),
    error = Danger,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun KaiaMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

/** Colour associated with a log level. */
fun levelColor(level: String): Color = when (level.uppercase()) {
    "ERROR" -> Danger
    "WARN" -> Amber
    "OK" -> Jade
    else -> Muted
}
