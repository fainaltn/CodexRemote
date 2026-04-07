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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.data.model.Server
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.repository.ServerRepository
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
                val trimmedBase = baseUrl.trimEnd('/')
                val trimmedWeb = webUrl.trimEnd('/')

                // Validate API connectivity before saving
                val client = ApiClient(trimmedBase)
                val healthy = client.checkHealth()
                client.close()
                if (!healthy) {
                    onError("无法连接到 API 服务：$trimmedBase")
                    return@launch
                }

                val server = Server(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { trimmedBase },
                    baseUrl = trimmedBase,
                    webUrl = trimmedWeb.ifBlank { null },
                )
                repo.saveServer(server)
                repo.setActiveServer(server.id)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "连接失败")
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
    var url by remember { mutableStateOf("http://192.168.2.146:31807") }
    var webUrl by remember { mutableStateOf("http://192.168.2.146:31817") }
    val saving by viewModel.saving.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加服务器") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                text = "连接运行在 macOS 上的 CodexRemote 服务。请输入 API 地址，" +
                    "以及网页界面地址。当前项目固定使用 31807 和 31817 端口。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("名称（可选）") },
                placeholder = { Text("我的 Mac") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("API 地址") },
                placeholder = { Text("http://192.168.2.146:31807") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = webUrl,
                onValueChange = { webUrl = it },
                label = { Text("网页界面地址（可选）") },
                placeholder = { Text("http://192.168.2.146:31817") },
                supportingText = { Text("如果走同一个反向代理，也可以留空") },
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
                    Text("连接并保存")
                }
            }
        }
    }
}
