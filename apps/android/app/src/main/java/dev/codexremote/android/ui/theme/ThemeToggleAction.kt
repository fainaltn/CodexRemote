package dev.codexremote.android.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

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
        ThemePreference.AUTO -> "主题：自动"
        ThemePreference.DARK -> "主题：夜间"
        ThemePreference.LIGHT -> "主题：日间"
    }
    IconButton(onClick = onToggle) {
        Icon(icon, contentDescription = label)
    }
}
