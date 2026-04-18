package dev.codexremote.android.ui.sessions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.codexremote.android.ui.theme.PrecisionConsoleSnackbarHost
import dev.codexremote.android.data.model.FileEntry
import dev.codexremote.android.data.model.PendingApproval
import dev.codexremote.android.data.model.SkillEntry
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

private fun openDownloadedFile(context: Context, file: File, mimeType: String) {
    val fileUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun relativeChildPath(rootPath: String, targetPath: String): String? {
    val normalizedRoot = rootPath.trimEnd('/')
    val normalizedTarget = targetPath.trimEnd('/')
    if (!normalizedTarget.startsWith(normalizedRoot)) return null
    return normalizedTarget.removePrefix(normalizedRoot).trimStart('/').ifBlank { null }
}

private fun queuedPromptRuntimeSummary(
    context: Context,
    queuedPrompt: QueuedPrompt,
): String = buildString {
    append(
        if (queuedPrompt.model.isNullOrBlank()) {
            context.getString(R.string.session_detail_queue_model_inherited)
        } else {
            context.getString(
                R.string.session_detail_queue_model_overridden,
                runtimeControlLabel(RuntimeControlTarget.Model, queuedPrompt.model),
            )
        },
    )
    append(" · ")
    append(
        if (queuedPrompt.reasoningEffort.isNullOrBlank()) {
            context.getString(R.string.session_detail_queue_reasoning_inherited)
        } else {
            context.getString(
                R.string.session_detail_queue_reasoning_overridden,
                runtimeControlLabel(RuntimeControlTarget.ReasoningEffort, queuedPrompt.reasoningEffort),
            )
        },
    )
    append(" · ")
    append(
        when (queuedPrompt.permissionMode) {
            "on-request", "onRequest", "default" ->
                context.getString(R.string.session_detail_queue_permission_default)
            else -> context.getString(R.string.session_detail_queue_permission_full)
        },
    )
}

private fun PendingApproval.toUiItem(context: Context): SessionApprovalUiItem {
    val scope = when (scope.trim().lowercase()) {
        "session" -> SessionApprovalScope.Session
        else -> SessionApprovalScope.Turn
    }
    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: when (kind.trim().lowercase()) {
        "command" -> context.getString(R.string.session_detail_approval_title_command)
        "filechange", "file_change" -> context.getString(R.string.session_detail_approval_title_file_change)
        else -> context.getString(R.string.session_detail_approval_title_permissions)
    }
    val detailParts = buildList {
        reason?.takeIf { it.isNotBlank() }?.let { add(it) }
        command?.takeIf { it.isNotBlank() }?.let { add(it) }
        cwd?.takeIf { it.isNotBlank() }?.let { add(it) }
        networkHost?.takeIf { it.isNotBlank() }?.let { host ->
            add("${networkProtocol ?: "network"}://$host")
        }
        grantRoot?.takeIf { it.isNotBlank() }?.let { add(it) }
        detail?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    return SessionApprovalUiItem(
        id = id,
        title = resolvedTitle,
        detail = detailParts.distinct().joinToString("\n").ifBlank { null },
        kind = kind,
        scope = scope,
        createdAt = createdAt,
    )
}

@Composable
private fun approvalTitle(approval: PendingApproval): String = when (approval.kind) {
    "command" -> stringResource(R.string.session_detail_approval_title_command)
    "fileChange" -> stringResource(R.string.session_detail_approval_title_file_change)
    else -> stringResource(R.string.session_detail_approval_title_permissions)
}

@Composable
private fun approvalMessage(approval: PendingApproval): String =
    approval.reason?.takeIf { it.isNotBlank() }
        ?: approval.detail?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.session_detail_approval_fallback_message)

@Composable
private fun ApprovalCard(
    approval: PendingApproval,
    pendingCount: Int,
    resolving: Boolean,
    onViewDetails: () -> Unit,
    onAllowOnce: () -> Unit,
    onAllowSession: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TimelineNoticeCard(
        modifier = modifier,
        title = approvalTitle(approval),
        message = approvalMessage(approval),
        tone = TimelineNoticeTone.Warning,
        stateLabel = if (pendingCount > 1) {
            stringResource(R.string.session_detail_approval_pending_count, pendingCount)
        } else {
            stringResource(R.string.session_detail_approval_pending_single)
        },
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onViewDetails,
                    enabled = !resolving,
                ) {
                    Text(stringResource(R.string.session_detail_approval_view_details))
                }
                Button(
                    onClick = onAllowOnce,
                    enabled = !resolving,
                ) {
                    Text(stringResource(R.string.session_detail_approval_allow_once))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedActionTextButton(
                    onClick = onAllowSession,
                    enabled = !resolving,
                    text = stringResource(R.string.session_detail_approval_allow_session),
                )
                OutlinedActionTextButton(
                    onClick = onReject,
                    enabled = !resolving,
                    text = stringResource(R.string.session_detail_approval_reject),
                )
            }
        },
    )
}

