package app.findeck.mobile.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.findeck.mobile.R

@Composable
fun ThemeToggleAction(
    themePreference: ThemePreference,
    onToggle: () -> Unit,
) {
    val icon = when (themePreference) {
        ThemePreference.AUTO -> Icons.Filled.BrightnessAuto
        ThemePreference.DARK -> Icons.Filled.DarkMode
        ThemePreference.LIGHT -> Icons.Filled.LightMode
    }
    val label = when (themePreference) {
        ThemePreference.AUTO -> stringResource(R.string.theme_toggle_auto)
        ThemePreference.DARK -> stringResource(R.string.theme_toggle_dark)
        ThemePreference.LIGHT -> stringResource(R.string.theme_toggle_light)
    }
    IconButton(onClick = onToggle) {
        Icon(icon, contentDescription = label)
    }
}
