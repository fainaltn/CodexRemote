package app.findeck.mobile

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
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.navigation.AppNavHost
import app.findeck.mobile.notifications.RunCompletedNotificationContract
import app.findeck.mobile.notifications.RunCompletedNotificationPayload
import app.findeck.mobile.ui.theme.FindeckTheme
import app.findeck.mobile.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivityViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)
    val themePreference = repo.themePreference
    private val _startupState = MutableStateFlow<StartupUiState>(StartupUiState.Loading)
    val startupState = _startupState.asStateFlow()
    private var startupJob: Job? = null
    private val _pendingNotificationPayload = MutableStateFlow<RunCompletedNotificationPayload?>(null)
    val pendingNotificationPayload = _pendingNotificationPayload.asStateFlow()

    init {
        prepareStartup(force = true)
    }

    fun cycleThemePreference(current: ThemePreference) {
        viewModelScope.launch {
            repo.setThemePreference(current.next())
        }
    }

    fun prepareStartup(force: Boolean = false) {
        if (startupJob?.isActive == true) return
        if (!force) {
            when (_startupState.value) {
                is StartupUiState.Loading,
                is StartupUiState.NoTrustedServer -> Unit
                else -> return
            }
        }

        startupJob = viewModelScope.launch {
            val server = repo.getTrustedReconnectServer()
            if (server == null) {
                _startupState.value = StartupUiState.NoTrustedServer
                return@launch
            }

            _startupState.value = StartupUiState.Reconnecting(
                serverId = server.id,
                serverLabel = server.label,
            )

            val client = ApiClient(server.baseUrl)
            try {
                reconnectTrustedServer(client, server)
                repo.setActiveServer(server.id)
                repo.touchTrustedReconnect(server.id)
                _startupState.value = StartupUiState.Reconnected(
                    serverId = server.id,
                    serverLabel = server.label,
                )
            } catch (error: Exception) {
                _startupState.value = StartupUiState.ReconnectFailed(
                    serverId = server.id,
                    serverLabel = server.label,
                    message = ApiClient.describeNetworkFailure(error),
                    requiresPairing = server.hasTrustedPairing,
                )
            } finally {
                client.close()
            }
        }
    }

    private suspend fun reconnectTrustedServer(client: ApiClient, server: Server) {
        val trustedClientId = server.trustedHost?.trustedClientId?.takeIf { it.isNotBlank() }
        val trustedClientSecret =
            server.trustedHost?.trustedClientSecret?.takeIf { it.isNotBlank() }
        val storedPassword = server.appPassword?.takeIf { it.isNotBlank() }

        if (trustedClientId != null && trustedClientSecret != null) {
            val response = client.reconnectTrustedClient(
                clientId = trustedClientId,
                clientSecret = trustedClientSecret,
                deviceLabel = "android",
            )
            repo.updateToken(server.id, response.token)
            return
        }

        if (storedPassword != null) {
            val response = client.login(storedPassword, deviceLabel = "android")
            repo.updateToken(server.id, response.token)
            return
        }

        throw IllegalStateException("当前主机缺少可信重连凭据，请重新配对或重新登录。")
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
            val startupState by viewModel.startupState.collectAsState()
            val pendingNotificationPayload by viewModel.pendingNotificationPayload.collectAsState()
            FindeckTheme(themePreference = themePreference) {
                AppNavHost(
                    themePreference = themePreference,
                    onToggleTheme = { viewModel.cycleThemePreference(themePreference) },
                    startupState = startupState,
                    pendingNotificationPayload = pendingNotificationPayload,
                    onPendingNotificationHandled = { viewModel.consumeNotificationPayload() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.prepareStartup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }
}

sealed interface StartupUiState {
    data object Loading : StartupUiState
    data object NoTrustedServer : StartupUiState
    data class Reconnecting(
        val serverId: String,
        val serverLabel: String,
    ) : StartupUiState

    data class Reconnected(
        val serverId: String,
        val serverLabel: String,
    ) : StartupUiState

    data class ReconnectFailed(
        val serverId: String,
        val serverLabel: String,
        val message: String,
        val requiresPairing: Boolean = false,
    ) : StartupUiState
}
