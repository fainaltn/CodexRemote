package app.findeck.mobile.ui.servers

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.QrCode2
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.findeck.mobile.R
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.data.model.TrustedHostMetadata
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.ui.sessions.TimelineNoticeCard
import app.findeck.mobile.ui.sessions.TimelineNoticeTone
import app.findeck.mobile.ui.theme.FindeckSnackbarHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

data class PairingUiState(
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val server: Server? = null,
    val error: String? = null,
)

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState = _uiState.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    fun load(serverId: String?) {
        viewModelScope.launch {
            _uiState.value = PairingUiState(loading = true)
            try {
                val server = when {
                    !serverId.isNullOrBlank() -> repo.getServer(serverId)
                    else -> repo.getActiveServer()
                }
                _uiState.value = PairingUiState(
                    loading = false,
                    server = server,
                )
            } catch (error: Exception) {
                _uiState.value = PairingUiState(
                    loading = false,
                    error = userFacingMessage(
                        error,
                        getApplication<Application>().getString(R.string.server_pairing_error_load_failed),
                    ),
                )
            }
        }
    }

    fun submit(
        serverId: String?,
        label: String,
        baseUrl: String,
        pairingInput: String,
        trustDevice: Boolean,
        onSuccess: (serverId: String, message: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val snapshot = _uiState.value
            val existingServer = when {
                !serverId.isNullOrBlank() -> repo.getServer(serverId)
                else -> snapshot.server
            }

            val parsed = parsePairingInput(pairingInput) ?: run {
                onError(getApplication<Application>().getString(R.string.server_pairing_error_code_required))
                return@launch
            }

            val resolvedBaseUrl = baseUrl.trim().ifBlank {
                parsed.baseUrl ?: existingServer?.baseUrl.orEmpty()
            }
            val effectiveBaseUrl =
                if (resolvedBaseUrl == getApplication<Application>().getString(R.string.add_server_default_api_url) &&
                    !parsed.baseUrl.isNullOrBlank()
                ) {
                    parsed.baseUrl
                } else {
                    resolvedBaseUrl
                }
            if (effectiveBaseUrl.isNullOrBlank()) {
                onError(getApplication<Application>().getString(R.string.server_pairing_error_base_url_required))
                return@launch
            }

            _uiState.value = snapshot.copy(submitting = true, error = null)
            try {
                val client = ApiClient(effectiveBaseUrl)
                try {
                    val health = client.validateConnection()
                    if (!health.ok) {
                        val detail = health.detail?.let { "\n$it" }.orEmpty()
                        throw IllegalStateException("${health.summary}$detail")
                    }

                    val response = client.claimPairingCode(
                        pairingCode = parsed.pairingCode,
                        deviceLabel = "android",
                    )
                    val savedServerId = existingServer?.id ?: UUID.randomUUID().toString()
                    val resolvedLabel = label.trim().ifBlank {
                        existingServer?.label ?: health.normalizedBaseUrl
                    }
                    val trustedHost = TrustedHostMetadata(
                        pairedAt = existingServer?.trustedHost?.pairedAt ?: Instant.now().toString(),
                        lastAutoReconnectAt = existingServer?.trustedHost?.lastAutoReconnectAt,
                        trustedUntil = existingServer?.trustedHost?.trustedUntil,
                        autoReconnectEnabled = trustDevice,
                        pairingMethod = parsed.pairingMethod ?: "manual-code",
                        trustLabel = parsed.trustLabel ?: existingServer?.trustedHost?.trustLabel ?: resolvedLabel,
                        trustedClientId = response.trustedClient.clientId,
                        trustedClientSecret = response.trustedClient.clientSecret,
                    )
                    repo.saveServer(
                        Server(
                            id = savedServerId,
                            label = resolvedLabel,
                            baseUrl = health.normalizedBaseUrl,
                            webUrl = existingServer?.webUrl,
                            token = response.token,
                            appPassword = existingServer?.appPassword,
                            trustedHost = trustedHost,
                        )
                    )
                    repo.setActiveServer(savedServerId)
                    onSuccess(
                        savedServerId,
                        getApplication<Application>().getString(
                            R.string.server_pairing_success_message,
                            resolvedLabel,
                        ),
                    )
                } finally {
                    client.close()
                }
            } catch (error: Exception) {
                val message = userFacingMessage(
                    error,
                    getApplication<Application>().getString(R.string.server_pairing_error_submit_failed),
                )
                _uiState.value = snapshot.copy(
                    loading = false,
                    submitting = false,
                    error = message,
                )
                onError(message)
                return@launch
            }

            _uiState.value = _uiState.value.copy(submitting = false, error = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    serverId: String?,
    onBack: () -> Unit,
    onPairingComplete: (serverId: String) -> Unit,
    viewModel: PairingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val defaultApiUrl = stringResource(R.string.add_server_default_api_url)
    var label by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(defaultApiUrl) }
    var pairingInput by remember { mutableStateOf("") }
    var trustDevice by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(serverId) {
        viewModel.load(serverId)
    }

    LaunchedEffect(uiState.server?.id) {
        val server = uiState.server ?: return@LaunchedEffect
        if (label.isBlank()) {
            label = server.label
        }
        if (baseUrl == defaultApiUrl || baseUrl.isBlank()) {
            baseUrl = server.baseUrl
        }
        trustDevice = server.trustedHost?.autoReconnectEnabled ?: true
    }

    val backDescription = stringResource(R.string.content_desc_back)
    val screenTitle = stringResource(R.string.server_pairing_title)
    val description = stringResource(R.string.server_pairing_description)
    val nameLabel = stringResource(R.string.server_pairing_name_label)
    val apiLabel = stringResource(R.string.server_pairing_api_label)
    val codeLabel = stringResource(R.string.server_pairing_code_label)
    val codeHint = stringResource(R.string.server_pairing_code_hint)
    val trustLabel = stringResource(R.string.server_pairing_trust_label)
    val submitLabel = stringResource(R.string.server_pairing_submit_button)
    val loadingTitle = stringResource(R.string.server_pairing_loading_title)
    val loadingMessage = stringResource(R.string.server_pairing_loading_message)
    val loadingFooter = stringResource(R.string.server_pairing_loading_footer)
    val loadingStateLabel = stringResource(R.string.server_pairing_loading_state_label)

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
        snackbarHost = { FindeckSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.loading -> {
                    TimelineNoticeCard(
                        title = loadingTitle,
                        message = loadingMessage,
                        footer = loadingFooter,
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = loadingStateLabel,
                        content = {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                }

                else -> {
                    val server = uiState.server
                    TimelineNoticeCard(
                        title = server?.label ?: stringResource(R.string.server_pairing_ready_title),
                        message = server?.baseUrl ?: stringResource(R.string.server_pairing_ready_message),
                        footer = description,
                        tone = if (uiState.error.isNullOrBlank()) {
                            TimelineNoticeTone.Neutral
                        } else {
                            TimelineNoticeTone.Warning
                        },
                        stateLabel = if (server == null) {
                            stringResource(R.string.server_pairing_ready_state_label)
                        } else {
                            stringResource(R.string.server_pairing_attached_state_label)
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.server_pairing_ready_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )

                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(nameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(apiLabel) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Dns, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = pairingInput,
                        onValueChange = {
                            pairingInput = it
                            val parsed = parsePairingInput(it)
                            if (!parsed?.baseUrl.isNullOrBlank() &&
                                (baseUrl.isBlank() || baseUrl == defaultApiUrl)
                            ) {
                                baseUrl = parsed?.baseUrl.orEmpty()
                            }
                        },
                        label = { Text(codeLabel) },
                        supportingText = { Text(codeHint) },
                        leadingIcon = {
                            Icon(Icons.Filled.QrCode2, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    TimelineNoticeCard(
                        title = trustLabel,
                        message = stringResource(R.string.server_pairing_trust_message),
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = if (trustDevice) {
                            stringResource(R.string.server_pairing_trust_enabled_state)
                        } else {
                            stringResource(R.string.server_pairing_trust_disabled_state)
                        },
                        content = {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = trustLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Switch(
                                    checked = trustDevice,
                                    onCheckedChange = { trustDevice = it },
                                )
                            }
                        },
                    )

                    Button(
                        onClick = {
                            viewModel.submit(
                                serverId = serverId,
                                label = label,
                                baseUrl = baseUrl,
                                pairingInput = pairingInput,
                                trustDevice = trustDevice,
                                onSuccess = { pairedServerId, message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                    onPairingComplete(pairedServerId)
                                },
                                onError = { message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                },
                            )
                        },
                        enabled = baseUrl.isNotBlank() && pairingInput.isNotBlank() && !uiState.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(submitLabel)
                        }
                    }

                    uiState.error?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.server_pairing_cancel))
                    }
                }
            }
        }
    }
}
