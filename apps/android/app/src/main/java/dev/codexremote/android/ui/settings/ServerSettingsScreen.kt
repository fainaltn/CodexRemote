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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
                    ?: throw IllegalStateException("服务器不存在")
                _uiState.value = ServerSettingsUiState(
                    loading = false,
                    server = server,
                )
            } catch (error: Exception) {
                _uiState.value = ServerSettingsUiState(
                    loading = false,
                    error = userFacingMessage(error, "加载设置失败"),
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
                onError("服务器不存在")
                return@launch
            }
            val token = server.token ?: run {
                onError("当前登录已失效，请重新登录")
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
                    onSuccess("密码已更新并重新连接")
                } else {
                    repo.updateCredentials(
                        serverId = serverId,
                        token = server.token,
                        appPassword = newPassword,
                    )
                    load(serverId)
                    onSuccess(
                        userFacingMessage(
                            reloginError ?: IllegalStateException("服务正在重启"),
                            "密码已更新，服务正在重启，请稍后再试",
                        )
                    )
                }
            } catch (error: Exception) {
                val message = userFacingMessage(error, "更新密码失败")
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        title = "正在读取设置",
                        message = "正在校验当前服务器并整理它的连接配置。",
                        footer = "这通常只需要几秒钟。",
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = "加载中",
                        content = {
                            ShimmerBlock(lines = 2)
                        },
                    )
                }

                uiState.server == null -> {
                    TimelineNoticeCard(
                        title = "设置加载失败",
                        message = uiState.error ?: "当前服务器不可用",
                        footer = "检查网络或主机状态后再试。",
                        tone = TimelineNoticeTone.Error,
                        stateLabel = "错误",
                        content = {
                            Button(
                                onClick = { viewModel.load(serverId) },
                            ) {
                                Text("重试加载")
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
                        text = "修改服务端密码。保存后会自动让当前 app 用新密码重新连接。",
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
                        label = { Text("原密码") },
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
                        label = { Text("新密码") },
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
                        label = { Text("重复新密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val validation = when {
                                currentPassword.isBlank() -> "请输入原密码"
                                newPassword.isBlank() -> "请输入新密码"
                                confirmPassword.isBlank() -> "请再次输入新密码"
                                newPassword != confirmPassword -> "两次输入的新密码不一致"
                                currentPassword == newPassword -> "新密码不能和原密码相同"
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
                            Text("保存并应用新密码")
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
