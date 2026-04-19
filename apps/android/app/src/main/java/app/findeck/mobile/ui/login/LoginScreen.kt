package app.findeck.mobile.ui.login

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.findeck.mobile.R
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.ui.theme.FindeckSnackbarHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class LoginTargetUiState(
    val label: String,
    val baseUrl: String,
)

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServerRepository(app)
    private val appContext = app

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _savedPassword = MutableStateFlow<String?>(null)
    val savedPassword = _savedPassword.asStateFlow()

    private val _target = MutableStateFlow<LoginTargetUiState?>(null)
    val target = _target.asStateFlow()

    fun loadTarget(serverId: String) {
        viewModelScope.launch {
            val servers = repo.servers.first()
            val server = servers.find { it.id == serverId }
            _savedPassword.value = server?.appPassword
            _target.value = server?.toLoginTargetUiState()
        }
    }

    fun login(
        serverId: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val servers = repo.servers.first()
                val server = servers.find { it.id == serverId }
                if (server == null) {
                    onError(appContext.getString(R.string.error_server_not_found))
                    return@launch
                }

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
                    val response = client.login(password, deviceLabel = "android")
                    repo.updateCredentials(
                        serverId = serverId,
                        token = response.token,
                        appPassword = password,
                    )
                    onSuccess()
                } finally {
                    client.close()
                }
            } catch (e: IllegalStateException) {
                onError(e.message ?: ApiClient.describeNetworkFailure(e))
            } catch (e: Exception) {
                onError(ApiClient.describeNetworkFailure(e))
            } finally {
                _loading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    serverId: String,
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: LoginViewModel = viewModel(),
) {
    var password by remember { mutableStateOf("") }
    val loading by viewModel.loading.collectAsState()
    val savedPassword by viewModel.savedPassword.collectAsState()
    val target by viewModel.target.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()

    fun submitLogin() {
        if (password.isBlank() || loading) return
        keyboardController?.hide()
        viewModel.login(
            serverId = serverId,
            password = password,
            onSuccess = onLoginSuccess,
            onError = { msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
    }

    LaunchedEffect(serverId) {
        viewModel.loadTarget(serverId)
    }

    LaunchedEffect(savedPassword) {
        if (password.isBlank() && !savedPassword.isNullOrBlank()) {
            password = savedPassword.orEmpty()
        }
    }

    val title = target?.label ?: stringResource(R.string.login_current_host)
    val baseUrl = target?.baseUrl ?: stringResource(R.string.login_preparing_environment)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.login_screen_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { FindeckSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                )
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            BrandHero(
                title = stringResource(R.string.login_brand_title),
                subtitle = stringResource(R.string.login_brand_subtitle),
                targetLabel = title,
                targetUrl = baseUrl,
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RowHint(
                        icon = Icons.Filled.Dns,
                        title = stringResource(R.string.login_selected_host_title),
                        body = title,
                    )
                    RowHint(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.login_unlock_method_title),
                        body = stringResource(R.string.login_unlock_method_body),
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.login_password_label)) },
                        supportingText = {
                            Text(stringResource(R.string.login_password_hint))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = if (!savedPassword.isNullOrBlank()) {
                                    stringResource(R.string.login_saved_password_detected)
                                } else {
                                    stringResource(R.string.login_saved_password_local_only)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Button(
                        onClick = { submitLogin() },
                        enabled = password.isNotEmpty() && !loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.login_unlocking))
                        } else {
                            Text(stringResource(R.string.login_unlock_button))
                        }
                    }

                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BrandHero(
    title: String,
    subtitle: String,
    targetLabel: String,
    targetUrl: String,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(14.dp),
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            ) {
                Text(
                    text = stringResource(R.string.console_brand_label),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.login_brand_unlock_target, targetLabel),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniPill(text = stringResource(R.string.login_step_verify))
                MiniPill(text = stringResource(R.string.login_step_store))
                MiniPill(text = stringResource(R.string.login_step_enter))
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            ) {
                Text(
                    text = targetUrl,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }

            Text(
                text = stringResource(R.string.login_footer, targetLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RowHint(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(10.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MiniPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun Server.toLoginTargetUiState(): LoginTargetUiState = LoginTargetUiState(
    label = label,
    baseUrl = baseUrl,
)