@Composable
private fun OutlinedActionTextButton(
    onClick: () -> Unit,
    enabled: Boolean,
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 1f else 0.6f),
    ) {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(text)
        }
    }
}

@Composable
private fun ApprovalDetailSheet(
    approval: PendingApproval,
    pendingCount: Int,
    resolving: Boolean,
    onAllowOnce: () -> Unit,
    onAllowSession: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = approvalTitle(approval),
            style = MaterialTheme.typography.titleMedium,
        )
        if (pendingCount > 1) {
            Text(
                text = stringResource(R.string.session_detail_approval_pending_count, pendingCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = approvalMessage(approval),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        approval.command?.takeIf { it.isNotBlank() }?.let { value ->
            ApprovalMetaRow(stringResource(R.string.session_detail_approval_detail_command), value)
        }
        approval.cwd?.takeIf { it.isNotBlank() }?.let { value ->
            ApprovalMetaRow(stringResource(R.string.session_detail_approval_detail_cwd), value)
        }
        approval.networkHost?.takeIf { it.isNotBlank() }?.let { host ->
            val protocol = approval.networkProtocol?.takeIf { it.isNotBlank() } ?: "network"
            ApprovalMetaRow(
                stringResource(R.string.session_detail_approval_detail_network),
                "$protocol://$host",
            )
        }
        approval.grantRoot?.takeIf { it.isNotBlank() }?.let { value ->
            ApprovalMetaRow(stringResource(R.string.session_detail_approval_detail_paths), value)
        }
        approval.permissions?.let { permissions ->
            val pathSummary = buildList {
                permissions.fileSystem?.read?.takeIf { it.isNotEmpty() }?.let { addAll(it) }
                permissions.fileSystem?.write?.takeIf { it.isNotEmpty() }?.let { addAll(it) }
            }.distinct().joinToString("\n")
            if (pathSummary.isNotBlank()) {
                ApprovalMetaRow(stringResource(R.string.session_detail_approval_detail_paths), pathSummary)
            }
        }
        ApprovalMetaRow(
            stringResource(R.string.session_detail_approval_detail_scope),
            if (approval.kind == "permissions") {
                stringResource(R.string.session_detail_approval_scope_turn)
            } else {
                stringResource(R.string.session_detail_approval_scope_session)
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onAllowOnce,
                enabled = !resolving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.session_detail_approval_allow_once))
            }
            Button(
                onClick = onAllowSession,
                enabled = !resolving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.session_detail_approval_allow_session))
            }
        }
        OutlinedActionTextButton(
            onClick = onReject,
            enabled = !resolving,
            text = stringResource(R.string.session_detail_approval_reject),
        )
    }
}

