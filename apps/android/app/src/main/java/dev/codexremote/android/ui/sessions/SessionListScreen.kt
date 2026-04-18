package dev.codexremote.android.ui.sessions

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Session
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.network.UnauthorizedException
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.data.repository.SessionFolderSortOrder
import dev.codexremote.android.ui.theme.PrecisionConsoleSnackbarHost
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.res.Resources
import java.time.Duration
import java.time.Instant
import java.io.File
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class ProjectFolder(
    val key: String,
    val persistenceKey: String,
    val label: String,
    val path: String?,
    val sessions: List<Session>,
    val updatedAt: String,
    val draftOnly: Boolean,
)

data class DraftProject(
    val path: String,
    val addedAt: String,
)

private fun projectLabel(resources: Resources, path: String?): String = when {
    path.isNullOrBlank() -> resources.getString(R.string.session_list_project_label_ungrouped)
    else -> File(path).name.ifBlank { path }
}

private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private data class SessionRecency(
    val label: String,
    val emphasis: SessionEmphasis,
    val isRecent: Boolean,
)

private enum class SessionEmphasis {
    Primary,
    Accent,
    Secondary,
    Neutral,
}

private fun sessionRecency(session: Session, resources: Resources): SessionRecency {
    val updatedAt = parseInstantOrNull(session.updatedAt)
        ?: return SessionRecency(
            label = resources.getString(R.string.session_list_recency_history),
            emphasis = SessionEmphasis.Neutral,
            isRecent = false,
        )
    val age = Duration.between(updatedAt, Instant.now()).coerceAtLeast(Duration.ZERO)
    return when {
        age.toMinutes() < 5 -> SessionRecency(
            label = resources.getString(R.string.session_list_recency_just_active),
            emphasis = SessionEmphasis.Accent,
            isRecent = true,
        )
        age.toMinutes() < 60 -> SessionRecency(
            label = resources.getString(R.string.session_list_recency_recent_hour),
            emphasis = SessionEmphasis.Accent,
            isRecent = true,
        )
        age.toHours() < 24 -> SessionRecency(
            label = resources.getString(R.string.session_list_recency_today),
            emphasis = SessionEmphasis.Secondary,
            isRecent = true,
        )
        else -> SessionRecency(
            label = resources.getString(R.string.session_list_recency_history),
            emphasis = SessionEmphasis.Neutral,
            isRecent = false,
        )
    }
}

private data class FolderSummary(
    val totalSessions: Int,
    val recentSessions: Int,
)

private fun folderSummary(folder: ProjectFolder): FolderSummary {
    val recentSessions = folder.sessions.count { session ->
        val updatedAt = parseInstantOrNull(session.updatedAt) ?: return@count false
        Duration.between(updatedAt, Instant.now()).coerceAtMost(Duration.ofDays(3650)).toMinutes() < 60
    }
    return FolderSummary(
        totalSessions = folder.sessions.size,
        recentSessions = recentSessions,
    )
}

