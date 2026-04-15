package dev.codexremote.android.ui.servers

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Server
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.ui.theme.PrecisionConsoleSnackbarHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AddServerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    fun addServer(
        label: String,
        baseUrl: String,
        webUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _saving.value = true
            try {
                val normalizedBase = ApiClient.normalizeBaseUrl(baseUrl)
                val normalizedWeb = webUrl
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(ApiClient::normalizeWebUrl)

                val client = ApiClient(normalizedBase)
                val health = try {
                    client.validateConnection()
                } finally {
                    client.close()
                }
                if (!health.ok) {
                    val detail = health.detail?.let { "\n$it" }.orEmpty()
                    onError("${health.summary}$detail")
                    return@launch
                }

                val server = Server(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { normalizedBase },
                    baseUrl = health.normalizedBaseUrl,
                    webUrl = normalizedWeb,
                )
                repo.saveServer(server)
                repo.setActiveServer(server.id)
                onSuccess()
            } catch (e: Exception) {
                onError(ApiClient.describeNetworkFailure(e))
            } finally {
                _saving.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onServerAdded: () -> Unit,
    onCancel: () -> Unit,
    viewModel: AddServerViewModel = viewModel(),
) {
    var label by remember { mutableStateOf("") }
    val defaultApiUrl = stringResource(R.string.add_server_default_api_url)
    val defaultWebUrl = stringResource(R.string.add_server_default_web_url)
    var url by remember { mutableStateOf(defaultApiUrl) }
    var webUrl by remember { mutableStateOf(defaultWebUrl) }
    val saving by viewModel.saving.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_server_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
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
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.add_server_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.add_server_name_label)) },
                placeholder = { Text(stringResource(R.string.add_server_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.add_server_api_label)) },
                placeholder = { Text(defaultApiUrl) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = webUrl,
                onValueChange = { webUrl = it },
                label = { Text(stringResource(R.string.add_server_web_label)) },
                placeholder = { Text(defaultWebUrl) },
                supportingText = { Text(stringResource(R.string.add_server_web_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.addServer(
                        label = label,
                        baseUrl = url,
                        webUrl = webUrl,
                        onSuccess = onServerAdded,
                        onError = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                    )
                },
                enabled = url.length > 7 && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.add_server_save_button))
                }
            }
        }
    }
}
