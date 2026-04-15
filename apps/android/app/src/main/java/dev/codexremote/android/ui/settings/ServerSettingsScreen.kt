package dev.codexremote.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Server
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.ui.sessions.ShimmerBlock
import dev.codexremote.android.ui.sessions.TimelineNoticeCard
import dev.codexremote.android.ui.sessions.TimelineNoticeTone
import dev.codexremote.android.ui.theme.PrecisionConsoleSnackbarHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val server: Server? = null,
    val error: String? = null,
)

class ServerSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _uiState = MutableStateFlow(ServerSettingsUiState())
    val uiState = _uiState.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    fun load(serverId: String) {
        viewModelScope.launch {
            _uiState.value = ServerSettingsUiState(loading = true)
            try {
                val servers = repo.servers.first()
                val server = servers.find { it.id == serverId }
                    ?: throw IllegalStateException(
                        getApplication<Application>().getString(R.string.server_settings_error_server_missing)
                    )
                _uiState.value = ServerSettingsUiState(
                    loading = false,
                    server = server,
                )
            } catch (error: Exception) {
                _uiState.value = ServerSettingsUiState(
                    loading = false,
                    error = userFacingMessage(
                        error,
                        getApplication<Application>().getString(R.string.server_settings_error_load_failed),
                    ),
                )
            }
        }
    }

    fun changePassword(
        serverId: String,
        currentPassword: String,
        newPassword: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val snapshot = _uiState.value
            val server = snapshot.server ?: run {
                onError(getApplication<Application>().getString(R.string.server_settings_error_server_missing))
                return@launch
            }
            val token = server.token ?: run {
                onError(getApplication<Application>().getString(R.string.server_settings_error_not_logged_in))
                return@launch
            }

            _uiState.value = snapshot.copy(saving = true, error = null)
            try {
                val client = ApiClient(server.baseUrl)
                try {
                    val health = client.validateConnection()
                    if (!health.ok) {
                        throw IllegalStateException(
                            buildString {
                                append(health.summary)
                                health.detail?.let {
                                    append('\n')
                                    append(it)
                                }
                            }
                        )
                    }
                    client.changePassword(
                        token = token,
                        currentPassword = currentPassword,
                        newPassword = newPassword,
                    )
                } finally {
                    client.close()
                }

                var refreshedToken: String? = null
                var reloginError: Throwable? = null
                repeat(8) { attempt ->
                    delay(if (attempt == 0) 1_500L else 1_000L)
                    try {
                        val loginClient = ApiClient(server.baseUrl)
                        try {
                            val response = loginClient.login(newPassword, deviceLabel = "android")
                            refreshedToken = response.token
                        } finally {
                            loginClient.close()
                        }
                        return@repeat
                    } catch (error: Exception) {
                        reloginError = error
                    }
                }

                if (refreshedToken != null) {
                    repo.updateCredentials(
                        serverId = serverId,
                        token = refreshedToken,
                        appPassword = newPassword,
                    )
                    load(serverId)
                    onSuccess(
                        getApplication<Application>().getString(
                            R.string.server_settings_password_updated_reconnected
                        )
                    )
                } else {
                    repo.updateCredentials(
                        serverId = serverId,
                        token = server.token,
                        appPassword = newPassword,
                    )
                    load(serverId)
                    onSuccess(
                        userFacingMessage(
                            reloginError ?: IllegalStateException(
                                getApplication<Application>().getString(
                                    R.string.server_settings_password_updated_restarting,
                                )
                            ),
                            getApplication<Application>().getString(
                                R.string.server_settings_password_updated_restarting,
                            ),
                        )
                    )
                }
            } catch (error: Exception) {
                val message = userFacingMessage(
                    error,
                    getApplication<Application>().getString(R.string.server_settings_error_update_failed),
                )
                _uiState.value = snapshot.copy(
                    loading = false,
                    saving = false,
                    error = message,
                )
                onError(message)
                return@launch
            }

            _uiState.update { it.copy(saving = false, error = null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    serverId: String,
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverId) {
        viewModel.load(serverId)
    }

    LaunchedEffect(uiState.server?.appPassword) {
        if (currentPassword.isBlank() && !uiState.server?.appPassword.isNullOrBlank()) {
            currentPassword = uiState.server?.appPassword.orEmpty()
        }
    }

    val screenTitle = stringResource(R.string.server_settings_title)
    val backDescription = stringResource(R.string.content_desc_back)
    val loadingTitle = stringResource(R.string.server_settings_loading_title)
    val loadingMessage = stringResource(R.string.server_settings_loading_message)
    val loadingFooter = stringResource(R.string.server_settings_loading_footer)
    val loadingStateLabel = stringResource(R.string.server_settings_loading_state_label)
    val errorTitle = stringResource(R.string.server_settings_error_title)
    val errorFooter = stringResource(R.string.server_settings_error_footer)
    val errorStateLabel = stringResource(R.string.server_settings_error_state_label)
    val retryButtonLabel = stringResource(R.string.server_settings_retry_button)
    val description = stringResource(R.string.server_settings_description)
    val currentPasswordLabel = stringResource(R.string.server_settings_current_password_label)
    val newPasswordLabel = stringResource(R.string.server_settings_new_password_label)
    val confirmPasswordLabel = stringResource(R.string.server_settings_confirm_password_label)
    val validationCurrentPasswordRequired = stringResource(R.string.server_settings_validation_current_password_required)
    val validationNewPasswordRequired = stringResource(R.string.server_settings_validation_new_password_required)
    val validationConfirmPasswordRequired = stringResource(R.string.server_settings_validation_confirm_password_required)
    val validationPasswordMismatch = stringResource(R.string.server_settings_validation_password_mismatch)
    val validationSamePassword = stringResource(R.string.server_settings_validation_same_password)
    val saveButtonLabel = stringResource(R.string.server_settings_save_button)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
                    }
                }
            )
        },
        snackbarHost = { PrecisionConsoleSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.loading -> {
                    TimelineNoticeCard(
                        title = loadingTitle,
                        message = loadingMessage,
                        footer = loadingFooter,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = loadingStateLabel,
                        content = {
                            ShimmerBlock(lines = 2)
                        },
                    )
                }

                uiState.server == null -> {
                    TimelineNoticeCard(
                        title = errorTitle,
                        message = uiState.error ?: stringResource(R.string.server_settings_current_server_unavailable),
                        footer = errorFooter,
                        tone = TimelineNoticeTone.Error,
                        stateLabel = errorStateLabel,
                        content = {
                            Button(
                                onClick = { viewModel.load(serverId) },
                            ) {
                                Text(retryButtonLabel)
                            }
                        },
                    )
                }

                else -> {
                    val server = uiState.server!!

                    Text(
                        text = server.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = server.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            localError = null
                        },
                        label = { Text(currentPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            localError = null
                        },
                        label = { Text(newPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            localError = null
                        },
                        label = { Text(confirmPasswordLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val validation = when {
                                currentPassword.isBlank() -> validationCurrentPasswordRequired
                                newPassword.isBlank() -> validationNewPasswordRequired
                                confirmPassword.isBlank() -> validationConfirmPasswordRequired
                                newPassword != confirmPassword -> validationPasswordMismatch
                                currentPassword == newPassword -> validationSamePassword
                                else -> null
                            }

                            if (validation != null) {
                                localError = validation
                                return@Button
                            }

                            viewModel.changePassword(
                                serverId = serverId,
                                currentPassword = currentPassword,
                                newPassword = newPassword,
                                onSuccess = { message ->
                                    currentPassword = ""
                                    newPassword = ""
                                    confirmPassword = ""
                                    localError = null
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                                onError = { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                            )
                        },
                        enabled = !uiState.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(saveButtonLabel)
                        }
                    }

                    val errorMessage = localError ?: uiState.error
                    errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
