package app.findeck.mobile.ui.servers

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.findeck.mobile.R
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.ui.sessions.TimelineNoticeCard
import app.findeck.mobile.ui.sessions.TimelineNoticeTone
import app.findeck.mobile.ui.theme.CodexOnline
import app.findeck.mobile.ui.theme.ThemePreference
import app.findeck.mobile.ui.theme.ThemeToggleAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
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
    val trustedServers = repo.trustedReconnectServers
    val activeServerId = repo.activeServerId

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

    fun refreshAllServers() {
        viewModelScope.launch {
            repo.servers.firstOrNull().orEmpty().forEach { server ->
                refreshServer(server)
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
    onPairServer: (serverId: String?) -> Unit,
    onSelectServer: (serverId: String) -> Unit,
    onServerAuthenticated: (serverId: String) -> Unit,
    viewModel: ServerListViewModel = viewModel(),
) {
    val servers by viewModel.servers.collectAsState(initial = emptyList())
    val trustedServers by viewModel.trustedServers.collectAsState(initial = emptyList())
    val activeServerId by viewModel.activeServerId.collectAsState(initial = null)
    val connectionStates by viewModel.connectionStates.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refreshAllServers()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.server_list_menu_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_list_menu_pair_host)) },
                                leadingIcon = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onPairServer(null)
                                },
                            )
                            if (activeServerId != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.server_list_menu_pair_active)) },
                                    leadingIcon = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        activeServerId?.let(onPairServer)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_list_menu_refresh)) },
                                leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.refreshAllServers()
                                },
                            )
                        }
                    }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onAddServer) {
                                Text(stringResource(R.string.server_add_button))
                            }
                            TextButton(onClick = { onPairServer(null) }) {
                                Text(stringResource(R.string.server_pair_button))
                            }
                        }
                    },
                )
            }
        } else {
            Column(modifier = Modifier.padding(padding)) {
                TimelineNoticeCard(
                    title = stringResource(R.string.server_list_intro_title),
                    message = if (trustedServers.isEmpty()) {
                        stringResource(R.string.server_list_intro_message)
                    } else {
                        stringResource(
                            R.string.server_list_intro_message_trusted,
                            trustedServers.size,
                        )
                    },
                    tone = TimelineNoticeTone.Neutral,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 10.dp),
                    footer = stringResource(R.string.server_list_intro_footer),
                    stateLabel = if (trustedServers.isEmpty()) {
                        stringResource(R.string.server_list_intro_state_untrusted)
                    } else {
                        stringResource(
                            R.string.server_list_intro_state_trusted,
                            trustedServers.size,
                        )
                    },
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onAddServer) {
                                Text(stringResource(R.string.server_add_button))
                            }
                            TextButton(onClick = { onPairServer(null) }) {
                                Text(stringResource(R.string.server_pair_button))
                            }
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
                            isActive = server.id == activeServerId,
                            onTap = {
                                if (server.token != null) {
                                    onServerAuthenticated(server.id)
                                } else {
                                    onSelectServer(server.id)
                                }
                            },
                            onLogin = { onSelectServer(server.id) },
                            onPair = { onPairServer(server.id) },
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
    isActive: Boolean,
    onTap: () -> Unit,
    onLogin: () -> Unit,
    onPair: () -> Unit,
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConnectionStatePill(
                        text = serverConnectionLabel(server, connectionState),
                        tint = serverConnectionTint(server, connectionState),
                    )
                    if (isActive) {
                        ConnectionStatePill(
                            text = stringResource(R.string.server_status_active),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = connectionState?.summary?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.server_connection_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (!server.hasTrustedPairing) {
                    IconButton(onClick = onPair) {
                        Icon(
                            imageVector = Icons.Filled.VpnKey,
                            contentDescription = stringResource(R.string.server_pair_action),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatePill(
    text: String,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tint),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun serverConnectionLabel(
    server: Server,
    connectionState: ServerConnectionUiState?,
): String = when {
    connectionState?.checking == true -> stringResource(R.string.server_status_reconnecting)
    server.hasTrustedPairing && connectionState?.reachable != false ->
        stringResource(R.string.server_status_trusted)
    connectionState?.reachable == false && server.trustedHost != null ->
        stringResource(R.string.server_status_repair_required)
    connectionState?.reachable == true ->
        stringResource(R.string.server_status_ready)
    server.token != null ->
        stringResource(R.string.server_status_logged_in)
    else ->
        stringResource(R.string.server_status_pair_needed)
}

@Composable
private fun serverConnectionTint(
    server: Server,
    connectionState: ServerConnectionUiState?,
): Color = when {
    connectionState?.checking == true -> MaterialTheme.colorScheme.primary
    server.hasTrustedPairing && connectionState?.reachable != false -> CodexOnline
    connectionState?.reachable == false && server.trustedHost != null ->
        MaterialTheme.colorScheme.error
    connectionState?.reachable == true -> CodexOnline
    server.token != null -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}
