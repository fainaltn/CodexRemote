package app.findeck.mobile.ui.sessions

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.findeck.mobile.R
import app.findeck.mobile.data.model.Session
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.network.UnauthorizedException
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.ui.theme.FindeckSnackbarHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ArchivedSessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    private suspend inline fun <T> withServerClient(
        serverId: String,
        crossinline block: suspend (ApiClient, String) -> T,
    ): T {
        val servers = repo.servers.first()
        val server = servers.find { it.id == serverId }
            ?: throw IllegalStateException(getApplication<Application>().getString(R.string.session_list_error_server_missing))
        val token = server.token
            ?: throw IllegalStateException(getApplication<Application>().getString(R.string.session_list_error_not_logged_in))
        val client = ApiClient(server.baseUrl)
        return try {
            block(client, token)
        } finally {
            client.close()
        }
    }

    fun load(serverId: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val response = withServerClient(serverId) { client, token ->
                    client.listArchivedSessions(token, hostId = "local")
                }
                _sessions.value = response.sessions
            } catch (error: UnauthorizedException) {
                repo.updateToken(serverId, null)
                _error.value = error.message
            } catch (error: Exception) {
                _error.value = userFacingMessage(
                    error,
                    getApplication<Application>().getString(R.string.session_list_error_load_failed),
                )
            } finally {
                _loading.value = false
            }
        }
    }

    fun unarchive(
        serverId: String,
        sessionId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                withServerClient(serverId) { client, token ->
                    client.unarchiveSession(token, hostId = "local", sessionId = sessionId)
                }
                _sessions.value = _sessions.value.filterNot { it.id == sessionId }
                onSuccess()
            } catch (error: Exception) {
                onError(
                    userFacingMessage(
                        error,
                        getApplication<Application>().getString(R.string.session_list_error_unarchive_failed),
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedSessionsScreen(
    serverId: String,
    onBack: () -> Unit,
    viewModel: ArchivedSessionsViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources

    val filteredSessions = remember(sessions, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            sessions
        } else {
            sessions.filter { session ->
                session.title.lowercase().contains(query) ||
                    session.cwd.orEmpty().lowercase().contains(query) ||
                    session.lastPreview.orEmpty().lowercase().contains(query)
            }
        }
    }

    LaunchedEffect(serverId) {
        viewModel.load(serverId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.session_archived_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
            )
        },
        snackbarHost = { FindeckSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                label = { Text(stringResource(R.string.session_archived_search_label)) },
                placeholder = { Text(stringResource(R.string.session_archived_search_placeholder)) },
            )

            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TimelineNoticeCard(
                            title = stringResource(R.string.session_archived_loading_title),
                            message = stringResource(R.string.session_archived_loading_message),
                            footer = stringResource(R.string.session_archived_loading_footer),
                            tone = TimelineNoticeTone.Neutral,
                            stateLabel = stringResource(R.string.session_archived_loading_state),
                            content = { ShimmerBlock(lines = 2) },
                        )
                    }
                }

                !error.isNullOrBlank() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TimelineNoticeCard(
                            title = stringResource(R.string.session_archived_error_title),
                            message = error.orEmpty(),
                            footer = stringResource(R.string.session_archived_error_footer),
                            tone = TimelineNoticeTone.Error,
                            stateLabel = stringResource(R.string.session_archived_error_state),
                            content = {
                                Button(onClick = { viewModel.load(serverId) }) {
                                    Text(stringResource(R.string.session_archived_retry))
                                }
                            },
                        )
                    }
                }

                filteredSessions.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TimelineNoticeCard(
                            title = stringResource(R.string.session_archived_empty_title),
                            message = if (searchQuery.isBlank()) {
                                stringResource(R.string.session_archived_empty_message)
                            } else {
                                stringResource(R.string.session_archived_empty_search_message)
                            },
                            footer = stringResource(R.string.session_archived_empty_footer),
                            tone = TimelineNoticeTone.Neutral,
                            stateLabel = stringResource(R.string.session_archived_empty_state),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        items(filteredSessions, key = { it.id }) { session ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = session.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!session.lastPreview.isNullOrBlank()) {
                                            Text(
                                                text = session.lastPreview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = session.cwd ?: stringResource(R.string.session_list_project_label_ungrouped),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            viewModel.unarchive(
                                                serverId = serverId,
                                                sessionId = session.id,
                                                onSuccess = {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            resources.getString(
                                                                R.string.session_archived_unarchive_success,
                                                                session.title,
                                                            )
                                                        )
                                                    }
                                                },
                                                onError = { message ->
                                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                                },
                                            )
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Unarchive,
                                            contentDescription = null,
                                        )
                                        Spacer(modifier = Modifier.height(0.dp))
                                        Text(stringResource(R.string.session_archived_unarchive_action))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
