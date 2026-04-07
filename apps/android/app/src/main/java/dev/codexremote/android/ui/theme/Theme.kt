package dev.codexremote.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CodexBlueDark,
    secondary = CodexBlueDark,
    surface = CodexSurfaceDark,
    surfaceVariant = CodexSurfaceRaisedDark,
    background = CodexBackgroundDark,
    primaryContainer = CodexCurrentCardDark,
)

private val LightColorScheme = lightColorScheme(
    primary = CodexBlueLight,
    secondary = CodexBlueLight,
    surface = CodexSurface,
    surfaceVariant = CodexSurfaceRaised,
    background = CodexBackgroundLight,
    primaryContainer = CodexCurrentCardLight,
)

@Composable
fun CodexRemoteTheme(
    themePreference: ThemePreference = ThemePreference.AUTO,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (themePreference.isDarkNow()) DarkColorScheme else LightColorScheme,
        typography = CodexTypography,
        content = content,
    )
}
