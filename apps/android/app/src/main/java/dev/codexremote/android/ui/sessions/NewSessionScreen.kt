package dev.codexremote.android.ui.sessions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.data.model.BrowseProjectsResponse
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.data.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val HOST_ID = "local"

class NewSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _browser = MutableStateFlow<BrowseProjectsResponse?>(null)
    val browser = _browser.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    suspend fun load(serverId: String, path: String? = null) {
        _loading.value = true
        _error.value = null
        try {
            val servers = repo.servers.first()
            val server = servers.find { it.id == serverId }
                ?: throw IllegalStateException(getApplication<Application>().getString(R.string.new_session_error_server_missing))
            val token = server.token ?: throw IllegalStateException(getApplication<Application>().getString(R.string.new_session_error_not_logged_in))
            val client = ApiClient(server.baseUrl)
            try {
                _browser.value = client.browseProjects(token, HOST_ID, path)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            _error.value = userFacingMessage(
                e,
                getApplication<Application>().getString(R.string.new_session_error_load_failed),
            )
        } finally {
            _loading.value = false
        }
    }

    fun openPath(serverId: String, path: String? = null) {
        viewModelScope.launch {
            load(serverId, path)
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    serverId: String,
    initialCwd: String?,
    onProjectSelected: (cwd: String) -> Unit,
    onBack: () -> Unit,
    viewModel: NewSessionViewModel = viewModel(),
) {
    val browser by viewModel.browser.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(serverId, initialCwd) {
        viewModel.load(serverId, initialCwd)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_session_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
                actions = {
                    if (browser?.parentPath != null) {
                        IconButton(onClick = { viewModel.openPath(serverId, browser?.parentPath) }) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.new_session_content_desc_parent),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val cwd = browser?.currentPath ?: return@IconButton
                            onProjectSelected(cwd)
                        },
                        enabled = !loading && !browser?.currentPath.isNullOrBlank(),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.new_session_content_desc_use_current_directory),
                        )
                    }
                    IconButton(onClick = { viewModel.openPath(serverId, browser?.currentPath ?: initialCwd) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.content_desc_refresh),
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.new_session_current_directory_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = browser?.currentPath ?: stringResource(R.string.new_session_current_directory_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                loading && browser == null -> {
                    TimelineNoticeCard(
                        title = stringResource(R.string.new_session_loading_title),
                        message = stringResource(R.string.new_session_loading_message),
                        footer = stringResource(R.string.new_session_loading_footer),
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = stringResource(R.string.new_session_loading_state_label),
                        content = {
                            ShimmerBlock(lines = 2)
                        },
                    )
                }

                !loading && browser?.entries.isNullOrEmpty() -> {
                    TimelineNoticeCard(
                        title = stringResource(R.string.new_session_empty_title),
                        message = stringResource(R.string.new_session_empty_message),
                        footer = stringResource(R.string.new_session_empty_footer),
                        tone = TimelineNoticeTone.Warning,
                        stateLabel = stringResource(R.string.new_session_empty_state_label),
                        content = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.openPath(serverId, browser?.parentPath ?: initialCwd) },
                                    enabled = browser?.parentPath != null || initialCwd != null,
                                ) {
                                    Text(stringResource(R.string.new_session_button_parent))
                                }
                                Button(
                                    onClick = { viewModel.openPath(serverId, browser?.currentPath ?: initialCwd) },
                                    enabled = !loading,
                                ) {
                                    Text(stringResource(R.string.new_session_button_refresh))
                                }
                            }
                        },
                    )
                }

                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(browser?.entries ?: emptyList(), key = { it.path }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.openPath(serverId, entry.path)
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(stringResource(R.string.new_session_entry_separator))
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.new_session_intro_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                TimelineNoticeCard(
                    title = stringResource(R.string.new_session_error_title),
                    message = error ?: "",
                    footer = stringResource(R.string.new_session_error_footer),
                    tone = TimelineNoticeTone.Error,
                    stateLabel = stringResource(R.string.new_session_error_state_label),
                    content = {
                        Button(
                            onClick = { viewModel.openPath(serverId, browser?.currentPath ?: initialCwd) },
                        ) {
                            Text(stringResource(R.string.new_session_error_retry_button))
                        }
                    },
                )
            }

            Button(
                onClick = {
                    val cwd = browser?.currentPath ?: return@Button
                    onProjectSelected(cwd)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && !browser?.currentPath.isNullOrBlank(),
            ) {
                Text(
                    if (loading) {
                        stringResource(R.string.new_session_submit_button_loading)
                    } else {
                        stringResource(R.string.new_session_submit_button_ready)
                    },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
