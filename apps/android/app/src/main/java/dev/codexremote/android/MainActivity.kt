package dev.codexremote.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.navigation.AppNavHost
import dev.codexremote.android.ui.theme.CodexRemoteTheme
import dev.codexremote.android.ui.theme.ThemePreference
import kotlinx.coroutines.launch

class MainActivityViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)
    val themePreference = repo.themePreference

    fun cycleThemePreference(current: ThemePreference) {
        viewModelScope.launch {
            repo.setThemePreference(current.next())
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainActivityViewModel = viewModel()
            val themePreference by viewModel.themePreference.collectAsState(initial = ThemePreference.AUTO)
            CodexRemoteTheme(themePreference = themePreference) {
                AppNavHost(
                    themePreference = themePreference,
                    onToggleTheme = { viewModel.cycleThemePreference(themePreference) },
                )
            }
        }
    }
}