private fun buildProjectFolders(
    sessions: List<Session>,
    draftProjects: List<DraftProject>,
    sortOrder: SessionFolderSortOrder,
    hiddenFolderKeys: Set<String>,
    customOrder: List<String>,
    resources: Resources,
): List<ProjectFolder> {
    val groups = linkedMapOf<String, MutableList<Session>>()
    for (session in sessions) {
        val key = session.cwd ?: "__ungrouped__"
        groups.getOrPut(key) { mutableListOf() }.add(session)
    }

    val folders = groups.map { (key, groupSessions) ->
        val sortedSessions = groupSessions.sortedByDescending { it.updatedAt }
        ProjectFolder(
            key = key,
            persistenceKey = sortedSessions.firstOrNull()?.cwd ?: key,
            label = projectLabel(resources, sortedSessions.firstOrNull()?.cwd),
            path = sortedSessions.firstOrNull()?.cwd,
            sessions = sortedSessions,
            updatedAt = sortedSessions.firstOrNull()?.updatedAt.orEmpty(),
            draftOnly = false,
        )
    }.toMutableList()

    val existingPaths = folders.mapNotNull { it.path }.toSet()
    folders += draftProjects
        .filterNot { it.path in existingPaths }
        .map { draft ->
            ProjectFolder(
                key = "draft-${draft.path}",
                persistenceKey = draft.path,
                label = projectLabel(resources, draft.path),
                path = draft.path,
                sessions = emptyList(),
                updatedAt = draft.addedAt,
                draftOnly = true,
            )
        }

    val visibleFolders = folders.filterNot { it.persistenceKey in hiddenFolderKeys }
    val effectiveFolders = when {
        visibleFolders.isNotEmpty() -> visibleFolders
        folders.isNotEmpty() -> folders
        else -> emptyList()
    }
    if (effectiveFolders.isEmpty()) {
        return emptyList()
    }

    val recentComparator = compareByDescending<ProjectFolder> { it.updatedAt }
        .thenBy { it.label.lowercase() }
    val comparator = when (sortOrder) {
        SessionFolderSortOrder.RECENT -> recentComparator
        SessionFolderSortOrder.NAME_ASC -> compareBy<ProjectFolder> { it.label.lowercase() }
            .thenByDescending { it.updatedAt }
        SessionFolderSortOrder.NAME_DESC -> compareByDescending<ProjectFolder> { it.label.lowercase() }
            .thenByDescending { it.updatedAt }
        SessionFolderSortOrder.CUSTOM -> {
            if (customOrder.isEmpty()) {
                recentComparator
            } else {
                val orderIndex = customOrder.withIndex().associate { it.value to it.index }
                compareBy<ProjectFolder> { orderIndex[it.persistenceKey] ?: Int.MAX_VALUE }
                    .then(recentComparator)
            }
        }
    }
    return effectiveFolders.sortedWith(comparator)
}

private fun SessionFolderSortOrder.next(): SessionFolderSortOrder = when (this) {
    SessionFolderSortOrder.RECENT -> SessionFolderSortOrder.NAME_ASC
    SessionFolderSortOrder.NAME_ASC -> SessionFolderSortOrder.NAME_DESC
    SessionFolderSortOrder.NAME_DESC -> SessionFolderSortOrder.CUSTOM
    SessionFolderSortOrder.CUSTOM -> SessionFolderSortOrder.RECENT
}

private fun SessionFolderSortOrder.label(resources: Resources): String = when (this) {
    SessionFolderSortOrder.RECENT -> resources.getString(R.string.session_list_sort_recent)
    SessionFolderSortOrder.NAME_ASC -> resources.getString(R.string.session_list_sort_name_asc)
    SessionFolderSortOrder.NAME_DESC -> resources.getString(R.string.session_list_sort_name_desc)
    SessionFolderSortOrder.CUSTOM -> resources.getString(R.string.session_list_sort_custom)
}

private fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    add(toIndex, removeAt(fromIndex))
}

class SessionListViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)
    private val prewarmSessionIds = mutableSetOf<String>()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _authExpired = MutableStateFlow(false)
    val authExpired = _authExpired.asStateFlow()

    private val _draftProjects = MutableStateFlow<List<DraftProject>>(emptyList())
    val draftProjects = _draftProjects.asStateFlow()

    private val _collapsedFolderKeys = MutableStateFlow<Set<String>>(emptySet())
    val collapsedFolderKeys = _collapsedFolderKeys.asStateFlow()

    private val _hiddenFolderKeys = MutableStateFlow<Set<String>>(emptySet())
    val hiddenFolderKeys = _hiddenFolderKeys.asStateFlow()

    private val _folderSortOrder = MutableStateFlow(SessionFolderSortOrder.RECENT)
    val folderSortOrder = _folderSortOrder.asStateFlow()

    private val _customFolderOrder = MutableStateFlow<List<String>>(emptyList())
    val customFolderOrder = _customFolderOrder.asStateFlow()

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
        val token = server.token ?: throw IllegalStateException(getApplication<Application>().getString(R.string.session_list_error_not_logged_in))
        val client = ApiClient(server.baseUrl)
        return try {
            block(client, token)
        } finally {
            client.close()
        }
    }

    fun initialize(serverId: String) {
        viewModelScope.launch {
            _authExpired.value = false
            _collapsedFolderKeys.value = repo.getCollapsedSessionFolders(serverId)
            _hiddenFolderKeys.value = repo.getHiddenSessionFolders(serverId)
            _folderSortOrder.value = repo.getSessionFolderSortOrder(serverId)
            _customFolderOrder.value = repo.getCustomSessionFolderOrder(serverId)
            refreshSessions(serverId)
        }
    }

    fun loadSessions(serverId: String) {
        viewModelScope.launch {
            refreshSessions(serverId)
        }
    }

    fun prewarmSessionBootstrap(serverId: String, session: Session) {
        if (!prewarmSessionIds.add(session.id)) {
            SessionDetailBootstrapStore.seed(session)
            return
        }
        SessionDetailBootstrapStore.seed(session)
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            runCatching {
                withServerClient(serverId) { client, token ->
                    client.getSessionMessages(
                        token = token,
                        hostId = "local",
                        sessionId = session.id,
                        limit = FOREGROUND_MESSAGE_TAIL_LIMIT,
                    )
                }
            }.onSuccess { payload ->
                val bootstrapLoadMs = SystemClock.elapsedRealtime() - startedAt
                SessionDetailBootstrapStore.store(
                    SessionDetailBootstrapSnapshot(
                        session = session,
                        messages = payload.messages,
                        hasMoreHistory = payload.hasMore,
                        nextBeforeOrderIndex = payload.nextBeforeOrderIndex,
                        bootstrapLoadMs = bootstrapLoadMs,
                    ),
                )
            }.also {
                prewarmSessionIds.remove(session.id)
            }
        }
    }

    fun toggleFolderCollapsed(serverId: String, folderKey: String) {
        viewModelScope.launch {
            val nextValue = _collapsedFolderKeys.value.toggle(folderKey)
            _collapsedFolderKeys.value = nextValue
            repo.setCollapsedSessionFolders(serverId, nextValue)
        }
    }

    fun hideFolder(serverId: String, folderKey: String) {
        viewModelScope.launch {
            val nextHidden = _hiddenFolderKeys.value + folderKey
            _hiddenFolderKeys.value = nextHidden
            repo.setHiddenSessionFolders(serverId, nextHidden)

            if (folderKey in _collapsedFolderKeys.value) {
                val nextCollapsed = _collapsedFolderKeys.value - folderKey
                _collapsedFolderKeys.value = nextCollapsed
                repo.setCollapsedSessionFolders(serverId, nextCollapsed)
            }
        }
    }

    fun restoreHiddenFolder(serverId: String, path: String) {
        viewModelScope.launch {
            if (path !in _hiddenFolderKeys.value) return@launch
            val nextHidden = _hiddenFolderKeys.value - path
            _hiddenFolderKeys.value = nextHidden
            repo.setHiddenSessionFolders(serverId, nextHidden)
        }
    }

    fun cycleFolderSort(serverId: String) {
        viewModelScope.launch {
            val nextValue = _folderSortOrder.value.next()
            _folderSortOrder.value = nextValue
            repo.setSessionFolderSortOrder(serverId, nextValue)
        }
    }

    fun saveCustomFolderOrder(serverId: String, visibleFolderKeys: List<String>) {
        viewModelScope.launch {
            val reorderedVisible = visibleFolderKeys.toMutableList().apply {
                // Keep hidden/stale keys at the tail so they survive future restores.
            }
            val hiddenOrStaleKeys = _customFolderOrder.value.filterNot { it in reorderedVisible }
            val nextOrder = reorderedVisible + hiddenOrStaleKeys
            _customFolderOrder.value = nextOrder
            _folderSortOrder.value = SessionFolderSortOrder.CUSTOM
            repo.setCustomSessionFolderOrder(serverId, nextOrder)
            repo.setSessionFolderSortOrder(serverId, SessionFolderSortOrder.CUSTOM)
        }
    }

    private suspend fun refreshSessions(serverId: String) {
        _loading.value = true
        _error.value = null
        try {
            val response = withServerClient(serverId) { client, token ->
                client.listSessions(token, hostId = "local")
            }
            _sessions.value = response.sessions
            response.sessions
                .take(3)
                .forEach { session ->
                    prewarmSessionBootstrap(serverId, session)
                }
        } catch (e: UnauthorizedException) {
            repo.updateToken(serverId, null)
            _authExpired.value = true
            _error.value = e.message ?: getApplication<Application>().getString(R.string.session_list_error_auth_expired)
        } catch (e: Exception) {
            _error.value = userFacingMessage(
                e,
                getApplication<Application>().getString(R.string.session_list_error_load_failed),
            )
        } finally {
            _loading.value = false
        }
    }

    fun renameSession(serverId: String, sessionId: String, title: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                withServerClient(serverId) { client, token ->
                    client.renameSession(token, hostId = "local", sessionId = sessionId, title = title)
                }
                refreshSessions(serverId)
            } catch (e: Exception) {
                _error.value = userFacingMessage(
                    e,
                    getApplication<Application>().getString(R.string.session_list_error_rename_failed),
                )
            } finally {
                _loading.value = false
            }
        }
    }

    fun archiveSessions(serverId: String, sessionIds: List<String>) {
        archiveSessions(serverId, sessionIds, onSuccess = {}, onError = {})
    }

    fun archiveSessions(
        serverId: String,
        sessionIds: List<String>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                withServerClient(serverId) { client, token ->
                    client.archiveSessions(token, hostId = "local", sessionIds = sessionIds)
                }
                refreshSessions(serverId)
                onSuccess()
            } catch (e: Exception) {
                val message = userFacingMessage(
                    e,
                    getApplication<Application>().getString(R.string.session_list_error_archive_failed),
                )
                _error.value = message
                onError(message)
            } finally {
                _loading.value = false
            }
        }
    }

    fun addDraftProject(cwd: String) {
        _draftProjects.value = buildList {
            add(DraftProject(path = cwd, addedAt = java.time.Instant.now().toString()))
            addAll(_draftProjects.value.filterNot { it.path == cwd })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    serverId: String,
    themePreference: ThemePreference,
    onToggleTheme: () -> Unit,
    onSelectSession: (hostId: String, sessionId: String) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewProject: () -> Unit,
    onNewThread: (cwd: String?) -> Unit,
    onAuthExpired: () -> Unit,
    selectedProjectCwd: String?,
    selectedSessionId: String?,
    onProjectHandled: () -> Unit,
    onBack: () -> Unit,
    viewModel: SessionListViewModel = viewModel(),
) {
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    val localeKey = configuration.locales.toLanguageTags()
    val sessions by viewModel.sessions.collectAsState()
    val draftProjects by viewModel.draftProjects.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val authExpired by viewModel.authExpired.collectAsState()
    val collapsedFolderKeys by viewModel.collapsedFolderKeys.collectAsState()
    val hiddenFolderKeys by viewModel.hiddenFolderKeys.collectAsState()
    val folderSortOrder by viewModel.folderSortOrder.collectAsState()
    val customFolderOrder by viewModel.customFolderOrder.collectAsState()
    val folders = remember(sessions, draftProjects, folderSortOrder, hiddenFolderKeys, customFolderOrder, localeKey) {
        buildProjectFolders(
            sessions = sessions,
            draftProjects = draftProjects,
            sortOrder = folderSortOrder,
            hiddenFolderKeys = hiddenFolderKeys,
            customOrder = customFolderOrder,
            resources = resources,
        )
    }
    val projectPaths = remember(folders) {
        folders.mapNotNull { it.path }.toSet()
    }
    val currentProjectFolder = remember(folders, selectedProjectCwd) {
        val currentPath = selectedProjectCwd?.trim().orEmpty().takeIf { it.isNotBlank() }
        folders.firstOrNull { it.path == currentPath }
    }
    val recentSessionCount = remember(sessions, localeKey) {
        sessions.count { sessionRecency(it, resources).isRecent }
    }
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var hideTarget by remember { mutableStateOf<ProjectFolder?>(null) }
    var renameText by remember { mutableStateOf("") }
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var duplicateProjectPath by remember { mutableStateOf<String?>(null) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dragFolders = remember(serverId) { mutableStateListOf<ProjectFolder>() }
    var orderDirty by remember { mutableStateOf(false) }
    val selectionMode = selectedSessionIds.isNotEmpty()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val normalizedSearch = searchQuery.trim().lowercase()
    val dragFolderSnapshot = dragFolders.toList().ifEmpty { folders }
    val displayFolders = remember(dragFolderSnapshot, normalizedSearch) {
        if (normalizedSearch.isBlank()) {
            dragFolderSnapshot
        } else {
            dragFolderSnapshot.mapNotNull { folder ->
                val folderMatches = folder.label.lowercase().contains(normalizedSearch) ||
                    folder.path.orEmpty().lowercase().contains(normalizedSearch)
                val filteredSessions = folder.sessions.filter { session ->
                    session.title.lowercase().contains(normalizedSearch) ||
                        session.lastPreview.orEmpty().lowercase().contains(normalizedSearch) ||
                        session.cwd.orEmpty().lowercase().contains(normalizedSearch)
                }
                when {
                    folderMatches -> folder
                    filteredSessions.isNotEmpty() -> folder.copy(sessions = filteredSessions)
                    else -> null
                }
            }
        }
    }

    LaunchedEffect(folders) {
        dragFolders.clear()
        dragFolders.addAll(folders)
    }

    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index == to.index) return@rememberReorderableLazyListState
        dragFolders.move(from.index, to.index)
        orderDirty = true
    }

    LaunchedEffect(serverId) {
        viewModel.initialize(serverId)
    }

    LaunchedEffect(selectedProjectCwd, projectPaths, hiddenFolderKeys) {
        val path = selectedProjectCwd?.trim().orEmpty()
        if (path.isBlank()) return@LaunchedEffect
        if (path in hiddenFolderKeys) {
            viewModel.restoreHiddenFolder(serverId, path)
        } else if (path in projectPaths) {
            duplicateProjectPath = path
        } else {
            viewModel.addDraftProject(path)
        }
        onProjectHandled()
    }

    LaunchedEffect(authExpired) {
        if (authExpired) {
            onAuthExpired()
        }
    }

    Scaffold(
        snackbarHost = { PrecisionConsoleSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.console_brand_label),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (selectionMode) {
                                stringResource(R.string.session_list_title_selected_sessions, selectedSessionIds.size)
                            } else {
                                stringResource(R.string.session_list_title_project_folders)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectedSessionIds = emptySet()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) {
                                stringResource(R.string.session_list_content_desc_cancel_selection)
                            } else {
                                stringResource(R.string.content_desc_back)
                            },
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        if (selectedSessionIds.size == 1) {
                            IconButton(
                                onClick = {
                                    val target = sessions.find { it.id == selectedSessionIds.first() }
                                    if (target != null) {
                                        renameTarget = target
                                        renameText = target.title
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.session_list_content_desc_rename))
                            }
                        }
                        IconButton(
                            onClick = {
                                val archivedSelectionCount = selectedSessionIds.size
                                viewModel.archiveSessions(
                                    serverId,
                                    selectedSessionIds.toList(),
                                    onSuccess = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                resources.getString(
                                                    R.string.session_list_archive_success,
                                                    archivedSelectionCount,
                                                ),
                                            )
                                        }
                                    },
                                    onError = { message ->
                                        scope.launch { snackbarHostState.showSnackbar(message) }
                                    },
                                )
                                selectedSessionIds = emptySet()
                            }
                        ) {
                            Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.session_list_content_desc_archive))
                        }
                    } else {
                        ThemeToggleAction(
                            themePreference = themePreference,
                            onToggle = onToggleTheme,
                        )
                        IconButton(
                            onClick = onNewProject,
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.session_list_content_desc_new_project),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { topMenuExpanded = true },
                                modifier = Modifier.size(52.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.session_list_content_desc_menu),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = topMenuExpanded,
                                onDismissRequest = { topMenuExpanded = false },
                                modifier = Modifier.widthIn(min = 220.dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_list_menu_inbox)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Inbox, contentDescription = null)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        onOpenInbox()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_list_menu_archived)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Archive, contentDescription = null)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        onOpenArchived()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_list_menu_refresh)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Refresh, contentDescription = null)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        viewModel.loadSessions(serverId)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_list_menu_settings)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Settings, contentDescription = null)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    TimelineNoticeCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = stringResource(R.string.session_list_loading_title),
                        message = stringResource(R.string.session_list_loading_message),
                        footer = stringResource(R.string.session_list_loading_footer),
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = stringResource(R.string.session_list_loading_state_label),
                        content = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    TimelineNoticeCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = stringResource(R.string.session_list_error_title),
                        message = error ?: stringResource(R.string.session_list_error_unknown),
                        tone = TimelineNoticeTone.Error,
                        footer = stringResource(R.string.session_list_error_footer),
                        stateLabel = stringResource(R.string.session_list_error_state_label),
                        content = {
                            TextButton(onClick = { viewModel.loadSessions(serverId) }) {
                                Text(stringResource(R.string.session_list_error_retry))
                            }
                        },
                    )
                }
            }

            folders.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    TimelineNoticeCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = stringResource(R.string.session_list_empty_projects_title),
                        message = stringResource(R.string.session_list_empty_projects_message),
                        tone = TimelineNoticeTone.Neutral,
                        footer = stringResource(R.string.session_list_empty_projects_footer),
                        stateLabel = stringResource(R.string.session_list_empty_projects_action),
                        content = {
                            Button(onClick = onNewProject) {
                                Text(stringResource(R.string.session_list_empty_projects_action))
                            }
                        },
                    )
                }
            }

            else -> {
                Column(modifier = Modifier.padding(padding)) {
                    SessionNavigationSummary(
                        folderCount = displayFolders.size,
                        sessionCount = displayFolders.sumOf { it.sessions.size },
                        recentSessionCount = recentSessionCount,
                        currentProjectFolder = currentProjectFolder,
                        selectionMode = selectionMode,
                        selectedCount = selectedSessionIds.size,
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        label = { Text(stringResource(R.string.session_list_search_label)) },
                        placeholder = { Text(stringResource(R.string.session_list_search_placeholder)) },
                    )
                    SessionFolderControls(
                        sortOrder = folderSortOrder,
                        onCycleSort = { viewModel.cycleFolderSort(serverId) },
                    )
                    if (displayFolders.isEmpty() && normalizedSearch.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            TimelineNoticeCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = stringResource(R.string.session_list_search_empty_title),
                                message = stringResource(R.string.session_list_search_empty_message),
                                footer = stringResource(R.string.session_list_search_empty_footer),
                                tone = TimelineNoticeTone.Neutral,
                                stateLabel = stringResource(R.string.session_list_search_empty_state),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        ) {
                            items(displayFolders, key = { folder -> folder.key }) { folder ->
                            ReorderableItem(
                                reorderableLazyListState,
                                key = folder.key,
                            ) reorderableItem@{ isDragging ->
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    FolderRow(
                                        folder = folder,
                                        collapsed = folder.persistenceKey in collapsedFolderKeys,
                                        isCurrentProject = folder.path == selectedProjectCwd?.trim().orEmpty().takeIf { it.isNotBlank() },
                                        onToggleCollapsed = {
                                            viewModel.toggleFolderCollapsed(serverId, folder.persistenceKey)
                                        },
                                        onHide = {
                                            hideTarget = folder
                                        },
                                        onNewThread = { onNewThread(folder.path) },
                                        dragHandleModifier = reorderHandleModifier(
                                            scope = this@reorderableItem,
                                            onDragStopped = {
                                                if (orderDirty) {
                                                    viewModel.saveCustomFolderOrder(
                                                        serverId,
                                                        dragFolders.map { it.persistenceKey },
                                                    )
                                                    orderDirty = false
                                                }
                                            },
                                        ),
                                        reorderEnabled = dragFolders.size > 1 && normalizedSearch.isBlank(),
                                        dragging = isDragging,
                                    )

                                    if (folder.persistenceKey !in collapsedFolderKeys) {
                                        Column(
                                            modifier = Modifier.padding(top = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            if (folder.draftOnly && folder.sessions.isEmpty()) {
                                                DraftProjectCard(path = folder.path)
                                            }
                                            folder.sessions.forEach { session ->
                                                SessionCard(
                                                    session = session,
                                                    isCurrentSession = session.id == selectedSessionId,
                                                    selected = selectedSessionIds.contains(session.id),
                                                    selectionMode = selectionMode,
                                                    onTap = {
                                                        if (selectionMode) {
                                                            selectedSessionIds = selectedSessionIds.toggle(session.id)
                                                        } else {
                                                            viewModel.prewarmSessionBootstrap(serverId, session)
                                                            onSelectSession(session.hostId, session.id)
                                                        }
                                                    },
                                                    onRename = {
                                                        if (selectionMode) {
                                                            selectedSessionIds = selectedSessionIds.toggle(session.id)
                                                        } else {
                                                            selectedSessionIds = setOf(session.id)
                                                        }
                                                    },
                                                )
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
        }

        if (renameTarget != null) {
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text(stringResource(R.string.session_list_dialog_rename_title)) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.session_list_dialog_rename_label)) },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = renameTarget ?: return@TextButton
                            val nextTitle = renameText.trim()
                            if (nextTitle.isNotEmpty()) {
                                viewModel.renameSession(serverId, target.id, nextTitle)
                            }
                            renameTarget = null
                        }
                    ) {
                        Text(stringResource(R.string.session_list_dialog_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text(stringResource(R.string.session_list_dialog_cancel))
                    }
                },
            )
        }

        if (duplicateProjectPath != null) {
            AlertDialog(
                onDismissRequest = { duplicateProjectPath = null },
                title = { Text(stringResource(R.string.session_list_dialog_project_exists_title)) },
                text = {
                    Text(stringResource(R.string.session_list_dialog_project_exists_body))
                },
                confirmButton = {
                    TextButton(onClick = { duplicateProjectPath = null }) {
                        Text(stringResource(R.string.session_list_dialog_acknowledge))
                    }
                },
            )
        }

        if (hideTarget != null) {
            AlertDialog(
                onDismissRequest = { hideTarget = null },
                title = { Text(stringResource(R.string.session_list_dialog_hide_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.session_list_dialog_hide_body,
                            hideTarget?.label.orEmpty(),
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = hideTarget ?: return@TextButton
                            viewModel.hideFolder(serverId, target.persistenceKey)
                            hideTarget = null
                        }
                    ) {
                        Text(stringResource(R.string.session_list_dialog_hide_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { hideTarget = null }) {
                        Text(stringResource(R.string.session_list_dialog_cancel))
                    }
                },
            )
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (contains(id)) this - id else this + id

private fun reorderHandleModifier(
    scope: ReorderableCollectionItemScope,
    onDragStopped: () -> Unit,
): Modifier = with(scope) {
    Modifier.draggableHandle(
        onDragStopped = { onDragStopped() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionNavigationSummary(
    folderCount: Int,
    sessionCount: Int,
    recentSessionCount: Int,
    currentProjectFolder: ProjectFolder?,
    selectionMode: Boolean,
    selectedCount: Int,
) {
    val projectCountText = pluralStringResource(
        R.plurals.session_list_navigation_project_count,
        folderCount,
        folderCount,
    )
    val selectedSessionsText = pluralStringResource(
        R.plurals.session_list_navigation_session_count,
        selectedCount,
        selectedCount,
    )
    val sessionCountText = pluralStringResource(
        R.plurals.session_list_navigation_session_count,
        sessionCount,
        sessionCount,
    )
    val recentCountText = pluralStringResource(
        R.plurals.session_list_navigation_recent_count,
        recentSessionCount,
        recentSessionCount,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.console_brand_label),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = if (selectionMode) {
                    stringResource(R.string.session_list_navigation_title_selected)
                } else {
                    stringResource(R.string.session_list_navigation_title_default)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when {
                    selectionMode -> stringResource(R.string.session_list_navigation_subtitle_selected, selectedSessionsText)
                    currentProjectFolder != null -> stringResource(R.string.session_list_navigation_subtitle_current_project, currentProjectFolder.label)
                    else -> stringResource(R.string.session_list_navigation_subtitle_default)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = projectCountText,
                    emphasis = SessionEmphasis.Secondary,
                )
                StatusChip(
                    text = sessionCountText,
                    emphasis = SessionEmphasis.Neutral,
                )
                StatusChip(
                    text = recentCountText,
                    emphasis = if (recentSessionCount > 0) SessionEmphasis.Accent else SessionEmphasis.Neutral,
                )
                if (currentProjectFolder != null) {
                    StatusChip(
                        text = stringResource(R.string.session_list_navigation_current_project_label, currentProjectFolder.label),
                        emphasis = SessionEmphasis.Primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    emphasis: SessionEmphasis,
) {
    val colors = when (emphasis) {
        SessionEmphasis.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SessionEmphasis.Accent -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SessionEmphasis.Secondary -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SessionEmphasis.Neutral -> MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = colors.first,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.second,
        )
    }
}

@Composable
private fun SessionFolderControls(
    sortOrder: SessionFolderSortOrder,
    onCycleSort: () -> Unit,
) {
    val resources = LocalContext.current.resources
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.session_list_browse_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(
                        R.string.session_list_browse_help,
                        sortOrder.label(resources),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCycleSort) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = stringResource(
                        R.string.session_list_browse_sort_toggle_description,
                        sortOrder.label(resources),
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: ProjectFolder,
    collapsed: Boolean,
    isCurrentProject: Boolean,
    onToggleCollapsed: () -> Unit,
    onHide: () -> Unit,
    onNewThread: () -> Unit,
    dragHandleModifier: Modifier,
    reorderEnabled: Boolean,
    dragging: Boolean,
) {
    val leadingIcon = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.ExpandMore
    val folderIcon = if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen
    val folderInteractionSource = remember { MutableInteractionSource() }
    val summary = remember(folder) { folderSummary(folder) }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                dragging -> MaterialTheme.colorScheme.secondaryContainer
                isCurrentProject -> MaterialTheme.colorScheme.primaryContainer
                folder.draftOnly -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = when {
            dragging -> 6.dp
            isCurrentProject -> 2.dp
            else -> 1.dp
        }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        interactionSource = folderInteractionSource,
                        indication = null,
                        onClick = onToggleCollapsed,
                        onLongClick = if (folder.path.isNullOrBlank()) null else onHide,
                    )
                    .padding(top = 6.dp, bottom = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = if (collapsed) {
                        stringResource(R.string.session_list_folder_expand)
                    } else {
                        stringResource(R.string.session_list_folder_collapse)
                    },
                    tint = if (isCurrentProject) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = folderIcon,
                    contentDescription = null,
                    tint = if (isCurrentProject) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = folder.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrentProject) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrentProject) {
                            StatusChip(
                                text = stringResource(R.string.session_list_folder_current_project_chip),
                                emphasis = SessionEmphasis.Primary,
                            )
                        } else if (folder.draftOnly) {
                            StatusChip(
                                text = stringResource(R.string.session_list_folder_draft_chip),
                                emphasis = SessionEmphasis.Secondary,
                            )
                        }
                    }
                    Text(
                        text = when {
                            folder.draftOnly -> stringResource(R.string.session_list_folder_waiting_first_session)
                            summary.recentSessions > 0 -> stringResource(
                                R.string.session_list_folder_summary_with_recent,
                                pluralStringResource(
                                    R.plurals.session_list_navigation_session_count,
                                    summary.totalSessions,
                                    summary.totalSessions,
                                ),
                                pluralStringResource(
                                    R.plurals.session_list_navigation_recent_count,
                                    summary.recentSessions,
                                    summary.recentSessions,
                                ),
                            )
                            else -> stringResource(
                                R.string.session_list_folder_summary_total,
                                pluralStringResource(
                                    R.plurals.session_list_navigation_session_count,
                                    summary.totalSessions,
                                    summary.totalSessions,
                                ),
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrentProject) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onNewThread,
                enabled = !folder.path.isNullOrBlank(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.session_list_folder_new_session_desc),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (reorderEnabled) {
                Box(
                    modifier = dragHandleModifier
                        .size(44.dp)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = stringResource(R.string.session_list_folder_drag_sort_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: Session,
    isCurrentSession: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onRename: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val localeKey = LocalConfiguration.current.locales.toLanguageTags()
    val recency = remember(session.updatedAt, localeKey) { sessionRecency(session, resources) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onRename,
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                recency.emphasis == SessionEmphasis.Primary -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        StatusChip(
                            text = stringResource(R.string.session_list_session_selected_chip),
                            emphasis = SessionEmphasis.Primary,
                        )
                    } else if (isCurrentSession) {
                        StatusChip(
                            text = stringResource(R.string.session_list_session_current_chip),
                            emphasis = SessionEmphasis.Primary,
                        )
                    } else if (recency.emphasis == SessionEmphasis.Primary) {
                        StatusChip(
                            text = recency.label,
                            emphasis = recency.emphasis,
                        )
                    } else if (recency.emphasis == SessionEmphasis.Accent) {
                        StatusChip(
                            text = recency.label,
                            emphasis = SessionEmphasis.Accent,
                        )
                    }
                }
                if (session.lastPreview != null) {
                    Text(
                        text = session.lastPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusChip(
                        text = formatDate(session.updatedAt),
                        emphasis = recency.emphasis,
                    )
                    if (selectionMode && !selected) {
                        StatusChip(
                            text = stringResource(R.string.session_list_session_long_press_multi_select_chip),
                            emphasis = SessionEmphasis.Neutral,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftProjectCard(path: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.session_list_draft_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.session_list_draft_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!path.isNullOrBlank()) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(
                text = stringResource(R.string.session_list_draft_chip),
                emphasis = SessionEmphasis.Secondary,
            )
        }
    }
}
