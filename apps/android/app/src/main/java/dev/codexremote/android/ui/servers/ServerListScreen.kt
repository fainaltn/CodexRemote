package dev.codexremote.android.ui.servers

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Server
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.ui.sessions.TimelineNoticeCard
import dev.codexremote.android.ui.sessions.TimelineNoticeTone
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import dev.codexremote.android.ui.theme.CodexOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerConnectionUiState(
    val checking: Boolean = false,
    val reachable: Boolean = false,
    val degraded: Boolean = false,
    val summary: String = "",
)

class ServerListViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)
    val servers = repo.servers

    private val _connectionStates = MutableStateFlow<Map<String, ServerConnectionUiState>>(emptyMap())
    val connectionStates = _connectionStates.asStateFlow()

    init {
        viewModelScope.launch {
            repo.servers.collectLatest { servers ->
                _connectionStates.value = servers.associate { server ->
                    server.id to ServerConnectionUiState(
                        checking = true,
                        summary = application.getString(R.string.server_connection_checking),
                    )
                }
                servers.forEach { server ->
                    refreshServer(server)
                }
            }
        }
    }

    fun refreshServer(server: Server) {
        _connectionStates.update { states ->
            states + (
                server.id to ServerConnectionUiState(
                    checking = true,
                    summary = getApplication<Application>().getString(R.string.server_connection_checking),
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val state = try {
                val client = ApiClient(server.baseUrl)
                try {
                    val health = client.validateConnection()
                    ServerConnectionUiState(
                        checking = false,
                        reachable = health.ok,
                        degraded = health.degraded,
                        summary = health.summary,
                    )
                } finally {
                    client.close()
                }
            } catch (error: Exception) {
                ServerConnectionUiState(
                    checking = false,
                    reachable = false,
                    degraded = false,
                    summary = ApiClient.describeNetworkFailure(error),
                )
            }

            _connectionStates.update { states -> states + (server.id to state) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    themePreference: ThemePreference,
    onToggleTheme: () -> Unit,
    onAddServer: () -> Unit,
    onSelectServer: (serverId: String) -> Unit,
    onServerAuthenticated: (serverId: String) -> Unit,
    viewModel: ServerListViewModel = viewModel(),
) {
    val servers by viewModel.servers.collectAsState(initial = emptyList())
    val connectionStates by viewModel.connectionStates.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.console_brand_label),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.server_list_top_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ThemeToggleAction(
                        themePreference = themePreference,
                        onToggle = onToggleTheme,
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.server_add_action),
                )
            }
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                TimelineNoticeCard(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    title = stringResource(R.string.server_empty_title),
                    message = stringResource(R.string.server_empty_message),
                    footer = stringResource(R.string.server_empty_footer),
                    tone = TimelineNoticeTone.Neutral,
                    stateLabel = stringResource(R.string.server_empty_state_label),
                    content = {
                        Button(onClick = onAddServer) {
                            Text(stringResource(R.string.server_add_button))
                        }
                    },
                )
            }
        } else {
            Column(modifier = Modifier.padding(padding)) {
                TimelineNoticeCard(
                    title = stringResource(R.string.server_list_intro_title),
                    message = stringResource(R.string.server_list_intro_message),
                    tone = TimelineNoticeTone.Neutral,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 10.dp),
                    footer = stringResource(R.string.server_list_intro_footer),
                    content = {
                        Button(onClick = onAddServer) {
                            Text(stringResource(R.string.server_add_button))
                        }
                    },
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            connectionState = connectionStates[server.id],
                            onTap = {
                                if (server.token != null) {
                                    onServerAuthenticated(server.id)
                                } else {
                                    onSelectServer(server.id)
                                }
                            },
                            onLogin = { onSelectServer(server.id) },
                            onRefresh = { viewModel.refreshServer(server) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: Server,
    connectionState: ServerConnectionUiState?,
    onTap: () -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.label,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.baseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        connectionState?.summary.isNullOrBlank() ->
                            stringResource(R.string.server_connection_checking)
                        else -> connectionState.summary
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        connectionState?.checking == true -> MaterialTheme.colorScheme.primary
                        connectionState?.reachable == true && connectionState?.degraded == true ->
                            MaterialTheme.colorScheme.tertiary
                        connectionState?.reachable == true -> CodexOnline
                        else -> MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = stringResource(R.string.server_refresh_action),
                    )
                }
                if (server.token != null) {
                    Icon(
                        imageVector = if (connectionState?.reachable == false) {
                            Icons.Filled.ErrorOutline
                        } else {
                            Icons.Filled.CheckCircle
                        },
                        contentDescription = if (connectionState?.reachable == false) {
                            stringResource(R.string.server_status_connection_error)
                        } else {
                            stringResource(R.string.server_status_logged_in)
                        },
                        tint = if (connectionState?.reachable == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            CodexOnline
                        },
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    IconButton(onClick = onLogin) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Login,
                            contentDescription = stringResource(R.string.server_login_action),
                        )
                    }
                }
            }
        }
    }
}
