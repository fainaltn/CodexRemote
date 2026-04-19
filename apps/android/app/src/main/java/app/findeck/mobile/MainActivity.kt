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
import app.findeck.mobile.data.model.ColdLaunchRestoreCandidate
import app.findeck.mobile.data.model.ColdLaunchRestoreMethod
import app.findeck.mobile.data.model.ColdLaunchRestoreDecision
import app.findeck.mobile.data.model.ColdLaunchRestoreUnavailableReason
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.data.model.resolveColdLaunchRestoreDecision
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.navigation.AppNavHost
import app.findeck.mobile.notifications.RunCompletedNotificationContract
import app.findeck.mobile.notifications.RunCompletedNotificationPayload
import app.findeck.mobile.ui.theme.FindeckTheme
import app.findeck.mobile.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
            val savedServers = repo.servers.firstOrNull().orEmpty()
            val activeServerId = repo.activeServerId.firstOrNull()
            when (
                val decision = resolveColdLaunchRestoreDecision(
                    servers = savedServers,
                    activeServerId = activeServerId,
                )
            ) {
                is ColdLaunchRestoreDecision.NoRestore -> {
                    _startupState.value = StartupUiState.NoTrustedServer(
                        kind = when (decision.reason) {
                            ColdLaunchRestoreUnavailableReason.NONE_SAVED ->
                                StartupNoTrustedServerKind.NONE_SAVED

                            ColdLaunchRestoreUnavailableReason.NO_ELIGIBLE_SAVED_HOST ->
                                StartupNoTrustedServerKind.NO_ELIGIBLE_RESTORE_HOST
                        },
                    )
                    return@launch
                }

                is ColdLaunchRestoreDecision.Restore -> {
                    val restoreCandidate = decision.candidate
                    val server = restoreCandidate.server

                    _startupState.value = StartupUiState.Reconnecting(
                        serverId = server.id,
                        serverLabel = server.label,
                        restoreMethod = restoreCandidate.method,
                    )

                    val client = ApiClient(server.baseUrl)
                    try {
                        val restoreResult = performColdLaunchRestore(
                            candidate = restoreCandidate,
                            client = object : ColdLaunchRestoreClient {
                                override suspend fun reconnectTrusted(
                                    clientId: String,
                                    clientSecret: String,
                                    deviceLabel: String,
                                ): String {
                                    val response = client.reconnectTrustedClient(
                                        clientId = clientId,
                                        clientSecret = clientSecret,
                                        deviceLabel = deviceLabel,
                                    )
                                    return response.token
                                }

                                override suspend fun login(
                                    password: String,
                                    deviceLabel: String,
                                ): String {
                                    val response = client.login(
                                        password = password,
                                        deviceLabel = deviceLabel,
                                    )
                                    return response.token
                                }
                            },
                        )
                        if (restoreResult.method != restoreCandidate.method) {
                            _startupState.value = StartupUiState.Reconnecting(
                                serverId = server.id,
                                serverLabel = server.label,
                                restoreMethod = restoreResult.method,
                            )
                        }
                        repo.updateToken(server.id, restoreResult.token)
                        repo.setActiveServer(server.id)
                        if (restoreResult.method == ColdLaunchRestoreMethod.TRUSTED_RECONNECT) {
                            repo.touchTrustedReconnect(server.id)
                        }
                        _startupState.value = StartupUiState.Reconnected(
                            serverId = server.id,
                            serverLabel = server.label,
                            restoreMethod = restoreResult.method,
                        )
                    } catch (error: Exception) {
                        val failure = classifyStartupFailure(error)
                        _startupState.value = StartupUiState.ReconnectFailed(
                            serverId = server.id,
                            serverLabel = server.label,
                            message = failure.message,
                            kind = failure.kind,
                            nextAction = failure.nextAction,
                        )
                    } finally {
                        client.close()
                    }
                }
            }
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
    data class NoTrustedServer(
        val kind: StartupNoTrustedServerKind,
    ) : StartupUiState
    data class Reconnecting(
        val serverId: String,
        val serverLabel: String,
        val restoreMethod: ColdLaunchRestoreMethod,
    ) : StartupUiState

    data class Reconnected(
        val serverId: String,
        val serverLabel: String,
        val restoreMethod: ColdLaunchRestoreMethod,
    ) : StartupUiState

    data class ReconnectFailed(
        val serverId: String,
        val serverLabel: String,
        val message: String,
        val kind: StartupFailureKind,
        val nextAction: StartupRecoveryAction,
    ) : StartupUiState
}
