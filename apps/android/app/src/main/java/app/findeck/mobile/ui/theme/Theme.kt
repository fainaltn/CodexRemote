package app.findeck.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CodexBlueDark,
    onPrimary = Color(0xFF081423),
    primaryContainer = CodexCurrentCardDark,
    onPrimaryContainer = Color(0xFFE0EDFF),
    secondary = CodexCyan,
    onSecondary = Color(0xFF05211D),
    secondaryContainer = CodexSurfaceSoftDark,
    onSecondaryContainer = Color(0xFFDDF7F2),
    tertiary = Color(0xFF7DB5FF),
    onTertiary = Color(0xFF081626),
    tertiaryContainer = CodexSurfaceSubtleDark,
    onTertiaryContainer = Color(0xFFE4F0FF),
    surface = CodexSurfaceDark,
    onSurface = Color(0xFFEDF4FF),
    surfaceVariant = CodexSurfaceRaisedDark,
    onSurfaceVariant = Color(0xFFB6C4D9),
    background = CodexBackgroundDark,
    onBackground = Color(0xFFEDF4FF),
    outline = CodexOutlineDark,
    outlineVariant = Color(0xFF223149),
    inverseSurface = Color(0xFFE8F1FF),
    inverseOnSurface = Color(0xFF0F1B2D),
    inversePrimary = CodexBlueLight,
    surfaceTint = CodexBlueDark,
    error = CodexOffline,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF521919),
    onErrorContainer = Color(0xFFFFDEDE),
)

private val LightColorScheme = lightColorScheme(
    primary = CodexBlueLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = CodexCurrentCardLight,
    onPrimaryContainer = Color(0xFF0B2548),
    secondary = Color(0xFF167E72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = CodexSurfaceSoftLight,
    onSecondaryContainer = Color(0xFF123A42),
    tertiary = Color(0xFF528CDD),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6F0FF),
    onTertiaryContainer = Color(0xFF14345F),
    surface = CodexSurface,
    onSurface = Color(0xFF152236),
    surfaceVariant = CodexSurfaceRaised,
    onSurfaceVariant = Color(0xFF4C5D76),
    background = CodexBackgroundLight,
    onBackground = Color(0xFF102033),
    outline = CodexOutlineLight,
    outlineVariant = Color(0xFFC7D6E8),
    inverseSurface = Color(0xFF152236),
    inverseOnSurface = Color(0xFFF5F9FF),
    inversePrimary = CodexBlueDark,
    surfaceTint = CodexBlueLight,
    error = CodexOffline,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDCDC),
    onErrorContainer = Color(0xFF7A1717),
)

@Composable
fun FindeckTheme(
    themePreference: ThemePreference = ThemePreference.AUTO,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (themePreference.isDarkNow()) DarkColorScheme else LightColorScheme,
        typography = CodexTypography,
        content = content,
    )
}
