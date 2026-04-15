package dev.codexremote.android.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
private enum class RepoActionDialogTarget { CreateBranch, CheckoutBranch, Commit }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var showDevDiagnostics by remember { mutableStateOf(false) }
    var previousLiveStatus by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var refreshedTerminalRunId by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var attachmentTarget by remember(stableDetailKey) { mutableStateOf(AttachmentTarget.Composer) }
    var runtimeSheetTarget by remember(stableDetailKey) { mutableStateOf<RuntimeControlTarget?>(null) }
    var repoActionDialogTarget by remember(stableDetailKey) { mutableStateOf<RepoActionDialogTarget?>(null) }
    var repoActionInput by remember(stableDetailKey) { mutableStateOf("") }
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
    val queuedPromptItems = remember(uiState.queuedPrompts) {
        uiState.queuedPrompts.map { queuedPrompt ->
            QueuedPromptItem(
                id = queuedPrompt.id,
                preview = buildString {
                    append(queuedPrompt.rawPrompt.trim().ifBlank { "待发送消息" })
                    if (queuedPrompt.artifacts.isNotEmpty()) {
                        append(" +${queuedPrompt.artifacts.size}附件")
                    }
                    queuedPrompt.model?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(runtimeControlLabel(RuntimeControlTarget.Model, it))
                    }
                    queuedPrompt.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                        append(" · 思考 ")
                        append(runtimeControlLabel(RuntimeControlTarget.ReasoningEffort, it))
                    }
                },
                attachmentCount = queuedPrompt.artifacts.size,
                model = queuedPrompt.model,
                reasoningEffort = queuedPrompt.reasoningEffort,
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
        if (uiState.liveStreamStatus == "正在连接原生实时流…") return@LaunchedEffect

        while (isActive && !uiState.liveStreamConnected) {
            viewModel.refreshLiveRun(serverId, sessionId)
            if (uiState.liveRun?.status !in activeRunStatuses) {
                // Run reached terminal — pull final session data
                viewModel.refresh(serverId, sessionId)
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
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showDevDiagnostics = true },
                        ),
                    ) {
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

                    SessionControlStrip(
                        liveRun = uiState.liveRun,
                        liveStreamConnected = uiState.liveStreamConnected,
                        liveStreamStatus = uiState.liveStreamStatus,
                        queuedPromptCount = uiState.queuedPrompts.size,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    RepoStatusSurface(
                        repoStatus = uiState.repoStatus,
                        actionsEnabled = !isRunning,
                        actionBusy = uiState.repoActionBusy,
                        actionSummary = uiState.repoActionSummary,
                        onCreateBranch = {
                            repoActionDialogTarget = RepoActionDialogTarget.CreateBranch
                            repoActionInput = ""
                        },
                        onCheckoutBranch = {
                            repoActionDialogTarget = RepoActionDialogTarget.CheckoutBranch
                            repoActionInput = ""
                        },
                        onCommit = {
                            repoActionDialogTarget = RepoActionDialogTarget.Commit
                            repoActionInput = ""
                        },
                        onPush = {
                            if (!sessionId.isNullOrBlank()) {
                                viewModel.pushRepo(serverId, sessionId)
                            }
                        },
                        onDismissSummary = { viewModel.dismissRepoActionSummary() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )

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

                    // Error banner — hide timeout-style blips whenever usable content is already visible.
                    val hasVisibleConversation = session != null || uiState.messages.isNotEmpty() || !cleanedOutput.isNullOrBlank()
                    val isTimeoutStyleError = uiState.error?.contains("timeout", ignoreCase = true) == true
                        || uiState.error?.contains("超时") == true

                    if (
                        uiState.error != null &&
                        !(isRunning && uiState.liveStreamConnected) &&
                        !(hasVisibleConversation && isTimeoutStyleError)
                    ) {
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
                        liveStreamConnected = uiState.liveStreamConnected,
                        liveStreamStatus = uiState.liveStreamStatus,
                        selectedModel = uiState.selectedModel,
                        selectedReasoningEffort = uiState.selectedReasoningEffort,
                        attachments = composerAttachments,
                        queuedPrompts = queuedPromptItems,
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
                        onRestoreQueuedPrompt = { queuedPrompt ->
                            viewModel.restoreQueuedPrompt(queuedPrompt.id)
                        },
                        onModelClick = { runtimeSheetTarget = RuntimeControlTarget.Model },
                        onReasoningEffortClick = {
                            runtimeSheetTarget = RuntimeControlTarget.ReasoningEffort
                        },
                        onSend = {
                            viewModel.sendPrompt(serverId, sessionId, initialCwd)
                        },
                        onQueue = {
                            viewModel.queuePrompt(sessionId)
                        },
                        onStop = { viewModel.stopRun(serverId, sessionId) },
                        sendContentDescription = if (isDraftSession) "发送首条消息并创建会话" else "发送消息",
                    )
                }
            }
        }
    }

    if (runtimeSheetTarget != null) {
        val target = runtimeSheetTarget ?: RuntimeControlTarget.Model
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { runtimeSheetTarget = null },
            sheetState = sheetState,
        ) {
            RuntimeControlSheetContent(
                target = target,
                currentValue = when (target) {
                    RuntimeControlTarget.Model -> uiState.selectedModel
                    RuntimeControlTarget.ReasoningEffort -> uiState.selectedReasoningEffort
                },
                onSelect = { value ->
                    when (target) {
                        RuntimeControlTarget.Model -> viewModel.setSelectedModel(value)
                        RuntimeControlTarget.ReasoningEffort -> viewModel.setSelectedReasoningEffort(value)
                    }
                    runtimeSheetTarget = null
                },
            )
        }
    }

    if (repoActionDialogTarget != null) {
        val target = repoActionDialogTarget ?: RepoActionDialogTarget.CreateBranch
        AlertDialog(
            onDismissRequest = { repoActionDialogTarget = null },
            title = {
                Text(
                    when (target) {
                        RepoActionDialogTarget.CreateBranch -> "新建分支"
                        RepoActionDialogTarget.CheckoutBranch -> "切换分支"
                        RepoActionDialogTarget.Commit -> "提交改动"
                    },
                )
            },
            text = {
                OutlinedTextField(
                    value = repoActionInput,
                    onValueChange = { repoActionInput = it },
                    singleLine = target != RepoActionDialogTarget.Commit,
                    label = {
                        Text(
                            when (target) {
                                RepoActionDialogTarget.CreateBranch -> "分支名"
                                RepoActionDialogTarget.CheckoutBranch -> "目标分支"
                                RepoActionDialogTarget.Commit -> "提交说明"
                            },
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val input = repoActionInput.trim()
                        if (input.isNotEmpty()) {
                            when (target) {
                                RepoActionDialogTarget.CreateBranch -> viewModel.createBranch(serverId, sessionId, input)
                                RepoActionDialogTarget.CheckoutBranch -> viewModel.checkoutBranch(serverId, sessionId, input)
                                RepoActionDialogTarget.Commit -> viewModel.commitRepo(serverId, sessionId, input)
                            }
                            repoActionDialogTarget = null
                        }
                    },
                    enabled = repoActionInput.trim().isNotEmpty() && !uiState.repoActionBusy,
                ) {
                    Text("执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { repoActionDialogTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    // Dev diagnostics bottom sheet (long-press TopAppBar title)
    if (showDevDiagnostics) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDevDiagnostics = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "开发者诊断",
                    style = MaterialTheme.typography.titleMedium,
                )
                DiagnosticRow("Session ID", sessionId ?: "(draft)")
                DiagnosticRow("Server", serverId)
                DiagnosticRow("Host", hostId)
                DiagnosticRow("SSE 连接", if (uiState.liveStreamConnected) "已连接" else "未连接")
                DiagnosticRow("SSE 状态", uiState.liveStreamStatus ?: "—")
                DiagnosticRow("Run ID", uiState.liveRun?.id ?: "—")
                DiagnosticRow("Run 状态", uiState.liveRun?.status ?: "—")
                DiagnosticRow("模型", uiState.liveRun?.model ?: "—")
                DiagnosticRow("消息数", "${uiState.messages.size}")
                DiagnosticRow("输出长度", "${uiState.liveRun?.lastOutput?.length ?: 0} chars")
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

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

// ── Developer diagnostics helper ──────────────────────────────────

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
    }
}
