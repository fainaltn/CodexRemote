package dev.codexremote.android.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Server
import dev.codexremote.android.data.model.RuntimeCatalogResponse
import dev.codexremote.android.data.model.RuntimeCreditsSnapshot
import dev.codexremote.android.data.model.RuntimeModelDescriptor
import dev.codexremote.android.data.model.RuntimeRateLimitWindow
import dev.codexremote.android.data.model.RuntimeUsageResponse
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.ui.sessions.ShimmerBlock
import dev.codexremote.android.ui.sessions.TimelineNoticeCard
import dev.codexremote.android.ui.sessions.TimelineNoticeTone
import dev.codexremote.android.ui.theme.PrecisionConsoleSnackbarHost
import dev.codexremote.android.ui.theme.ThemePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SETTINGS_HOST_ID = "local"

data class ServerSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val server: Server? = null,
    val runtimeDefaultModel: String? = null,
    val runtimeDefaultReasoningEffort: String? = null,
    val runtimeDefaultPermissionMode: String? = null,
    val runtimeCatalog: RuntimeCatalogResponse? = null,
    val runtimeUsage: RuntimeUsageResponse? = null,
    val runtimeLoading: Boolean = false,
    val runtimeError: String? = null,
    val error: String? = null,
)

private enum class RuntimeSettingTarget {
    Model,
    ReasoningEffort,
    PermissionMode,
}

private data class RuntimeSelectionOption(
    val value: String?,
    val title: String,
    val detail: String,
)

class ServerSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _uiState = MutableStateFlow(ServerSettingsUiState())
    val uiState = _uiState.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    private suspend fun loadRuntimeData(server: Server) {
        val token = server.token?.takeIf { it.isNotBlank() }
        if (token == null) {
            _uiState.update {
                it.copy(
                    runtimeLoading = false,
                    runtimeError = getApplication<Application>().getString(R.string.server_settings_error_not_logged_in),
                )
            }
            return
        }

        _uiState.update { it.copy(runtimeLoading = true, runtimeError = null) }
        val client = ApiClient(server.baseUrl)
        try {
            val catalog = runCatching {
                client.getRuntimeCatalog(token = token, hostId = SETTINGS_HOST_ID)
            }
            val usage = runCatching {
                client.getRuntimeUsage(token = token, hostId = SETTINGS_HOST_ID)
            }

            val runtimeError = buildList {
                catalog.exceptionOrNull()?.let {
                    add(
                        userFacingMessage(
                            it,
                            getApplication<Application>().getString(R.string.server_settings_usage_unavailable),
                        )
                    )
                }
                usage.exceptionOrNull()?.let {
                    add(
                        userFacingMessage(
                            it,
                            getApplication<Application>().getString(R.string.server_settings_usage_unavailable),
                        )
                    )
                }
            }.distinct().joinToString("\n").ifBlank { null }

            _uiState.update { current ->
                current.copy(
                    runtimeCatalog = catalog.getOrNull(),
                    runtimeUsage = usage.getOrNull(),
                    runtimeLoading = false,
                    runtimeError = runtimeError,
                )
            }
        } finally {
            client.close()
        }
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
                    runtimeDefaultModel = repo.getRuntimeDefaultModel(serverId),
                    runtimeDefaultReasoningEffort = repo.getRuntimeDefaultReasoningEffort(serverId),
                    runtimeDefaultPermissionMode = repo.getRuntimeDefaultPermissionMode(serverId),
                )
                loadRuntimeData(server)
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

    fun updateRuntimeDefaults(
        serverId: String,
        model: String?,
        reasoningEffort: String?,
        permissionMode: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val server = _uiState.value.server ?: run {
                onError(getApplication<Application>().getString(R.string.server_settings_error_server_missing))
                return@launch
            }
            try {
                repo.setRuntimeDefaultModel(serverId, model)
                repo.setRuntimeDefaultReasoningEffort(serverId, reasoningEffort)
                repo.setRuntimeDefaultPermissionMode(serverId, permissionMode)
                _uiState.value = _uiState.value.copy(
                    runtimeDefaultModel = model,
                    runtimeDefaultReasoningEffort = reasoningEffort,
                    runtimeDefaultPermissionMode = permissionMode,
                    server = server,
                )
                onSuccess(getApplication<Application>().getString(R.string.server_settings_runtime_saved))
            } catch (error: Exception) {
                onError(
                    userFacingMessage(
                        error,
                        getApplication<Application>().getString(R.string.server_settings_error_update_failed),
                    ),
                )
            }
        }
    }

    fun resetRuntimeDefaults(
        serverId: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        updateRuntimeDefaults(
            serverId = serverId,
            model = null,
            reasoningEffort = null,
            permissionMode = null,
            onSuccess = onSuccess,
            onError = onError,
        )
    }

    fun refreshRuntimeData(serverId: String) {
        viewModelScope.launch {
            val server = _uiState.value.server ?: return@launch
            if (server.id != serverId) return@launch
            loadRuntimeData(server)
        }
    }

    fun updateTrustedReconnect(
        serverId: String,
        enabled: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val server = _uiState.value.server ?: run {
                onError(getApplication<Application>().getString(R.string.server_settings_error_server_missing))
                return@launch
            }
            try {
                repo.setTrustedAutoReconnectEnabled(serverId, enabled)
                load(serverId)
                onSuccess(
                    if (enabled) {
                        getApplication<Application>().getString(R.string.server_settings_trust_enabled)
                    } else {
                        getApplication<Application>().getString(R.string.server_settings_trust_disabled)
                    }
                )
            } catch (error: Exception) {
                val message = userFacingMessage(
                    error,
                    getApplication<Application>().getString(R.string.server_settings_error_update_failed),
                )
                onError(message)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    saving = false,
                    server = server,
                    error = message,
                )
            }
        }
    }

    fun clearTrustedHost(
        serverId: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val server = _uiState.value.server ?: run {
                onError(getApplication<Application>().getString(R.string.server_settings_error_server_missing))
                return@launch
            }
            try {
                repo.clearTrustedHostMetadata(serverId)
                load(serverId)
                onSuccess(getApplication<Application>().getString(R.string.server_settings_trust_cleared))
            } catch (error: Exception) {
                val message = userFacingMessage(
                    error,
                    getApplication<Application>().getString(R.string.server_settings_error_update_failed),
                )
                onError(message)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    saving = false,
                    server = server,
                    error = message,
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
    themePreference: ThemePreference,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onOpenPairing: () -> Unit,
    viewModel: ServerSettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var runtimeSheetTarget by remember { mutableStateOf<RuntimeSettingTarget?>(null) }
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
    val trustTitle = stringResource(R.string.server_settings_trust_title)
    val trustMessage = stringResource(R.string.server_settings_trust_message)
    val trustBadgeTrusted = stringResource(R.string.server_settings_trust_badge_trusted)
    val trustBadgeUnpaired = stringResource(R.string.server_settings_trust_badge_unpaired)
    val trustAutoReconnect = stringResource(R.string.server_settings_trust_auto_reconnect)
    val trustClear = stringResource(R.string.server_settings_trust_clear)
    val appearanceTitle = stringResource(R.string.server_settings_appearance_title)
    val appearanceMessage = stringResource(R.string.server_settings_appearance_message)
    val notificationsTitle = stringResource(R.string.server_settings_notifications_title)
    val notificationsEnabled = stringResource(R.string.server_settings_notifications_enabled)
    val notificationsDisabled = stringResource(R.string.server_settings_notifications_disabled)
    val notificationsManage = stringResource(R.string.server_settings_notifications_manage)
    val usageTitle = stringResource(R.string.server_settings_usage_title)
    val usageMessage = stringResource(R.string.server_settings_usage_message)
    val usageLoading = stringResource(R.string.server_settings_usage_loading)
    val usageRefresh = stringResource(R.string.server_settings_usage_refresh)
    val usagePlanLabel = stringResource(R.string.server_settings_usage_plan_label)
    val usageCreditsLabel = stringResource(R.string.server_settings_usage_credits_label)
    val usageWindow5hLabel = stringResource(R.string.server_settings_usage_window_5h_label)
    val usageWindow7dLabel = stringResource(R.string.server_settings_usage_window_7d_label)
    val runtimeTitle = stringResource(R.string.server_settings_runtime_title)
    val runtimeMessage = stringResource(R.string.server_settings_runtime_message)
    val runtimeReset = stringResource(R.string.server_settings_runtime_reset)
    val runtimePermissionTitle = stringResource(R.string.server_settings_runtime_permission_title)
    val passwordTitle = stringResource(R.string.server_settings_password_title)
    val passwordMessage = stringResource(R.string.server_settings_password_message)
    val trustLabelTitle = stringResource(R.string.server_settings_trust_label_title)
    val trustMethodTitle = stringResource(R.string.server_settings_trust_method_title)
    val trustPairedAtTitle = stringResource(R.string.server_settings_trust_paired_at_title)
    val trustLastReconnectTitle = stringResource(R.string.server_settings_trust_last_reconnect_title)

    val notificationsHelper = remember { dev.codexremote.android.notifications.RunCompletedNotificationHelper(context) }
    var canPostNotifications by remember { mutableStateOf(notificationsHelper.canPostNotifications()) }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, notificationsHelper) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                canPostNotifications = notificationsHelper.canPostNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                .imePadding()
                .verticalScroll(scrollState),
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
                    val trustedHost = server.trustedHost

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

                    TimelineNoticeCard(
                        title = appearanceTitle,
                        message = appearanceMessage,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = when (themePreference) {
                            ThemePreference.AUTO -> stringResource(R.string.server_settings_theme_auto)
                            ThemePreference.LIGHT -> stringResource(R.string.server_settings_theme_light)
                            ThemePreference.DARK -> stringResource(R.string.server_settings_theme_dark)
                        },
                        content = {
                            Button(onClick = onToggleTheme) {
                                Text(stringResource(R.string.server_settings_theme_cycle))
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    TimelineNoticeCard(
                        title = notificationsTitle,
                        message = if (canPostNotifications) notificationsEnabled else notificationsDisabled,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = if (canPostNotifications) {
                            stringResource(R.string.server_settings_notifications_state_on)
                        } else {
                            stringResource(R.string.server_settings_notifications_state_off)
                        },
                        content = {
                            TextButton(
                                onClick = {
                                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    } else {
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                    }
                                    context.startActivity(intent)
                                },
                            ) {
                                Text(notificationsManage)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    TimelineNoticeCard(
                        title = usageTitle,
                        message = when {
                            uiState.runtimeLoading && uiState.runtimeUsage == null -> usageLoading
                            uiState.runtimeError != null && uiState.runtimeUsage == null -> uiState.runtimeError.orEmpty()
                            else -> usageMessage
                        },
                        tone = if (uiState.runtimeError != null && uiState.runtimeUsage == null) {
                            TimelineNoticeTone.Warning
                        } else {
                            TimelineNoticeTone.Neutral
                        },
                        stateLabel = when {
                            uiState.runtimeLoading && uiState.runtimeUsage == null -> loadingStateLabel
                            uiState.runtimeError != null && uiState.runtimeUsage == null -> errorStateLabel
                            else -> runtimePlanLabel(uiState.runtimeUsage?.rateLimits?.planType)
                        },
                        content = {
                            if (uiState.runtimeLoading && uiState.runtimeUsage == null) {
                                ShimmerBlock(lines = 2)
                            } else {
                                val usage = uiState.runtimeUsage?.rateLimits
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingsMetaRow(
                                        usagePlanLabel,
                                        runtimePlanLabel(usage?.planType),
                                    )
                                    SettingsMetaRow(
                                        usageCreditsLabel,
                                        runtimeCreditsLabel(usage?.credits),
                                    )
                                    SettingsMetaRow(
                                        usageWindow5hLabel,
                                        runtimeWindowSummary(usage?.primary),
                                    )
                                    SettingsMetaRow(
                                        usageWindow7dLabel,
                                        runtimeWindowSummary(usage?.secondary),
                                    )
                                    if (uiState.runtimeError != null && uiState.runtimeUsage != null) {
                                        Text(
                                            text = uiState.runtimeError.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.refreshRuntimeData(serverId) },
                                    ) {
                                        Text(usageRefresh)
                                    }
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    TimelineNoticeCard(
                        title = runtimeTitle,
                        message = runtimeMessage,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = stringResource(R.string.server_settings_runtime_state_label),
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(
                                    onClick = { runtimeSheetTarget = RuntimeSettingTarget.Model },
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.session_control_model_format,
                                            runtimeModelDisplayLabel(
                                                uiState.runtimeDefaultModel,
                                                uiState.runtimeCatalog,
                                            ),
                                        ),
                                    )
                                }
                                TextButton(
                                    onClick = { runtimeSheetTarget = RuntimeSettingTarget.ReasoningEffort },
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.session_control_reasoning_format,
                                            runtimeReasoningDisplayLabel(uiState.runtimeDefaultReasoningEffort),
                                        ),
                                    )
                                }
                                TextButton(
                                    onClick = { runtimeSheetTarget = RuntimeSettingTarget.PermissionMode },
                                ) {
                                    Text(
                                        "${runtimePermissionTitle} · ${runtimePermissionDisplayLabel(uiState.runtimeDefaultPermissionMode)}",
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.resetRuntimeDefaults(
                                            serverId = serverId,
                                            onSuccess = { message ->
                                                scope.launch { snackbarHostState.showSnackbar(message) }
                                            },
                                            onError = { message ->
                                                scope.launch { snackbarHostState.showSnackbar(message) }
                                            },
                                        )
                                    },
                                ) {
                                    Text(runtimeReset)
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    TimelineNoticeCard(
                        title = trustTitle,
                        message = trustMessage,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = if (trustedHost == null) trustBadgeUnpaired else trustBadgeTrusted,
                        content = {
                            if (trustedHost == null) {
                                Button(onClick = onOpenPairing) {
                                    Text(stringResource(R.string.server_settings_trust_pair_button))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = trustAutoReconnect,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Switch(
                                            checked = trustedHost.autoReconnectEnabled,
                                            onCheckedChange = { enabled ->
                                                viewModel.updateTrustedReconnect(
                                                    serverId = serverId,
                                                    enabled = enabled,
                                                    onSuccess = { message ->
                                                        scope.launch { snackbarHostState.showSnackbar(message) }
                                                    },
                                                    onError = { message ->
                                                        scope.launch { snackbarHostState.showSnackbar(message) }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                    trustedHost.trustLabel?.takeIf { it.isNotBlank() }?.let { label ->
                                        SettingsMetaRow(trustLabelTitle, label)
                                    }
                                    trustedHost.pairingMethod?.takeIf { it.isNotBlank() }?.let { method ->
                                        SettingsMetaRow(trustMethodTitle, method)
                                    }
                                    trustedHost.pairedAt?.let { pairedAt ->
                                        SettingsMetaRow(trustPairedAtTitle, formatSettingsTimestamp(pairedAt))
                                    }
                                    trustedHost.lastAutoReconnectAt?.let { lastReconnect ->
                                        SettingsMetaRow(trustLastReconnectTitle, formatSettingsTimestamp(lastReconnect))
                                    }
                                    TextButton(
                                        onClick = {
                                            viewModel.clearTrustedHost(
                                                serverId = serverId,
                                                onSuccess = { message ->
                                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                                },
                                                onError = { message ->
                                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                                },
                                            )
                                        },
                                    ) {
                                        Text(trustClear)
                                    }
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    TimelineNoticeCard(
                        title = passwordTitle,
                        message = passwordMessage,
                        footer = description,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = stringResource(R.string.server_settings_password_state_label),
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            }
                        }
                    )

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

    if (runtimeSheetTarget != null) {
        val target = runtimeSheetTarget ?: RuntimeSettingTarget.Model
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { runtimeSheetTarget = null },
            sheetState = sheetState,
        ) {
            RuntimeSelectionSheetContent(
                target = target,
                catalog = uiState.runtimeCatalog,
                currentModel = uiState.runtimeDefaultModel,
                currentReasoning = uiState.runtimeDefaultReasoningEffort,
                currentPermissionMode = uiState.runtimeDefaultPermissionMode,
                onSelect = { value ->
                    val nextModel = when (target) {
                        RuntimeSettingTarget.Model -> value
                        else -> uiState.runtimeDefaultModel
                    }
                    val nextReasoning = when (target) {
                        RuntimeSettingTarget.ReasoningEffort -> value
                        else -> uiState.runtimeDefaultReasoningEffort
                    }
                    val nextPermissionMode = when (target) {
                        RuntimeSettingTarget.PermissionMode -> value
                        else -> uiState.runtimeDefaultPermissionMode
                    }
                    viewModel.updateRuntimeDefaults(
                        serverId = serverId,
                        model = nextModel,
                        reasoningEffort = nextReasoning,
                        permissionMode = nextPermissionMode,
                        onSuccess = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                        onError = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )
                    runtimeSheetTarget = null
                },
            )
        }
    }
}

@Composable
private fun SettingsMetaRow(
    title: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RuntimeSelectionSheetContent(
    target: RuntimeSettingTarget,
    catalog: RuntimeCatalogResponse?,
    currentModel: String?,
    currentReasoning: String?,
    currentPermissionMode: String?,
    onSelect: (String?) -> Unit,
) {
    val sheetScrollState = rememberScrollState()
    val title = when (target) {
        RuntimeSettingTarget.Model -> stringResource(R.string.server_settings_runtime_model_picker_title)
        RuntimeSettingTarget.ReasoningEffort -> stringResource(R.string.server_settings_runtime_reasoning_picker_title)
        RuntimeSettingTarget.PermissionMode -> stringResource(R.string.server_settings_runtime_permission_picker_title)
    }
    val message = when (target) {
        RuntimeSettingTarget.Model -> stringResource(R.string.server_settings_runtime_message)
        RuntimeSettingTarget.ReasoningEffort -> stringResource(R.string.server_settings_runtime_message)
        RuntimeSettingTarget.PermissionMode -> stringResource(R.string.server_settings_runtime_permission_picker_message)
    }
    val options = runtimeSelectionOptions(target, catalog)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(sheetScrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val selected = when (target) {
                    RuntimeSettingTarget.Model -> currentModel == option.value
                    RuntimeSettingTarget.ReasoningEffort -> currentReasoning == option.value
                    RuntimeSettingTarget.PermissionMode -> currentPermissionMode == option.value
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.value) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun runtimeSelectionOptions(
    target: RuntimeSettingTarget,
    catalog: RuntimeCatalogResponse?,
) = when (target) {
    RuntimeSettingTarget.Model -> buildList {
        add(
            RuntimeSelectionOption(
                value = null,
                title = stringResource(R.string.session_controls_runtime_auto_label),
                detail = stringResource(R.string.session_controls_runtime_model_auto_detail),
            )
        )
        val models = catalog?.models
            ?.filterNot { it.hidden }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                RuntimeModelDescriptor(
                    id = "gpt-5.4",
                    model = "gpt-5.4",
                    displayName = stringResource(R.string.session_controls_runtime_model_gpt_5_4_label),
                    description = stringResource(R.string.session_controls_runtime_model_gpt_5_4_detail),
                    hidden = false,
                    isDefault = true,
                    defaultReasoningEffort = "medium",
                    supportedReasoningEfforts = emptyList(),
                ),
                RuntimeModelDescriptor(
                    id = "o4-mini",
                    model = "o4-mini",
                    displayName = stringResource(R.string.session_controls_runtime_model_o4_mini_label),
                    description = stringResource(R.string.session_controls_runtime_model_o4_mini_detail),
                    hidden = false,
                    isDefault = false,
                    defaultReasoningEffort = "medium",
                    supportedReasoningEfforts = emptyList(),
                ),
            )
        models.sortedWith(
            compareByDescending<RuntimeModelDescriptor> { it.isDefault }
                .thenBy { it.displayName.lowercase() }
        ).forEach { model ->
            add(
                RuntimeSelectionOption(
                    value = model.model,
                    title = model.displayName,
                    detail = model.description,
                )
            )
        }
    }

    RuntimeSettingTarget.ReasoningEffort -> buildList {
        add(
            RuntimeSelectionOption(
                value = null,
                title = stringResource(R.string.server_settings_runtime_reasoning_auto_label),
                detail = stringResource(R.string.server_settings_runtime_reasoning_auto_detail),
            )
        )
        val supportedEfforts = linkedMapOf<String, String>()
        catalog?.models.orEmpty().forEach { model ->
            model.supportedReasoningEfforts.forEach { effort ->
                supportedEfforts.putIfAbsent(effort.reasoningEffort, effort.description)
            }
        }
        val ordering = listOf("low", "medium", "high", "xhigh")
        val orderedEfforts = if (supportedEfforts.isNotEmpty()) {
            ordering.filter { supportedEfforts.containsKey(it) } + supportedEfforts.keys.filterNot { it in ordering }.sorted()
        } else {
            ordering
        }
        orderedEfforts.forEach { effort ->
            add(
                RuntimeSelectionOption(
                    value = effort,
                    title = runtimeReasoningDisplayLabel(effort),
                    detail = supportedEfforts[effort] ?: runtimeReasoningDetailLabel(effort),
                )
            )
        }
    }

    RuntimeSettingTarget.PermissionMode -> listOf(
        RuntimeSelectionOption(
            value = "on-request",
            title = stringResource(R.string.server_settings_runtime_permission_on_request_label),
            detail = stringResource(R.string.server_settings_runtime_permission_on_request_detail),
        ),
        RuntimeSelectionOption(
            value = "full-access",
            title = stringResource(R.string.server_settings_runtime_permission_full_label),
            detail = stringResource(R.string.server_settings_runtime_permission_full_detail),
        ),
    )
}

@Composable
private fun runtimeModelDisplayLabel(
    currentModel: String?,
    catalog: RuntimeCatalogResponse?,
): String {
    if (currentModel.isNullOrBlank()) {
        return stringResource(R.string.session_controls_runtime_auto_label)
    }
    return catalog?.models
        ?.firstOrNull { it.model == currentModel || it.id == currentModel }
        ?.displayName
        ?: currentModel.orEmpty()
}

@Composable
private fun runtimeReasoningDisplayLabel(value: String?): String = when (value) {
    null, "" -> stringResource(R.string.server_settings_runtime_reasoning_auto_label)
    "low" -> stringResource(R.string.server_settings_runtime_reasoning_low_label)
    "medium" -> stringResource(R.string.server_settings_runtime_reasoning_medium_label)
    "high" -> stringResource(R.string.server_settings_runtime_reasoning_high_label)
    "xhigh" -> stringResource(R.string.server_settings_runtime_reasoning_xhigh_label)
    else -> value.orEmpty()
}

@Composable
private fun runtimeReasoningDetailLabel(value: String?): String = when (value) {
    null, "" -> stringResource(R.string.server_settings_runtime_reasoning_auto_detail)
    "low" -> stringResource(R.string.server_settings_runtime_reasoning_low_detail)
    "medium" -> stringResource(R.string.server_settings_runtime_reasoning_medium_detail)
    "high" -> stringResource(R.string.server_settings_runtime_reasoning_high_detail)
    "xhigh" -> stringResource(R.string.server_settings_runtime_reasoning_xhigh_detail)
    else -> value.orEmpty()
}

@Composable
private fun runtimePermissionDisplayLabel(value: String?): String = when (value) {
    null, "", "default", "on-request", "onRequest" ->
        stringResource(R.string.server_settings_runtime_permission_on_request_label)
    "full", "full-access", "fullAccess" ->
        stringResource(R.string.server_settings_runtime_permission_full_label)
    else -> value.orEmpty()
}

@Composable
private fun runtimePlanLabel(planType: String?): String {
    val value = planType?.trim().orEmpty()
    return if (value.isBlank()) {
        "—"
    } else {
        value.replace('_', ' ')
            .split(' ')
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
    }
}

@Composable
private fun runtimeCreditsLabel(snapshot: RuntimeCreditsSnapshot?): String = when {
    snapshot == null -> "—"
    snapshot.unlimited -> stringResource(R.string.server_settings_usage_credits_unlimited)
    snapshot.balance?.isNotBlank() == true -> snapshot.balance.orEmpty()
    snapshot.hasCredits -> stringResource(R.string.server_settings_usage_credits_available)
    else -> "0"
}

@Composable
private fun runtimeWindowSummary(window: RuntimeRateLimitWindow?): String {
    if (window == null) {
        return "—"
    }
    val percent = stringResource(
        R.string.server_settings_usage_window_percent_format,
        window.usedPercent,
    )
    val reset = window.resetsAt?.let { raw ->
        formatRuntimeResetTime(raw)?.let {
            stringResource(R.string.server_settings_usage_reset_format, it)
        }
    }
    return listOfNotNull(percent, reset).joinToString(" · ")
}

private fun formatRuntimeResetTime(raw: Long): String? = runCatching {
    val instant = if (raw > 1_000_000_000_000L) {
        Instant.ofEpochMilli(raw)
    } else {
        Instant.ofEpochSecond(raw)
    }
    instant.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrNull()

private fun formatSettingsTimestamp(raw: String): String = runCatching {
    Instant.parse(raw)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrElse { raw }
