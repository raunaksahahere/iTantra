package com.itantra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentSaffron,
    onPrimary = DarkBg,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = TextPrimary,
    secondary = AccentEmerald,
    onSecondary = DarkBg,
    secondaryContainer = DarkSurface,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentChakra,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = AccentAlert,
    onError = TextPrimary
)

@Composable
fun ITantraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
