package com.orangechuice.lightstick.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFFF6FA8)
private val AccentDark = Color(0xFFB03A6B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0A12),
    secondary = Color(0xFF8CC8FF),
    background = Color(0xFF12101A),
    surface = Color(0xFF1B1926),
    surfaceVariant = Color(0xFF262336),
    onBackground = Color(0xFFEDEAF5),
    onSurface = Color(0xFFEDEAF5),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF1E6FB8),
)

@Composable
fun LightstickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
