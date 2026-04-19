package app.findeck.mobile.ui.theme

import java.time.LocalTime

enum class ThemePreference {
    AUTO,
    LIGHT,
    DARK;

    fun next(): ThemePreference = when (this) {
        AUTO -> DARK
        DARK -> LIGHT
        LIGHT -> AUTO
    }

    fun isDarkNow(): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        AUTO -> {
            val hour = LocalTime.now().hour
            hour < 7 || hour >= 19
        }
    }
}
