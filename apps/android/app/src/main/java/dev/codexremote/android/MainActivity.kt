package dev.codexremote.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.navigation.AppNavHost
import dev.codexremote.android.notifications.RunCompletedNotificationContract
import dev.codexremote.android.notifications.RunCompletedNotificationPayload
import dev.codexremote.android.ui.theme.CodexRemoteTheme
import dev.codexremote.android.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivityViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)
    val themePreference = repo.themePreference
    private val _pendingNotificationPayload = MutableStateFlow<RunCompletedNotificationPayload?>(null)
    val pendingNotificationPayload = _pendingNotificationPayload.asStateFlow()

    fun cycleThemePreference(current: ThemePreference) {
        viewModelScope.launch {
            repo.setThemePreference(current.next())
        }
    }

    fun handleIntent(intent: Intent?) {
        val payload = RunCompletedNotificationContract.parse(intent) ?: return
        _pendingNotificationPayload.value = payload
    }

    fun consumeNotificationPayload() {
        _pendingNotificationPayload.value = null
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel by lazy {
        ViewModelProvider(this)[MainActivityViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.handleIntent(intent)
        setContent {
            val themePreference by viewModel.themePreference.collectAsState(initial = ThemePreference.AUTO)
            val pendingNotificationPayload by viewModel.pendingNotificationPayload.collectAsState()
            CodexRemoteTheme(themePreference = themePreference) {
                AppNavHost(
                    themePreference = themePreference,
                    onToggleTheme = { viewModel.cycleThemePreference(themePreference) },
                    pendingNotificationPayload = pendingNotificationPayload,
                    onPendingNotificationHandled = { viewModel.consumeNotificationPayload() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }
}
