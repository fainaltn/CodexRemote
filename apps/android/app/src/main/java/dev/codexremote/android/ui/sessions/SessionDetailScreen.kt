package dev.codexremote.android.ui.sessions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.R
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import java.io.File

private enum class AttachmentTarget { Composer, Edit }
private enum class RepoActionDialogTarget { CreateBranch, CheckoutBranch, Commit }
private enum class SessionLoadingStage { Host, Stream, Timeline }

private fun createCameraCaptureUri(context: Context): Uri {
    val cameraDirectory = File(context.cacheDir, "camera").apply { mkdirs() }
    val imageFile = File.createTempFile("camera_", ".jpg", cameraDirectory)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile,
    )
}

@Composable
private fun SessionLoadingCard(stage: SessionLoadingStage) {
    TimelineNoticeCard(
        title = stringResource(R.string.session_detail_loading_title),
        message = stringResource(R.string.session_detail_loading_message),
        footer = stringResource(R.string.session_detail_loading_footer),
        tone = TimelineNoticeTone.Neutral,
        stateLabel = stringResource(R.string.session_detail_loading_state),
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionLoadingStage.entries.forEachIndexed { index, item ->
                    val isActive = item.ordinal <= stage.ordinal
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = when (item) {
                                    SessionLoadingStage.Host -> stringResource(R.string.session_detail_loading_stage_host)
                                    SessionLoadingStage.Stream -> stringResource(R.string.session_detail_loading_stage_stream)
                                    SessionLoadingStage.Timeline -> stringResource(R.string.session_detail_loading_stage_timeline)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            AnimatedContent(stage, label = "session-loading-stage") { currentStage ->
                val stageTitle = when (currentStage) {
                    SessionLoadingStage.Host -> stringResource(R.string.session_detail_loading_stage_host)
                    SessionLoadingStage.Stream -> stringResource(R.string.session_detail_loading_stage_stream)
                    SessionLoadingStage.Timeline -> stringResource(R.string.session_detail_loading_stage_timeline)
                }
                val stageMessage = when (currentStage) {
                    SessionLoadingStage.Host -> stringResource(R.string.session_detail_loading_stage_host_desc)
                    SessionLoadingStage.Stream -> stringResource(R.string.session_detail_loading_stage_stream_desc)
                    SessionLoadingStage.Timeline -> stringResource(R.string.session_detail_loading_stage_timeline_desc)
                }
                Column(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stageTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stageMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ShimmerBlock(lines = 2)
                }
            }
        },
    )
}

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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
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
    var showManualRefreshIndicator by remember(stableDetailKey) { mutableStateOf(false) }
    var showDevDiagnostics by remember { mutableStateOf(false) }
    var previousLiveStatus by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var refreshedTerminalRunId by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var attachmentTarget by remember(stableDetailKey) { mutableStateOf(AttachmentTarget.Composer) }
    var runtimeSheetTarget by remember(stableDetailKey) { mutableStateOf<RuntimeControlTarget?>(null) }
    var repoActionDialogTarget by remember(stableDetailKey) { mutableStateOf<RepoActionDialogTarget?>(null) }
    var repoActionInput by remember(stableDetailKey) { mutableStateOf("") }
    var showRepoLogSheet by remember(stableDetailKey) { mutableStateOf(false) }
    var wasBackgrounded by remember(stableDetailKey) { mutableStateOf(false) }
    var pendingCameraCaptureUri by remember(stableDetailKey) { mutableStateOf<Uri?>(null) }
    val voiceController = remember(stableDetailKey) {
        SessionVoiceInputController(
            context = context.applicationContext,
            scope = coroutineScope,
            onTranscript = { transcript -> viewModel.appendPrompt(transcript) },
            onError = { error ->
                val message = when (error) {
                    SessionVoiceInputError.PermissionDenied ->
                        context.getString(R.string.session_detail_voice_permission_denied)
                    SessionVoiceInputError.Unavailable ->
                        context.getString(R.string.session_detail_voice_unavailable)
                    SessionVoiceInputError.NoMatch ->
                        context.getString(R.string.session_detail_voice_no_match)
                    SessionVoiceInputError.Failed ->
                        context.getString(R.string.session_detail_voice_failed)
                }
                viewModel.showError(message)
            },
        )
    }
    val voiceUiState by voiceController.uiState.collectAsState()
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
                    append(
                        queuedPrompt.rawPrompt.trim().ifBlank {
                            context.getString(R.string.session_detail_queue_default)
                        },
                    )
                    if (queuedPrompt.artifacts.isNotEmpty()) {
                        append(
                            context.getString(
                                R.string.session_detail_queue_attachment_suffix,
                                queuedPrompt.artifacts.size,
                            ),
                        )
                    }
                    queuedPrompt.model?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(runtimeControlLabel(RuntimeControlTarget.Model, it))
                    }
                    queuedPrompt.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                        append(
                            context.getString(
                                R.string.session_detail_queue_reasoning_prefix,
                                runtimeControlLabel(RuntimeControlTarget.ReasoningEffort, it),
                            ),
                        )
                    }
                },
                attachmentCount = queuedPrompt.artifacts.size,
                model = queuedPrompt.model,
                reasoningEffort = queuedPrompt.reasoningEffort,
            )
        }
    }
    val attachPickedUri: (Uri) -> Unit = { uri ->
        if (sessionId.isNullOrBlank() && attachmentTarget == AttachmentTarget.Composer) {
            viewModel.queueLocalAttachment(uri)
        } else {
            if (sessionId.isNullOrBlank()) {
                viewModel.showError(context.getString(R.string.session_detail_add_attachment_requires_session))
            } else {
                viewModel.uploadAttachment(serverId, sessionId, uri)
            }
        }
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(attachPickedUri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val captureUri = pendingCameraCaptureUri
        pendingCameraCaptureUri = null
        if (success && captureUri != null) {
            attachPickedUri(captureUri)
        }
    }
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            viewModel.showError(context.getString(R.string.session_detail_voice_permission_denied))
            return@rememberLauncherForActivityResult
        }
        if (!voiceController.isAvailable()) {
            viewModel.showError(context.getString(R.string.session_detail_voice_unavailable))
            return@rememberLauncherForActivityResult
        }
        voiceController.start(context.getString(R.string.session_detail_voice_prompt))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val isRunning = uiState.liveRun?.status in activeRunStatuses
    val cleanedOutput by viewModel.cleanedOutputFlow.collectAsState(initial = null)
    val loadingStage = when {
        uiState.session == null && uiState.liveStreamStatus.isNullOrBlank() -> SessionLoadingStage.Host
        uiState.liveStreamStatus == context.getString(R.string.session_detail_stream_connecting) -> SessionLoadingStage.Stream
        else -> SessionLoadingStage.Timeline
    }

    // ── Side effects ─────────────────────────────────────────────

    LaunchedEffect(serverId, hostId, sessionId) {
        if (!sessionId.isNullOrBlank()) {
            viewModel.load(serverId, sessionId)
        } else {
            viewModel.prepareDraft()
        }
    }

    LaunchedEffect(sessionId) {
        val notificationPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!notificationPermissionGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    DisposableEffect(voiceController) {
        onDispose {
            voiceController.release()
        }
    }

    DisposableEffect(lifecycleOwner, serverId, sessionId, stableDetailKey) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasBackgrounded = true
                    voiceController.cancel()
                    viewModel.onAppBackgrounded()
                }
                Lifecycle.Event.ON_START -> {
                    if (wasBackgrounded) {
                        wasBackgrounded = false
                        viewModel.onAppForegrounded(serverId, sessionId)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

    LaunchedEffect(uiState.refreshing) {
        if (!uiState.refreshing) {
            showManualRefreshIndicator = false
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

    LaunchedEffect(uiState.recoveryNotice) {
        if (uiState.recoveryNotice != null) {
            delay(3_000L)
            viewModel.clearRecoveryNotice()
        }
    }

    // Fallback polling when SSE is disconnected
    LaunchedEffect(serverId, sessionId, uiState.liveStreamConnected, uiState.liveRun?.status, uiState.appInBackground) {
        if (sessionId.isNullOrBlank()) return@LaunchedEffect
        if (uiState.appInBackground) return@LaunchedEffect
        if (uiState.liveStreamConnected) return@LaunchedEffect
        if (uiState.liveStreamStatus == context.getString(R.string.session_detail_stream_connecting)) return@LaunchedEffect

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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.session_detail_nav_back),
                        )
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
                                Icon(
                                    Icons.Filled.Archive,
                                    contentDescription = stringResource(R.string.session_detail_archive),
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            if (!sessionId.isNullOrBlank()) {
                                showManualRefreshIndicator = true
                                viewModel.refresh(serverId, sessionId)
                            }
                        },
                        enabled = !sessionId.isNullOrBlank() && !uiState.refreshing,
                    ) {
                        if (uiState.refreshing && showManualRefreshIndicator) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.session_detail_refresh),
                            )
                        }
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
                    SessionLoadingCard(stage = loadingStage)
                }
            }

            session == null && !isDraftSession -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.session_detail_unavailable),
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
                    SessionControlStrip(
                        liveRun = uiState.liveRun,
                        liveStreamConnected = uiState.liveStreamConnected,
                        liveStreamStatus = uiState.liveStreamStatus,
                        queuedPromptCount = uiState.queuedPrompts.size,
                        isRefreshing = uiState.refreshing,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    AnimatedVisibility(
                        visible = !uiState.recoveryNotice.isNullOrBlank(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        TimelineNoticeCard(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            title = stringResource(R.string.session_detail_recovery_title),
                            message = uiState.recoveryNotice.orEmpty(),
                            footer = stringResource(R.string.session_detail_recovery_footer),
                            tone = TimelineNoticeTone.Neutral,
                        )
                    }

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
                        onPull = {
                            if (!sessionId.isNullOrBlank()) {
                                viewModel.pullRepo(serverId, sessionId)
                            }
                        },
                        onStash = {
                            if (!sessionId.isNullOrBlank()) {
                                viewModel.stashRepo(serverId, sessionId)
                            }
                        },
                        onShowLog = {
                            if (!sessionId.isNullOrBlank()) {
                                viewModel.loadRepoLog(serverId, sessionId)
                                showRepoLogSheet = true
                            }
                        },
                        onDismissSummary = { viewModel.dismissRepoActionSummary() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )

                    PullToRefreshBox(
                        isRefreshing = uiState.refreshing && showManualRefreshIndicator,
                        onRefresh = {
                            if (!sessionId.isNullOrBlank()) {
                                showManualRefreshIndicator = true
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
                                pendingTurnPrompt = uiState.currentTurnPromptOverride,
                                retainedLiveOutput = uiState.retainedLiveOutput,
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
                    val hasVisibleConversation = session != null ||
                        uiState.messages.isNotEmpty() ||
                        !cleanedOutput.isNullOrBlank() ||
                        !uiState.retainedLiveOutput.isNullOrBlank() ||
                        !uiState.currentTurnPromptOverride.isNullOrBlank()
                    val isTimeoutStyleError = uiState.error?.contains("timeout", ignoreCase = true) == true
                        || uiState.error?.contains("超时") == true

                    if (
                        uiState.error != null &&
                        !uiState.appInBackground &&
                        !(isRunning && uiState.liveStreamConnected) &&
                        !(hasVisibleConversation && isTimeoutStyleError)
                    ) {
                        ErrorBanner(
                            message = uiState.error!!,
                            onDismiss = { viewModel.clearError() },
                        )
                    }

                    VoiceRecordingCapsule(
                        visible = voiceUiState.recording || voiceUiState.transcribing,
                        uiState = voiceUiState,
                        onStop = { voiceController.stop() },
                        onCancel = { voiceController.cancel() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )

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
                        onGalleryClick = {
                            attachmentTarget = AttachmentTarget.Composer
                            attachmentPicker.launch(arrayOf("image/*"))
                        },
                        onCameraClick = {
                            attachmentTarget = AttachmentTarget.Composer
                            runCatching { createCameraCaptureUri(context) }
                                .onSuccess { captureUri ->
                                    pendingCameraCaptureUri = captureUri
                                    cameraLauncher.launch(captureUri)
                                }
                                .onFailure {
                                    viewModel.showError(
                                        context.getString(R.string.session_media_camera_create_failed),
                                    )
                                }
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
                        onVoiceClick = {
                            if (voiceUiState.recording) {
                                voiceController.stop()
                            } else if (voiceUiState.transcribing) {
                                // Keep current transcription running.
                            } else if (!voiceController.isAvailable()) {
                                viewModel.showError(context.getString(R.string.session_detail_voice_unavailable))
                            } else {
                                val permissionGranted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (permissionGranted) {
                                    voiceController.start(context.getString(R.string.session_detail_voice_prompt))
                                } else {
                                    voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onSend = {
                            viewModel.sendPrompt(serverId, sessionId, initialCwd)
                        },
                        onQueue = {
                            viewModel.queuePrompt(sessionId)
                        },
                        onStop = { viewModel.stopRun(serverId, sessionId) },
                        sendContentDescription = stringResource(
                            if (isDraftSession) {
                                R.string.session_detail_send_first
                            } else {
                                R.string.session_detail_send_message
                            },
                        ),
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

    if (showRepoLogSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showRepoLogSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.session_controls_repo_log_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.repoLogLoading) {
                    ShimmerBlock(lines = 3)
                } else if (uiState.repoLogEntries.isEmpty()) {
                    TimelineNoticeCard(
                        title = stringResource(R.string.session_controls_repo_log_title),
                        message = stringResource(R.string.session_controls_repo_log_empty),
                        tone = TimelineNoticeTone.Neutral,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.repoLogEntries.forEach { entry ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = entry.subject,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${entry.shortHash} · ${entry.author}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = formatDate(entry.authoredAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (repoActionDialogTarget != null) {
        val target = repoActionDialogTarget ?: RepoActionDialogTarget.CreateBranch
        AlertDialog(
            onDismissRequest = { repoActionDialogTarget = null },
            title = {
                Text(
                    when (target) {
                        RepoActionDialogTarget.CreateBranch -> stringResource(R.string.session_detail_repo_create_branch_title)
                        RepoActionDialogTarget.CheckoutBranch -> stringResource(R.string.session_detail_repo_checkout_branch_title)
                        RepoActionDialogTarget.Commit -> stringResource(R.string.session_detail_repo_commit_title)
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
                                RepoActionDialogTarget.CreateBranch -> stringResource(R.string.session_detail_repo_branch_name)
                                RepoActionDialogTarget.CheckoutBranch -> stringResource(R.string.session_detail_repo_target_branch)
                                RepoActionDialogTarget.Commit -> stringResource(R.string.session_detail_repo_commit_message)
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
                    Text(stringResource(R.string.session_detail_execute))
                }
            },
            dismissButton = {
                TextButton(onClick = { repoActionDialogTarget = null }) {
                    Text(stringResource(R.string.session_detail_cancel))
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
                    text = stringResource(R.string.session_detail_dev_diag_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                DiagnosticRow("Session ID", sessionId ?: "(draft)")
                DiagnosticRow("Server", serverId)
                DiagnosticRow("Host", hostId)
                DiagnosticRow(
                    stringResource(R.string.session_detail_diag_sse_connection),
                    if (uiState.liveStreamConnected) {
                        stringResource(R.string.session_detail_diag_connected)
                    } else {
                        stringResource(R.string.session_detail_diag_disconnected)
                    },
                )
                DiagnosticRow(stringResource(R.string.session_detail_diag_sse_status), uiState.liveStreamStatus ?: "—")
                DiagnosticRow("Run ID", uiState.liveRun?.id ?: "—")
                DiagnosticRow(stringResource(R.string.session_detail_diag_run_status), uiState.liveRun?.status ?: "—")
                DiagnosticRow(stringResource(R.string.session_detail_diag_model), uiState.liveRun?.model ?: "—")
                DiagnosticRow(stringResource(R.string.session_detail_diag_message_count), "${uiState.messages.size}")
                DiagnosticRow(
                    stringResource(R.string.session_detail_diag_output_length),
                    "${uiState.liveRun?.lastOutput?.length ?: 0} chars",
                )
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
    TimelineNoticeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        title = stringResource(R.string.session_detail_error_banner_title),
        message = message,
        footer = stringResource(R.string.session_detail_error_banner_footer),
        tone = TimelineNoticeTone.Error,
        stateLabel = stringResource(R.string.session_detail_error_state),
        content = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.session_detail_close))
            }
        }
    )
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
        TimelineNoticeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            title = stringResource(R.string.session_detail_error_state_title),
            message = message,
            footer = stringResource(R.string.session_detail_error_state_footer),
            tone = TimelineNoticeTone.Error,
            stateLabel = stringResource(R.string.session_detail_error_state),
            content = {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.session_detail_retry_load))
                }
            }
        )
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
