package dev.codexremote.android.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

private enum class AttachmentTarget { Composer, Edit }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    serverId: String,
    hostId: String,
    sessionId: String?,
    initialCwd: String?,
    themePreference: ThemePreference,
    onToggleTheme: () -> Unit,
    onSessionCreated: (hostId: String, sessionId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val session = uiState.session
    val topBarState = rememberTopAppBarState()
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topBarState)
    // Use ViewModel derived flows instead of screen-level remember blocks
    val historyRounds by viewModel.historyRoundsFlow.collectAsState(initial = emptyList())
    val latestPrompt by viewModel.latestUserPromptFlow.collectAsState(initial = null)
    val latestReply by viewModel.latestAssistantReplyFlow.collectAsState(initial = null)
    val stableDetailKey = sessionId ?: "draft:${initialCwd.orEmpty()}"
    val isDraftSession = sessionId == null
    val listState = rememberLazyListState()
    var expandedRounds by remember(stableDetailKey) { mutableStateOf(setOf<String>()) }
    var autoFollowLive by remember(stableDetailKey) { mutableStateOf(true) }
    var pendingLiveUpdates by remember(stableDetailKey) { mutableStateOf(0) }
    var previousLiveStatus by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var refreshedTerminalRunId by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var attachmentTarget by remember(stableDetailKey) { mutableStateOf(AttachmentTarget.Composer) }
    val composerAttachments = remember(uiState.pendingArtifacts, uiState.pendingLocalAttachments) {
        buildList {
            addAll(
                uiState.pendingArtifacts.map {
                    ComposerAttachmentItem(
                        id = it.id,
                        originalName = it.originalName,
                        mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes,
                        localOnly = false,
                    )
                }
            )
            addAll(
                uiState.pendingLocalAttachments.map {
                    ComposerAttachmentItem(
                        id = it.id,
                        originalName = it.originalName,
                        mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes,
                        localOnly = true,
                    )
                }
            )
        }
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            if (sessionId.isNullOrBlank() && attachmentTarget == AttachmentTarget.Composer) {
                viewModel.queueLocalAttachment(uri)
            } else {
                if (sessionId.isNullOrBlank()) {
                    viewModel.showError("请先创建会话后再添加附件。")
                } else {
                    viewModel.uploadAttachment(serverId, sessionId, uri)
                }
            }
        }
    }
    val isRunning = uiState.liveRun?.status in activeRunStatuses
    val cleanedOutput by viewModel.cleanedOutputFlow.collectAsState(initial = null)

    // ── Side effects ─────────────────────────────────────────────

    LaunchedEffect(serverId, hostId, sessionId) {
        if (!sessionId.isNullOrBlank()) {
            viewModel.load(serverId, sessionId)
        } else {
            viewModel.prepareDraft()
        }
    }

    LaunchedEffect(uiState.archived) {
        if (uiState.archived) {
            viewModel.consumeArchived()
            onBack()
        }
    }

    LaunchedEffect(uiState.createdSessionId) {
        val createdSessionId = uiState.createdSessionId ?: return@LaunchedEffect
        viewModel.consumeCreatedSession()
        onSessionCreated(hostId, createdSessionId)
    }

    DisposableEffect(serverId, sessionId) {
        if (!sessionId.isNullOrBlank()) {
            viewModel.startLiveRunStream(serverId, sessionId)
        }
        onDispose {
            viewModel.stopLiveRunStream()
        }
    }

    // Auto-follow scroll tracking
    LaunchedEffect(listState, stableDetailKey) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems == 0 || lastVisible >= totalItems - 2
        }
            .distinctUntilChanged()
            .collect { nearBottom ->
                autoFollowLive = nearBottom
            }
    }

    LaunchedEffect(autoFollowLive, isRunning) {
        if (autoFollowLive || !isRunning) {
            pendingLiveUpdates = 0
        }
    }

    // Auto-refresh when a run transitions to terminal
    LaunchedEffect(sessionId, uiState.liveRun?.status, uiState.liveRun?.id) {
        if (sessionId.isNullOrBlank()) return@LaunchedEffect
        val status = uiState.liveRun?.status
        if (status in activeRunStatuses) {
            refreshedTerminalRunId = null
        } else if (
            status in terminalRunStatuses &&
            previousLiveStatus in activeRunStatuses &&
            uiState.liveRun?.id != null &&
            refreshedTerminalRunId != uiState.liveRun?.id
        ) {
            refreshedTerminalRunId = uiState.liveRun?.id
            viewModel.refresh(serverId, sessionId)
        }
        previousLiveStatus = status
    }

    // Fallback polling when SSE is disconnected
    LaunchedEffect(serverId, sessionId, uiState.liveStreamConnected, uiState.liveRun?.status) {
        if (sessionId.isNullOrBlank()) return@LaunchedEffect
        if (uiState.liveStreamConnected) return@LaunchedEffect

        while (isActive && !uiState.liveStreamConnected) {
            viewModel.refreshLiveRun(serverId, sessionId)
            if (uiState.liveRun?.status !in activeRunStatuses) {
                break
            }
            delay(1_500L)
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(
        uiState.liveRun?.id,
        uiState.liveRun?.lastOutput,
        uiState.messages.size,
        uiState.error,
        uiState.sending,
        isRunning,
    ) {
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        if (uiState.sending || (isRunning && autoFollowLive)) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LaunchedEffect(uiState.liveRun?.lastOutput, isRunning, autoFollowLive) {
        if (isRunning && !autoFollowLive && !uiState.liveRun?.lastOutput.isNullOrBlank()) {
            pendingLiveUpdates += 1
        }
    }

    // ── UI ────────────────────────────────────────────────────────

    Scaffold(
        modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session?.title ?: detailProjectLabel(initialCwd),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Subtitle: project path or session info
                        val subtitle = session?.cwd ?: initialCwd
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = java.io.File(subtitle).name.ifBlank { subtitle },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    ThemeToggleAction(
                        themePreference = themePreference,
                        onToggle = onToggleTheme,
                    )
                    if (!sessionId.isNullOrBlank() && session?.archivedAt == null) {
                        IconButton(
                            onClick = { viewModel.archiveSession(serverId, sessionId) },
                            enabled = !uiState.archiving,
                        ) {
                            if (uiState.archiving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.Archive, contentDescription = "归档")
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            if (!sessionId.isNullOrBlank()) {
                                viewModel.refresh(serverId, sessionId)
                            }
                        },
                        enabled = !sessionId.isNullOrBlank(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.loading && session == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            session == null && !isDraftSession -> {
                ErrorState(
                    message = uiState.error ?: "会话不可用",
                    onRetry = { if (!sessionId.isNullOrBlank()) viewModel.load(serverId, sessionId) },
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding(),
                ) {
                    if (uiState.loading || uiState.refreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    PullToRefreshBox(
                        isRefreshing = uiState.refreshing,
                        onRefresh = {
                            if (!sessionId.isNullOrBlank()) {
                                viewModel.refresh(serverId, sessionId)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ConversationTimeline(
                                listState = listState,
                                historyRounds = historyRounds,
                                expandedRounds = expandedRounds,
                                onToggleRound = { roundId ->
                                    expandedRounds = if (expandedRounds.contains(roundId)) {
                                        expandedRounds - roundId
                                    } else {
                                        expandedRounds + roundId
                                    }
                                },
                                liveRun = uiState.liveRun,
                                latestUserPrompt = latestPrompt,
                                latestAssistantReply = latestReply,
                                cleanedOutput = cleanedOutput,
                                sending = uiState.sending,
                                isDraft = isDraftSession,
                                liveStreamConnected = uiState.liveStreamConnected,
                                liveStreamStatus = uiState.liveStreamStatus,
                                onRetry = { prompt ->
                                    if (!sessionId.isNullOrBlank()) {
                                        viewModel.resendLatestPrompt(serverId, sessionId, prompt)
                                    }
                                },
                                onReusePrompt = { prompt ->
                                    viewModel.retryLatestPrompt(prompt)
                                },
                            )

                            // Scroll-to-bottom FAB
                            ScrollToBottomFab(
                                visible = !autoFollowLive && isRunning,
                                hasNewContent = pendingLiveUpdates > 0,
                                onClick = {
                                    autoFollowLive = true
                                    pendingLiveUpdates = 0
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 20.dp),
                            )
                        }
                    }

                    // Error banner
                    if (uiState.error != null) {
                        ErrorBanner(
                            message = uiState.error!!,
                            onDismiss = { viewModel.clearError() },
                        )
                    }

                    // Composer bar (always pinned to bottom)
                    ComposerBar(
                        prompt = uiState.prompt,
                        uploading = uiState.uploading,
                        sending = uiState.sending,
                        stopping = uiState.stopping,
                        isRunning = isRunning,
                        attachments = composerAttachments,
                        onPromptChange = { viewModel.setPrompt(it) },
                        onUploadClick = {
                            attachmentTarget = AttachmentTarget.Composer
                            attachmentPicker.launch(arrayOf("*/*"))
                        },
                        onRemoveAttachment = { attachment ->
                            if (attachment.localOnly) {
                                viewModel.removeLocalAttachment(attachment.id)
                            } else {
                                viewModel.removeArtifact(attachment.id)
                            }
                        },
                        onSend = { viewModel.sendPrompt(serverId, sessionId, initialCwd) },
                        onStop = { viewModel.stopRun(serverId, sessionId) },
                        sendContentDescription = if (isDraftSession) "发送首条消息并创建会话" else "发送消息",
                    )
                }
            }
        }
    }
}

// ── Lightweight supporting composables ────────────────────────────

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "加载失败",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "点击重试",
                        modifier = Modifier
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
