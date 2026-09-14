package com.example.ialocal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ialocal.data.ThemeMode

object NexusColors {
    val Brand = Color(0xFF6366F1)
    val BrandStrong = Color(0xFF4F46E5)
    val BrandDeep = Color(0xFF4338CA)
    val Purple = Color(0xFFA855F7)
    val Emerald = Color(0xFF10B981)
    val Pink = Color(0xFFEC4899)

    val Surface900 = Color(0xFF0A0C12)
    val Surface850 = Color(0xFF10121A)
    val Surface800 = Color(0xFF161822)
    val Surface750 = Color(0xFF1E222D)
    val Border = Color(0xFF293142)
    val BorderSoft = Color(0xFF1E293B)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF94A3B8)
}

private val NexusDarkColorScheme = darkColorScheme(
    primary = NexusColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF25255A),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF160B2F),
    secondaryContainer = Color(0xFF2A1945),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = NexusColors.Emerald,
    onTertiary = Color(0xFF001F17),
    background = NexusColors.Surface900,
    onBackground = NexusColors.TextPrimary,
    surface = NexusColors.Surface850,
    onSurface = NexusColors.TextPrimary,
    surfaceVariant = NexusColors.Surface800,
    onSurfaceVariant = NexusColors.TextMuted,
    surfaceContainerLowest = NexusColors.Surface900,
    surfaceContainerLow = NexusColors.Surface850,
    surfaceContainer = NexusColors.Surface800,
    surfaceContainerHigh = NexusColors.Surface750,
    surfaceContainerHighest = Color(0xFF262B38),
    outline = Color(0xFF475569),
    outlineVariant = NexusColors.Border,
    error = Color(0xFFFB7185),
    errorContainer = Color(0xFF3A1720),
    onErrorContainer = Color(0xFFFFE4E6),
)

private val NexusLightColorScheme = lightColorScheme(
    primary = NexusColors.BrandStrong,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E9FF),
    onPrimaryContainer = Color(0xFF23206B),
    secondary = Color(0xFF7C3AED),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F9),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFF1F3F9),
    surfaceContainerHigh = Color(0xFFE9ECF4),
    surfaceContainerHighest = Color(0xFFE1E5EF),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD8DEEA),
    error = Color(0xFFE11D48),
)

private val NexusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun LocalAiTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (dark) NexusDarkColorScheme else NexusLightColorScheme,
        shapes = NexusShapes,
        content = content,
    )
}
