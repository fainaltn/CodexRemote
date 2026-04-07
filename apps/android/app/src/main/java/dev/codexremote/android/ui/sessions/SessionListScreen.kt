package dev.codexremote.android.ui.sessions

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.data.model.Session
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.network.UnauthorizedException
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.data.repository.SessionFolderSortOrder
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

private fun projectLabel(path: String?): String = when {
    path.isNullOrBlank() -> "未归类"
    else -> File(path).name.ifBlank { path }
}

private fun buildProjectFolders(
    sessions: List<Session>,
    draftProjects: List<DraftProject>,
    sortOrder: SessionFolderSortOrder,
    hiddenFolderKeys: Set<String>,
    customOrder: List<String>,
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
            label = projectLabel(sortedSessions.firstOrNull()?.cwd),
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
                label = projectLabel(draft.path),
                path = draft.path,
                sessions = emptyList(),
                updatedAt = draft.addedAt,
                draftOnly = true,
            )
        }

    val visibleFolders = folders.filterNot { it.persistenceKey in hiddenFolderKeys }
    if (visibleFolders.isEmpty()) {
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
    return visibleFolders.sortedWith(comparator)
}

private fun SessionFolderSortOrder.next(): SessionFolderSortOrder = when (this) {
    SessionFolderSortOrder.RECENT -> SessionFolderSortOrder.NAME_ASC
    SessionFolderSortOrder.NAME_ASC -> SessionFolderSortOrder.NAME_DESC
    SessionFolderSortOrder.NAME_DESC -> SessionFolderSortOrder.CUSTOM
    SessionFolderSortOrder.CUSTOM -> SessionFolderSortOrder.RECENT
}

private fun SessionFolderSortOrder.label(): String = when (this) {
    SessionFolderSortOrder.RECENT -> "最近更新"
    SessionFolderSortOrder.NAME_ASC -> "名称 A-Z"
    SessionFolderSortOrder.NAME_DESC -> "名称 Z-A"
    SessionFolderSortOrder.CUSTOM -> "自定义排序"
}

private fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    add(toIndex, removeAt(fromIndex))
}

class SessionListViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

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
            val servers = repo.servers.first()
            val server = servers.find { it.id == serverId }
                ?: throw IllegalStateException("服务器不存在")
            val token = server.token ?: throw IllegalStateException("尚未登录")

            val client = ApiClient(server.baseUrl)
            val response = client.listSessions(token, hostId = "local")
            client.close()
            _sessions.value = response.sessions
        } catch (e: UnauthorizedException) {
            repo.updateToken(serverId, null)
            _authExpired.value = true
            _error.value = e.message ?: "登录已失效，请重新登录"
        } catch (e: Exception) {
            _error.value = e.message ?: "加载会话失败"
        } finally {
            _loading.value = false
        }
    }

    fun renameSession(serverId: String, sessionId: String, title: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val servers = repo.servers.first()
                val server = servers.find { it.id == serverId }
                    ?: throw IllegalStateException("服务器不存在")
                val token = server.token ?: throw IllegalStateException("尚未登录")

                val client = ApiClient(server.baseUrl)
                client.renameSession(token, hostId = "local", sessionId = sessionId, title = title)
                client.close()
                refreshSessions(serverId)
            } catch (e: Exception) {
                _error.value = e.message ?: "重命名会话失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun archiveSessions(serverId: String, sessionIds: List<String>) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val servers = repo.servers.first()
                val server = servers.find { it.id == serverId }
                    ?: throw IllegalStateException("服务器不存在")
                val token = server.token ?: throw IllegalStateException("尚未登录")

                val client = ApiClient(server.baseUrl)
                client.archiveSessions(token, hostId = "local", sessionIds = sessionIds)
                client.close()
                refreshSessions(serverId)
            } catch (e: Exception) {
                _error.value = e.message ?: "归档会话失败"
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
    onNewProject: () -> Unit,
    onNewThread: (cwd: String?) -> Unit,
    onAuthExpired: () -> Unit,
    selectedProjectCwd: String?,
    onProjectHandled: () -> Unit,
    onBack: () -> Unit,
    viewModel: SessionListViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val draftProjects by viewModel.draftProjects.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val authExpired by viewModel.authExpired.collectAsState()
    val collapsedFolderKeys by viewModel.collapsedFolderKeys.collectAsState()
    val hiddenFolderKeys by viewModel.hiddenFolderKeys.collectAsState()
    val folderSortOrder by viewModel.folderSortOrder.collectAsState()
    val customFolderOrder by viewModel.customFolderOrder.collectAsState()
    val folders = remember(sessions, draftProjects, folderSortOrder, hiddenFolderKeys, customFolderOrder) {
        buildProjectFolders(
            sessions = sessions,
            draftProjects = draftProjects,
            sortOrder = folderSortOrder,
            hiddenFolderKeys = hiddenFolderKeys,
            customOrder = customFolderOrder,
        )
    }
    val projectPaths = remember(folders) {
        folders.mapNotNull { it.path }.toSet()
    }
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var hideTarget by remember { mutableStateOf<ProjectFolder?>(null) }
    var renameText by remember { mutableStateOf("") }
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var duplicateProjectPath by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val dragFolders = remember(serverId) { mutableStateListOf<ProjectFolder>() }
    var orderDirty by remember { mutableStateOf(false) }
    val selectionMode = selectedSessionIds.isNotEmpty()

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
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectionMode) "已选择 ${selectedSessionIds.size} 个会话" else "项目文件夹")
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
                            contentDescription = if (selectionMode) "取消选择" else "返回",
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
                                Icon(Icons.Filled.Edit, contentDescription = "重命名")
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.archiveSessions(serverId, selectedSessionIds.toList())
                                selectedSessionIds = emptySet()
                            }
                        ) {
                            Icon(Icons.Filled.Archive, contentDescription = "归档")
                        }
                    } else {
                        ThemeToggleAction(
                            themePreference = themePreference,
                            onToggle = onToggleTheme,
                        )
                        IconButton(onClick = onNewProject) {
                            Icon(Icons.Filled.Add, contentDescription = "新项目")
                        }
                        IconButton(onClick = onOpenInbox) {
                            Icon(Icons.Filled.Inbox, contentDescription = "收件箱")
                        }
                        IconButton(onClick = { viewModel.loadSessions(serverId) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
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
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error ?: "未知错误",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "请刷新重试，或检查网络连接。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            folders.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "还没有项目文件夹",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                Column(modifier = Modifier.padding(padding)) {
                    SessionFolderControls(
                        sortOrder = folderSortOrder,
                        onCycleSort = { viewModel.cycleFolderSort(serverId) },
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        items(dragFolders, key = { folder -> folder.key }) { folder ->
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
                                        reorderEnabled = dragFolders.size > 1,
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
                                                    selected = selectedSessionIds.contains(session.id),
                                                    selectionMode = selectionMode,
                                                    onTap = {
                                                        if (selectionMode) {
                                                            selectedSessionIds = selectedSessionIds.toggle(session.id)
                                                        } else {
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

        if (renameTarget != null) {
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("主题名称") },
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
                        Text("保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text("取消")
                    }
                },
            )
        }

        if (duplicateProjectPath != null) {
            AlertDialog(
                onDismissRequest = { duplicateProjectPath = null },
                title = { Text("项目已存在") },
                text = {
                    Text("会话列表里已经有这个项目目录了，不会重复创建项目区块。")
                },
                confirmButton = {
                    TextButton(onClick = { duplicateProjectPath = null }) {
                        Text("知道了")
                    }
                },
            )
        }

        if (hideTarget != null) {
            AlertDialog(
                onDismissRequest = { hideTarget = null },
                title = { Text("隐藏项目文件夹") },
                text = {
                    Text(
                        "这不会删除项目目录或已有会话，只是先把“${hideTarget?.label.orEmpty()}”从会话列表里隐藏。之后如果再次从“新项目”里选择同一路径，它会重新显示。"
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
                        Text("隐藏")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { hideTarget = null }) {
                        Text("取消")
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

@Composable
private fun SessionFolderControls(
    sortOrder: SessionFolderSortOrder,
    onCycleSort: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "长按文件夹可隐藏，拖动右侧把手排序。当前: ${sortOrder.label()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCycleSort) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = "切换文件夹排序，当前为 ${sortOrder.label()}",
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: ProjectFolder,
    collapsed: Boolean,
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

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 6.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    contentDescription = if (collapsed) "展开文件夹" else "折叠文件夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = folderIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = folder.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onNewThread,
                enabled = !folder.path.isNullOrBlank(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "在该项目中新建会话",
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
                        contentDescription = "按住拖拽排序",
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
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onRename: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onRename,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.lastPreview != null) {
                    Text(
                        text = session.lastPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selectionMode) {
                    Text(
                        text = if (selected) "已选中" else "长按可多选归档",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "这个项目还没有会话",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "点击右侧 + 创建第一条会话。",
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
        }
    }
}