@Composable
private fun ApprovalMetaRow(
    title: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
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
    onOpenNewThread: (cwd: String?) -> Unit,
    onOpenPairing: () -> Unit,
    pendingApprovals: List<SessionApprovalUiItem> = emptyList(),
    onApprovalDecision: (approvalId: String, decision: SessionApprovalDecision) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsState()
    val activeApprovalItems = remember(uiState.pendingApprovals, pendingApprovals, context) {
        if (uiState.pendingApprovals.isNotEmpty()) {
            uiState.pendingApprovals.map { it.toUiItem(context) }
        } else {
            pendingApprovals
        }
    }
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
    var repoStatusExpanded by rememberSaveable(stableDetailKey) { mutableStateOf(false) }
    var showApprovalSheet by remember(stableDetailKey) { mutableStateOf(false) }
    var wasBackgrounded by remember(stableDetailKey) { mutableStateOf(false) }
    var pendingCameraCaptureUri by remember(stableDetailKey) { mutableStateOf<Uri?>(null) }
    var transientBannerMessage by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var transientBannerTone by remember(stableDetailKey) { mutableStateOf(TimelineNoticeTone.Neutral) }
    var voiceModeEnabled by rememberSaveable(stableDetailKey) { mutableStateOf(false) }
    var pendingVoiceHoldStart by remember(stableDetailKey) { mutableStateOf(false) }
    var playingVoiceNotePath by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var resolvedVoiceNoteDurationMs by remember(stableDetailKey) { mutableStateOf<Long?>(null) }
    val voiceNotePlayer = remember(stableDetailKey) { MediaPlayer() }
    val voiceController = remember(stableDetailKey) {
        SessionVoiceInputController(
            context = context.applicationContext,
            scope = coroutineScope,
            onError = { error ->
                val message = when (error) {
                    SessionVoiceInputError.PermissionDenied ->
                        context.getString(R.string.session_detail_voice_permission_denied)
                    SessionVoiceInputError.Unavailable ->
                        context.getString(R.string.session_detail_voice_unavailable)
                    SessionVoiceInputError.TooShort -> null
                    SessionVoiceInputError.NoMatch ->
                        context.getString(R.string.session_detail_voice_no_match)
                    SessionVoiceInputError.Failed ->
                        context.getString(R.string.session_detail_voice_failed)
                }
                if (error == SessionVoiceInputError.TooShort) {
                    transientBannerMessage = localizedSessionText(
                        "按住时间太短，至少说满 2 秒。",
                        "Hold to talk for at least 2 seconds.",
                    )
                    transientBannerTone = TimelineNoticeTone.Warning
                } else if (!message.isNullOrBlank()) {
                    viewModel.showError(message)
                }
            },
            onVoiceNoteReady = { audio ->
                viewModel.sendRecordedVoiceNote(
                    serverId = serverId,
                    sessionId = sessionId,
                    draftCwd = initialCwd,
                    audio = audio,
                )
                voiceModeEnabled = false
                transientBannerMessage = localizedSessionText(
                    "语音已发送，正在处理。",
                    "Voice note sent and processing started.",
                )
                transientBannerTone = TimelineNoticeTone.Neutral
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
    val slashCommandSuggestions = listOf(
        ComposerSuggestion(
            id = "slash-status",
            label = "/status",
            insertText = "/status ",
            detail = stringResource(R.string.composer_suggestion_status_detail),
            kind = ComposerSuggestionKind.Command,
        ),
        ComposerSuggestion(
            id = "slash-archive",
            label = "/archive",
            insertText = "/archive ",
            detail = stringResource(R.string.composer_suggestion_archive_detail),
            kind = ComposerSuggestionKind.Command,
        ),
        ComposerSuggestion(
            id = "slash-new",
            label = "/new",
            insertText = "/new ",
            detail = stringResource(R.string.composer_suggestion_new_detail),
            kind = ComposerSuggestionKind.Command,
        ),
        ComposerSuggestion(
            id = "slash-pair",
            label = "/pair",
            insertText = "/pair ",
            detail = stringResource(R.string.composer_suggestion_pair_detail),
            kind = ComposerSuggestionKind.Command,
        ),
        ComposerSuggestion(
            id = "slash-refresh",
            label = "/refresh",
            insertText = "/refresh ",
            detail = stringResource(R.string.composer_suggestion_refresh_detail),
            kind = ComposerSuggestionKind.Command,
        ),
        ComposerSuggestion(
            id = "slash-stop",
            label = "/stop",
            insertText = "/stop ",
            detail = stringResource(R.string.composer_suggestion_stop_detail),
            kind = ComposerSuggestionKind.Command,
        ),
    )
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
                    append(
                        context.getString(
                            R.string.session_detail_queue_permission_prefix,
                            runtimeControlLabel(RuntimeControlTarget.PermissionMode, queuedPrompt.permissionMode),
                        ),
                    )
                },
                runtimeSummary = queuedPromptRuntimeSummary(context, queuedPrompt),
                attachmentCount = queuedPrompt.artifacts.size,
                model = queuedPrompt.model,
                reasoningEffort = queuedPrompt.reasoningEffort,
                permissionMode = queuedPrompt.permissionMode,
            )
        }
    }
    var composerPromptValue by rememberSaveable(stableDetailKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(uiState.prompt))
    }
    LaunchedEffect(uiState.prompt) {
        if (composerPromptValue.text != uiState.prompt) {
            composerPromptValue = TextFieldValue(
                text = uiState.prompt,
                selection = TextRange(uiState.prompt.length),
            )
        }
    }
    val composerTokenContext = remember(composerPromptValue) {
        detectComposerTokenContext(
            text = composerPromptValue.text,
            cursorPosition = composerPromptValue.selection.end,
        )
    }
    var skillSourceFilter by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var dynamicSkillEntries by remember(stableDetailKey) { mutableStateOf<List<SkillEntry>>(emptyList()) }
    var dynamicFileEntries by remember(stableDetailKey) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var fileBrowsePath by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var fileBrowseParentPath by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    val skillFilterOptions = listOf(
        ComposerSuggestionFilterOption(null, stringResource(R.string.composer_suggestions_filter_all)),
        ComposerSuggestionFilterOption("repo-local", stringResource(R.string.composer_suggestions_filter_repo)),
        ComposerSuggestionFilterOption("user-home", stringResource(R.string.composer_suggestions_filter_home)),
    )
    val skillSuggestions = remember(dynamicSkillEntries) {
        dynamicSkillEntries.map { skill ->
            ComposerSuggestion(
                id = skill.id,
                label = "\$${skill.name}",
                insertText = "\$${skill.name} ",
                detail = buildString {
                    append(
                        if (skill.source == "repo-local") {
                            context.getString(R.string.composer_suggestion_skill_source_repo)
                        } else {
                            context.getString(R.string.composer_suggestion_skill_source_home)
                        },
                    )
                    if (skill.description.isNotBlank()) {
                        append(" · ")
                        append(skill.description)
                    }
                },
                kind = ComposerSuggestionKind.Skill,
            )
        }
    }
    val fileSuggestions = remember(dynamicFileEntries, fileBrowseParentPath, context) {
        buildList {
            fileBrowseParentPath?.let { parentPath ->
                add(
                    ComposerSuggestion(
                        id = "file-up:$parentPath",
                        label = "..",
                        detail = context.getString(R.string.composer_suggestions_file_up),
                        kind = ComposerSuggestionKind.File,
                        browsePath = parentPath,
                    ),
                )
            }
            addAll(dynamicFileEntries.map { entry ->
                ComposerSuggestion(
                    id = entry.path,
                    label = "@${entry.relativePath}",
                    insertText = "@${entry.relativePath} ",
                    detail = if (entry.kind == "directory") {
                        context.getString(R.string.composer_suggestion_file_detail_directory)
                    } else {
                        context.getString(R.string.composer_suggestion_file_detail_workspace)
                    },
                    kind = ComposerSuggestionKind.File,
                    browsePath = if (entry.kind == "directory") entry.relativePath else null,
                )
            })
        }
    }
    LaunchedEffect(serverId, stableDetailKey, composerTokenContext?.prefix, skillSourceFilter) {
        if (composerTokenContext?.prefix != '$') return@LaunchedEffect
        runCatching {
            viewModel.loadSkillSuggestions(serverId, source = skillSourceFilter)
        }.onSuccess { skills ->
            dynamicSkillEntries = skills
        }
    }
    LaunchedEffect(serverId, sessionId, initialCwd, stableDetailKey, composerTokenContext?.prefix, composerTokenContext?.query, fileBrowsePath) {
        if (composerTokenContext?.prefix != '@') {
            dynamicFileEntries = emptyList()
            fileBrowseParentPath = null
            fileBrowsePath = null
            return@LaunchedEffect
        }
        val query = composerTokenContext?.query?.trim().orEmpty()
        if (query.isBlank()) {
            runCatching {
                viewModel.listFileSuggestions(
                    serverId = serverId,
                    sessionId = sessionId,
                    cwd = initialCwd,
                    path = fileBrowsePath,
                )
            }.onSuccess { response ->
                dynamicFileEntries = response.entries
                fileBrowseParentPath = response.parentPath?.let { parent ->
                    if (parent == response.rootPath) "." else relativeChildPath(response.rootPath, parent)
                }
            }
        } else {
            runCatching {
                viewModel.searchFileSuggestions(
                    serverId = serverId,
                    query = query,
                    sessionId = sessionId,
                    cwd = initialCwd,
                    limit = 12,
                )
            }.onSuccess { results ->
                dynamicFileEntries = results
                fileBrowseParentPath = null
            }
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
        val shouldStart = pendingVoiceHoldStart
        pendingVoiceHoldStart = false
        if (!granted) {
            viewModel.showError(context.getString(R.string.session_detail_voice_permission_denied))
            return@rememberLauncherForActivityResult
        }
        if (!voiceController.isAvailable()) {
            viewModel.showError(voiceController.readinessLabel(context))
            return@rememberLauncherForActivityResult
        }
        if (shouldStart) {
            voiceController.start(context.getString(R.string.session_detail_voice_prompt))
        }
    }
    val startVoiceRecording = remember(
        context,
        voiceController,
        voiceUiState.recording,
        voiceUiState.transcribing,
        voicePermissionLauncher
    ) {
        {
            if (voiceUiState.recording) {
            } else if (voiceUiState.transcribing) {
                Unit
            } else if (!voiceController.isAvailable()) {
                viewModel.showError(voiceController.readinessLabel(context))
            } else {
                val permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (permissionGranted) {
                    voiceController.start(context.getString(R.string.session_detail_voice_prompt))
                } else {
                    pendingVoiceHoldStart = true
                    voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    val finishVoiceRecording = remember(voiceController, voiceUiState.recording) {
        {
            if (voiceUiState.recording) {
                voiceController.stop()
            } else {
                pendingVoiceHoldStart = false
            }
        }
    }
    val cancelVoiceRecording = remember(voiceController, voiceUiState.recording, voiceUiState.transcribing) {
        {
            pendingVoiceHoldStart = false
            if (voiceUiState.recording || voiceUiState.transcribing) {
                voiceController.cancel()
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val isRunning = uiState.liveRun?.status in activeRunStatuses
    val cleanedOutput by viewModel.cleanedOutputFlow.collectAsState(initial = null)
    val latestVoiceNote = remember(uiState.liveRun?.prompt, uiState.currentTurnPromptOverride) {
        extractLatestVoiceNoteReference(uiState.liveRun?.prompt)
            ?: extractLatestVoiceNoteReference(uiState.currentTurnPromptOverride)
    }
    val loadingStage = when {
        uiState.session == null && uiState.liveStreamStatus.isNullOrBlank() -> SessionLoadingStage.Host
        uiState.liveStreamStatus == context.getString(R.string.session_detail_stream_connecting) -> SessionLoadingStage.Stream
        else -> SessionLoadingStage.Timeline
    }
    val handleSessionFileDownload: (SessionFileLinkRequest) -> Unit = remember(
        serverId,
        sessionId,
        session?.cwd,
        coroutineScope,
        context,
    ) {
        { request ->
            val currentSessionId = sessionId
            val cwd = session?.cwd
            val relativePath = if (!cwd.isNullOrBlank()) {
                relativeChildPath(cwd, request.target)
            } else {
                null
            }
            if (!currentSessionId.isNullOrBlank() && !relativePath.isNullOrBlank()) {
                coroutineScope.launch {
                    runCatching {
                        viewModel.downloadSessionFile(
                            serverId = serverId,
                            sessionId = currentSessionId,
                            relativePath = relativePath,
                        )
                    }.onSuccess { downloaded ->
                        openDownloadedFile(
                            context = context,
                            file = downloaded.file,
                            mimeType = downloaded.mimeType,
                        )
                    }.onFailure {
                        viewModel.showError(
                            context.getString(R.string.session_detail_error_file_read),
                        )
                    }
                }
            } else if (!currentSessionId.isNullOrBlank() && isLikelyHostAbsolutePath(request.target)) {
                coroutineScope.launch {
                    runCatching {
                        viewModel.downloadAbsoluteFile(
                            serverId = serverId,
                            sessionId = currentSessionId,
                            absolutePath = request.target,
                        )
                    }.onSuccess { downloaded ->
                        openDownloadedFile(
                            context = context,
                            file = downloaded.file,
                            mimeType = downloaded.mimeType,
                        )
                    }.onFailure {
                        viewModel.showError(
                            context.getString(R.string.session_detail_error_file_read),
                        )
                    }
                }
            } else {
                viewModel.showError(
                    context.getString(R.string.session_detail_error_file_read),
                )
            }
        }
    }

    // ── Side effects ─────────────────────────────────────────────

    LaunchedEffect(serverId, hostId, sessionId) {
        if (!sessionId.isNullOrBlank()) {
            viewModel.load(serverId, sessionId)
        } else {
            viewModel.prepareDraft(serverId)
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

    DisposableEffect(voiceNotePlayer) {
        voiceNotePlayer.setOnCompletionListener {
            playingVoiceNotePath = null
        }
        onDispose {
            runCatching { voiceNotePlayer.stop() }
            voiceNotePlayer.release()
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
        val wasActive = previousLiveStatus in activeRunStatuses
        if (status in activeRunStatuses) {
            refreshedTerminalRunId = null
        } else if (
            status in terminalRunStatuses &&
            wasActive &&
            uiState.liveRun?.id != null &&
            refreshedTerminalRunId != uiState.liveRun?.id
        ) {
            refreshedTerminalRunId = uiState.liveRun?.id
            viewModel.refresh(serverId, sessionId)
        }
        if (status in terminalRunStatuses && wasActive) {
            transientBannerMessage = when (status) {
                "completed" -> context.getString(R.string.session_detail_feedback_run_completed)
                "failed" -> context.getString(R.string.session_detail_feedback_run_failed)
                "stopped" -> context.getString(R.string.session_detail_feedback_run_stopped)
                else -> null
            }
            transientBannerTone = when (status) {
                "failed" -> TimelineNoticeTone.Error
                "stopped" -> TimelineNoticeTone.Warning
                else -> TimelineNoticeTone.Neutral
            }
        }
        previousLiveStatus = status
    }

    LaunchedEffect(uiState.recoveryNotice) {
        if (uiState.recoveryNotice != null) {
            if (uiState.recoveryNotice != context.getString(R.string.session_detail_recovery_degraded)) {
                delay(3_000L)
                viewModel.clearRecoveryNotice()
            }
        }
    }

    LaunchedEffect(uiState.repoActionSummary) {
        val summary = uiState.repoActionSummary?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        transientBannerMessage = summary
        transientBannerTone = TimelineNoticeTone.Neutral
        viewModel.dismissRepoActionSummary()
    }

    LaunchedEffect(runtimeSheetTarget) {
        if (runtimeSheetTarget == RuntimeControlTarget.Model ||
            runtimeSheetTarget == RuntimeControlTarget.ReasoningEffort
        ) {
            viewModel.refreshRuntimeCatalog(serverId)
        }
    }

    LaunchedEffect(activeApprovalItems) {
        if (activeApprovalItems.isEmpty()) {
            showApprovalSheet = false
        }
    }

    LaunchedEffect(transientBannerMessage) {
        if (transientBannerMessage != null) {
            delay(8_000L)
            transientBannerMessage = null
            transientBannerTone = TimelineNoticeTone.Neutral
        }
    }

    LaunchedEffect(latestVoiceNote?.absolutePath, latestVoiceNote?.durationMs, sessionId) {
        resolvedVoiceNoteDurationMs = latestVoiceNote?.durationMs
        val voiceNote = latestVoiceNote ?: return@LaunchedEffect
        if (voiceNote.durationMs != null) return@LaunchedEffect
        val currentSessionId = sessionId ?: return@LaunchedEffect
        runCatching {
            if (!voiceNote.artifactId.isNullOrBlank()) {
                viewModel.downloadArtifactFile(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    artifactId = voiceNote.artifactId,
                )
            } else {
                viewModel.downloadAbsoluteFile(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    absolutePath = voiceNote.absolutePath,
                )
            }
        }.onSuccess { downloaded ->
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(downloaded.file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                }
            }.onSuccess { durationMs ->
                if (durationMs != null) {
                    resolvedVoiceNoteDurationMs = durationMs
                }
            }
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

    CompositionLocalProvider(LocalSessionFileLinkHandler provides handleSessionFileDownload) {
        Scaffold(
            modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            snackbarHost = { PrecisionConsoleSnackbarHost(snackbarHostState) },
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
                        selectedModel = uiState.selectedModel,
                        selectedReasoningEffort = uiState.selectedReasoningEffort,
                        queuedPromptCount = uiState.queuedPrompts.size,
                        isRefreshing = uiState.refreshing,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    AnimatedVisibility(
                        visible = uiState.transitionLoading,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        TimelineNoticeCard(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            title = stringResource(R.string.session_detail_transition_title),
                            message = stringResource(R.string.session_detail_transition_message),
                            footer = stringResource(R.string.session_detail_transition_footer),
                            tone = TimelineNoticeTone.Neutral,
                        )
                    }

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

                    AnimatedVisibility(
                        visible = !transientBannerMessage.isNullOrBlank(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        TimelineNoticeCard(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            title = stringResource(R.string.session_detail_feedback_title),
                            message = transientBannerMessage.orEmpty(),
                            tone = transientBannerTone,
                        )
                    }

                    AnimatedVisibility(
                        visible = activeApprovalItems.isNotEmpty(),
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        SessionApprovalPreviewCard(
                            approvals = activeApprovalItems,
                            onOpenDetails = { showApprovalSheet = true },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    RepoStatusSurface(
                        repoStatus = uiState.repoStatus,
                        expanded = repoStatusExpanded,
                        actionsEnabled = !isRunning,
                        actionBusy = uiState.repoActionBusy,
                        actionSummary = uiState.repoActionSummary,
                        onToggleExpanded = { repoStatusExpanded = !repoStatusExpanded },
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
                                hasMoreHistory = uiState.hasMoreHistory,
                                loadingOlderHistory = uiState.loadingOlderHistory,
                                onLoadOlderHistory = {
                                    if (!sessionId.isNullOrBlank()) {
                                        viewModel.loadOlderHistory(serverId, sessionId)
                                    }
                                },
                                onRetry = { prompt ->
                                    if (!sessionId.isNullOrBlank()) {
                                        viewModel.resendLatestPrompt(serverId, sessionId, prompt)
                                    }
                                },
                                onReusePrompt = { prompt ->
                                    viewModel.retryLatestPrompt(prompt)
                                },
                                onDownloadFile = { ref ->
                                    handleSessionFileDownload(
                                        SessionFileLinkRequest(
                                            target = ref.absolutePath,
                                            displayLabel = ref.label,
                                        ),
                                    )
                                },
                                onPlayVoiceNote = { voiceNote ->
                                    val currentSessionId = sessionId
                                    if (currentSessionId.isNullOrBlank()) return@ConversationTimeline
                                    coroutineScope.launch {
                                        runCatching {
                                            if (!voiceNote.artifactId.isNullOrBlank()) {
                                                viewModel.downloadArtifactFile(
                                                    serverId = serverId,
                                                    sessionId = currentSessionId,
                                                    artifactId = voiceNote.artifactId,
                                                )
                                            } else {
                                                viewModel.downloadAbsoluteFile(
                                                    serverId = serverId,
                                                    sessionId = currentSessionId,
                                                    absolutePath = voiceNote.absolutePath,
                                                )
                                            }
                                        }.onSuccess { downloaded ->
                                            runCatching {
                                                MediaMetadataRetriever().use { retriever ->
                                                    retriever.setDataSource(downloaded.file.absolutePath)
                                                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                                        ?.toLongOrNull()
                                                }
                                            }.onSuccess { durationMs ->
                                                if (durationMs != null) {
                                                    resolvedVoiceNoteDurationMs = durationMs
                                                }
                                            }
                                            runCatching {
                                                if (playingVoiceNotePath == voiceNote.absolutePath &&
                                                    voiceNotePlayer.isPlaying
                                                ) {
                                                    voiceNotePlayer.pause()
                                                    playingVoiceNotePath = null
                                                } else {
                                                    voiceNotePlayer.reset()
                                                    voiceNotePlayer.setDataSource(downloaded.file.absolutePath)
                                                    voiceNotePlayer.prepare()
                                                    voiceNotePlayer.start()
                                                    playingVoiceNotePath = voiceNote.absolutePath
                                                }
                                            }.onFailure {
                                                playingVoiceNotePath = null
                                                viewModel.showError(
                                                    context.getString(R.string.session_detail_error_file_read),
                                                )
                                            }
                                        }.onFailure {
                                            playingVoiceNotePath = null
                                            viewModel.showError(
                                                context.getString(R.string.session_detail_error_file_read),
                                            )
                                        }
                                    }
                                },
                                playingVoiceNotePath = playingVoiceNotePath,
                                resolvedVoiceNoteDurationMs = resolvedVoiceNoteDurationMs,
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
                        uiState.recoveryNotice.isNullOrBlank() &&
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
                        promptValue = composerPromptValue,
                        uploading = uiState.uploading,
                        sending = uiState.sending,
                        stopping = uiState.stopping,
                        isRunning = isRunning,
                        liveStreamConnected = uiState.liveStreamConnected,
                        liveStreamStatus = uiState.liveStreamStatus,
                        selectedModel = uiState.selectedModel,
                        selectedReasoningEffort = uiState.selectedReasoningEffort,
                        selectedPermissionMode = uiState.selectedPermissionMode,
                        attachments = composerAttachments,
                        queuedPrompts = queuedPromptItems,
                        slashCommands = slashCommandSuggestions,
                        fileSuggestions = fileSuggestions,
                        skillSuggestions = skillSuggestions,
                        suggestionFilters = skillFilterOptions,
                        selectedSuggestionFilterId = skillSourceFilter,
                        voiceModeEnabled = voiceModeEnabled,
                        voiceRecording = voiceUiState.recording,
                        voicePreparing = voiceUiState.transcribing,
                        onPromptValueChange = { nextValue ->
                            composerPromptValue = nextValue
                            viewModel.setPrompt(nextValue.text)
                        },
                        onSuggestionAction = { suggestion ->
                            if (suggestion.kind == ComposerSuggestionKind.Skill) {
                                transientBannerMessage = localizedSessionText(
                                    "已插入技能 ${suggestion.label}，发送时会优先触发。",
                                    "Inserted ${suggestion.label}; it will be prioritized when you send.",
                                )
                                transientBannerTone = TimelineNoticeTone.Neutral
                            }
                            val slashHandled = when (suggestion.id) {
                                "slash-status" -> {
                                    showDevDiagnostics = true
                                    true
                                }
                                "slash-archive" -> {
                                    if (!sessionId.isNullOrBlank() && session?.archivedAt == null) {
                                        viewModel.archiveSession(serverId, sessionId)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                "slash-pair" -> {
                                    onOpenPairing()
                                    true
                                }
                                "slash-new" -> {
                                    onOpenNewThread(session?.cwd ?: initialCwd)
                                    true
                                }
                                "slash-refresh" -> {
                                    if (!sessionId.isNullOrBlank()) {
                                        viewModel.refresh(serverId, sessionId)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                "slash-stop" -> {
                                    if (isRunning) {
                                        viewModel.stopRun(serverId, sessionId)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                            if (slashHandled) {
                                true
                            } else if (!suggestion.browsePath.isNullOrBlank()) {
                                fileBrowsePath = suggestion.browsePath
                                true
                            } else {
                                false
                            }
                        },
                        onSuggestionFilterSelect = { selected ->
                            skillSourceFilter = selected
                        },
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
                        onPermissionModeClick = {
                            runtimeSheetTarget = RuntimeControlTarget.PermissionMode
                        },
                        onVoiceClick = {
                            if (voiceUiState.recording || voiceUiState.transcribing) {
                                cancelVoiceRecording()
                            } else {
                                voiceModeEnabled = !voiceModeEnabled
                                if (voiceModeEnabled) {
                                    transientBannerMessage = localizedSessionText(
                                        "按住语音按钮说话，松开后自动发送，最长 90 秒。",
                                        "Hold the voice button to talk and release to send automatically. Max 90 seconds.",
                                    )
                                    transientBannerTone = TimelineNoticeTone.Neutral
                                }
                            }
                        },
                        onVoiceHoldStart = {
                            startVoiceRecording()
                        },
                        onVoiceHoldEnd = {
                            finishVoiceRecording()
                        },
                        onVoiceHoldCancel = {
                            cancelVoiceRecording()
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
                    RuntimeControlTarget.PermissionMode -> uiState.selectedPermissionMode
                },
                runtimeModels = uiState.runtimeModels,
                selectedModel = uiState.selectedModel,
                onSelect = { value ->
                    when (target) {
                        RuntimeControlTarget.Model -> viewModel.setSelectedModel(value)
                        RuntimeControlTarget.ReasoningEffort -> viewModel.setSelectedReasoningEffort(value)
                        RuntimeControlTarget.PermissionMode -> viewModel.setSelectedPermissionMode(value)
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

    if (showApprovalSheet && activeApprovalItems.isNotEmpty()) {
        SessionApprovalSheet(
            approvals = activeApprovalItems,
            onDismiss = { showApprovalSheet = false },
            onDecision = { approvalId, decision ->
                val pendingApproval = uiState.pendingApprovals.firstOrNull { it.id == approvalId }
                if (pendingApproval != null) {
                    val mappedDecision = when (decision) {
                        SessionApprovalDecision.AcceptTurn -> "accept"
                        SessionApprovalDecision.AcceptSession -> "acceptForSession"
                        SessionApprovalDecision.Decline -> "decline"
                    }
                    viewModel.decidePendingApproval(
                        serverId = serverId,
                        sessionId = sessionId,
                        approval = pendingApproval,
                        decision = mappedDecision,
                    )
                } else {
                    onApprovalDecision(approvalId, decision)
                }
            },
        )
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
        val perfSnapshot = sessionId?.let { SessionPerformanceStore.peek(it) }
        fun metricLabel(ms: Long?, status: String?): String = when {
            ms != null && !status.isNullOrBlank() -> "${ms}ms · $status"
            ms != null -> "${ms}ms"
            !status.isNullOrBlank() -> status
            else -> "—"
        }
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
                DiagnosticRow(
                    stringResource(R.string.session_detail_diag_permission_selected),
                    runtimeControlLabel(RuntimeControlTarget.PermissionMode, uiState.selectedPermissionMode),
                )
                DiagnosticRow(
                    stringResource(R.string.session_detail_diag_permission_last_sent),
                    uiState.lastRequestedPermissionMode?.let {
                        runtimeControlLabel(RuntimeControlTarget.PermissionMode, it)
                    } ?: "—",
                )
                DiagnosticRow(stringResource(R.string.session_detail_diag_message_count), "${uiState.messages.size}")
                DiagnosticRow(
                    stringResource(R.string.session_detail_diag_output_length),
                    "${uiState.liveRun?.lastOutput?.length ?: 0} chars",
                )
                DiagnosticRow(
                    "Bootstrap load",
                    metricLabel(
                        uiState.lastBootstrapLoadMs ?: perfSnapshot?.bootstrapLoadMs,
                        perfSnapshot?.bootstrapStatus,
                    ),
                )
                DiagnosticRow(
                    "Summary load",
                    metricLabel(
                        uiState.lastSummaryLoadMs ?: perfSnapshot?.summaryLoadMs,
                        null,
                    ),
                )
                DiagnosticRow(
                    "Tail load",
                    metricLabel(
                        uiState.lastTailLoadMs ?: perfSnapshot?.tailLoadMs,
                        null,
                    ),
                )
                DiagnosticRow(
                    "Live refresh",
                    metricLabel(
                        uiState.lastLiveRefreshMs ?: perfSnapshot?.liveRefreshMs,
                        perfSnapshot?.liveStatus,
                    ),
                )
                DiagnosticRow(
                    "Repo refresh",
                    metricLabel(
                        uiState.lastRepoRefreshMs ?: perfSnapshot?.repoRefreshMs,
                        perfSnapshot?.repoStatus,
                    ),
                )
                DiagnosticRow(
                    "Approvals refresh",
                    metricLabel(
                        uiState.lastApprovalsRefreshMs ?: perfSnapshot?.approvalsRefreshMs,
                        perfSnapshot?.approvalsStatus,
                    ),
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
private fun SettingsMetaRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.65f),
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
