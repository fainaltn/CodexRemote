package dev.codexremote.android.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexremote.android.data.model.Artifact
import dev.codexremote.android.data.model.Run
import dev.codexremote.android.data.model.Session
import dev.codexremote.android.data.model.SessionMessage
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.theme.ThemeToggleAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ACTIVE_POLL_INTERVAL_MS = 4_000L
private val activeRunStatuses = setOf("pending", "running")
private val terminalRunStatuses = setOf("completed", "failed", "stopped")
private val timeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private enum class AttachmentTarget { Composer, Edit }

private data class ComposerAttachmentItem(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localOnly: Boolean,
)

private data class HistoryGroup(
    val id: String,
    val messages: List<SessionMessage>,
    val primaryMessages: List<SessionMessage>,
    val foldedMessages: List<SessionMessage>,
    val preview: String,
    val title: String,
    val folded: Boolean,
    val isHistorical: Boolean,
)

private fun formatDate(dateStr: String): String {
    return runCatching {
        Instant.parse(dateStr).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }.getOrElse { dateStr }
}

private fun statusLabel(status: String): String = when (status) {
    "pending" -> "等待中"
    "running" -> "运行中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun cleanLiveOutput(
    output: String?,
    rawPrompt: String? = null,
): String? {
    if (output.isNullOrBlank()) return null
    val filtered = output
        .lineSequence()
        .map { it.trimEnd() }
        .filter { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@filter false
            if (trimmed.startsWith("2026-") && trimmed.contains(" WARN ")) return@filter false
            if (trimmed.startsWith("Reading prompt from stdin")) return@filter false
            if (trimmed.startsWith("OpenAI Codex")) return@filter false
            if (trimmed == "--------") return@filter false
            if (trimmed.startsWith("workdir: ")) return@filter false
            if (trimmed.startsWith("model: ")) return@filter false
            if (trimmed.startsWith("provider: ")) return@filter false
            if (trimmed.startsWith("approval: ")) return@filter false
            if (trimmed.startsWith("sandbox: ")) return@filter false
            if (trimmed.startsWith("reasoning effort: ")) return@filter false
            if (trimmed.startsWith("reasoning summaries: ")) return@filter false
            if (trimmed.startsWith("session id: ")) return@filter false
            if (trimmed == "mcp: figma starting") return@filter false
            if (trimmed == "mcp: playwright starting") return@filter false
            if (trimmed == "mcp: figma ready") return@filter false
            if (trimmed == "mcp: playwright ready") return@filter false
            if (trimmed.startsWith("mcp startup: ")) return@filter false
            if (trimmed == "user") return@filter false
            if (trimmed == "codex") return@filter false
            if (trimmed == "tokens used") return@filter false
            if (trimmed.matches(Regex("^\\d[\\d,]*$"))) return@filter false
            true
        }
        .toList()

    if (filtered.isEmpty()) return null
    val deduped = mutableListOf<String>()
    for (line in filtered) {
        val normalized = line.replace(Regex("\\s+"), " ").trim()
        val previous = deduped.lastOrNull()
        if (previous != null && previous.replace(Regex("\\s+"), " ").trim() == normalized) {
            continue
        }
        deduped += line
    }
    val sanitized = sanitizeLiveOutputPayload(
        text = deduped.joinToString("\n").trim(),
        rawPrompt = rawPrompt,
    )
    return collapseRepeatedOutputBlocks(sanitized).ifBlank { null }
}

private fun sanitizePromptDisplay(prompt: String): String {
    val trimmed = prompt.trim()
    if (trimmed.startsWith("You have access to these uploaded session artifacts")) {
        val marker = "User request:"
        val idx = trimmed.indexOf(marker)
        if (idx != -1) {
            return trimmed.substring(idx + marker.length).trim()
        }
    }
    return trimmed
}

private fun sanitizeLiveOutputPayload(
    text: String,
    rawPrompt: String?,
): String {
    var sanitized = text.trim()
    val rawPromptTrimmed = rawPrompt?.trim().orEmpty()
    val displayPrompt = rawPrompt?.let(::sanitizePromptDisplay)

    if (sanitized.startsWith("You have access to these uploaded session artifacts")) {
        val marker = "User request:"
        val idx = sanitized.indexOf(marker)
        if (idx != -1) {
            sanitized = sanitized.substring(idx + marker.length).trim()
        }
    }

    if (rawPromptTrimmed.isNotBlank()) {
        sanitized = stripLeadingBlock(sanitized, rawPromptTrimmed)
    }

    if (!displayPrompt.isNullOrBlank()) {
        sanitized = stripLeadingBlock(sanitized, "User request: $displayPrompt")
        sanitized = stripLeadingBlock(sanitized, displayPrompt)
    }

    return sanitized
}

private fun stripLeadingBlock(text: String, block: String): String {
    if (block.isBlank()) return text
    val trimmedText = text.trim()
    val trimmedBlock = block.trim()
    if (trimmedText == trimmedBlock) return ""
    if (trimmedText.startsWith("$trimmedBlock\n")) {
        return trimmedText.removePrefix("$trimmedBlock\n").trim()
    }
    if (trimmedText.startsWith("$trimmedBlock\r\n")) {
        return trimmedText.removePrefix("$trimmedBlock\r\n").trim()
    }
    return trimmedText
}

private fun collapseRepeatedOutputBlocks(text: String): String {
    val normalized = text.trim()
    if (normalized.isBlank()) return normalized

    val paragraphs = normalized
        .split(Regex("\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    if (paragraphs.size >= 2) {
        val collapsed = mutableListOf<String>()
        for (paragraph in paragraphs) {
            if (collapsed.lastOrNull() == paragraph) continue
            collapsed += paragraph
        }
        return collapsed.joinToString("\n\n")
    }

    val lines = normalized.lines()
    if (lines.size % 2 == 0) {
        val half = lines.size / 2
        val firstHalf = lines.take(half).joinToString("\n").trim()
        val secondHalf = lines.drop(half).joinToString("\n").trim()
        if (firstHalf.isNotBlank() && firstHalf == secondHalf) {
            return firstHalf
        }
    }

    return normalized
}

private fun latestCanonicalPrompt(messages: List<SessionMessage>): String? =
    messages
        .lastOrNull { it.role == "user" }
        ?.text
        ?.trim()
        ?.ifBlank { null }

private fun latestCanonicalAssistantReply(messages: List<SessionMessage>): String? {
    val lastUserIndex = messages.indexOfLast { it.role == "user" }
    if (lastUserIndex == -1) return null
    return messages
        .drop(lastUserIndex + 1)
        .lastOrNull { it.role == "assistant" && it.kind == "message" }
        ?.text
        ?.trim()
        ?.ifBlank { null }
}

private fun summarizeGroupTitle(text: String?): String {
    val raw = text.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (raw.isBlank()) return "历史对话"
    return if (raw.length > 20) "${raw.take(20)}…" else raw
}

private fun summarizeHistoryTitle(group: List<SessionMessage>): String {
    val firstUser = group.firstOrNull { it.role == "user" }
    val lastAssistant = group.asReversed().firstOrNull { it.role == "assistant" && it.kind == "message" }
    return summarizeGroupTitle(firstUser?.text ?: lastAssistant?.text)
}

private fun buildHistoryGroups(messages: List<SessionMessage>): List<HistoryGroup> {
    val lastUserIndex = messages.indexOfLast { it.role == "user" }
    val groups = mutableListOf<HistoryGroup>()
    var current = mutableListOf<SessionMessage>()
    var currentKind = "round"

    fun flush() {
        if (current.isEmpty()) return
        val firstUser = current.firstOrNull { it.role == "user" }
        val assistantMessages = current.filter { it.role == "assistant" && it.kind == "message" }
        val previewSource = assistantMessages.lastOrNull()?.text
            ?: firstUser?.text
            ?: current.firstOrNull()?.text
            ?: ""
        val firstIndex = messages.indexOfFirst { it.id == current.first().id }
        val isHistorical = firstIndex in 0 until lastUserIndex
        val isLatestVisibleReply = currentKind != "reasoning" && !isHistorical
        var primaryMessages = current.toList()
        var foldedMessages = emptyList<SessionMessage>()

        if (currentKind != "reasoning" && assistantMessages.size > 1) {
            val finalAssistant = assistantMessages.last()
            primaryMessages = current.filter { message ->
                message.role != "assistant" || message.kind != "message" || message.id == finalAssistant.id
            }
            foldedMessages = current.filter { message ->
                message.role == "assistant" && message.kind == "message" && message.id != finalAssistant.id
            }
        }

        groups += HistoryGroup(
            id = current.first().id,
            messages = current.toList(),
            primaryMessages = primaryMessages,
            foldedMessages = foldedMessages,
            title = when {
                currentKind == "reasoning" -> "Codex 思考"
                isLatestVisibleReply -> "当前对话"
                firstUser != null -> summarizeHistoryTitle(current)
                else -> "系统上下文"
            },
            folded = currentKind == "reasoning" || isHistorical,
            isHistorical = isHistorical,
            preview = if (previewSource.length > 72) "${previewSource.take(72)}…" else previewSource,
        )
        current = mutableListOf()
    }

    for (message in messages) {
        val nextKind = if (message.kind == "reasoning") "reasoning" else "round"
        if (current.isNotEmpty() && (message.role == "user" || nextKind != currentKind)) {
            flush()
        }
        currentKind = nextKind
        current += message
    }
    flush()

    return groups
}

private fun detailProjectLabel(path: String?): String = when {
    path.isNullOrBlank() -> "会话详情"
    else -> java.io.File(path).name.ifBlank { path }
}

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
    val historyGroups = remember(uiState.messages) { buildHistoryGroups(uiState.messages) }
    val latestPrompt = remember(uiState.messages) { latestCanonicalPrompt(uiState.messages) }
    val latestReply = remember(uiState.messages) { latestCanonicalAssistantReply(uiState.messages) }
    val stableDetailKey = sessionId ?: "draft:${initialCwd.orEmpty()}"
    val isDraftSession = sessionId == null
    var expandedGroups by remember(stableDetailKey) { mutableStateOf(setOf<String>()) }
    var previousLiveStatus by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var refreshedTerminalRunId by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var editingMessageId by remember(stableDetailKey) { mutableStateOf<String?>(null) }
    var editingText by remember(stableDetailKey) { mutableStateOf("") }
    var editingArtifacts by remember(stableDetailKey) { mutableStateOf<List<Artifact>>(emptyList()) }
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
                    viewModel.showError("请先创建会话后再编辑并添加附件。")
                } else {
                    viewModel.uploadAttachment(serverId, sessionId, uri) { artifact ->
                        if (attachmentTarget == AttachmentTarget.Edit) {
                            editingArtifacts = editingArtifacts + artifact
                        }
                    }
                }
            }
        }
    }
    val isRunning = uiState.liveRun?.status in activeRunStatuses
    val showCurrentRunCard = remember(uiState.liveRun, latestPrompt, latestReply, isDraftSession) {
        val liveRun = uiState.liveRun ?: return@remember isDraftSession
        if (liveRun.status in activeRunStatuses) return@remember true
        if (!liveRun.error.isNullOrBlank()) return@remember true
        latestPrompt == null || latestReply == null
    }

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

    LaunchedEffect(serverId, sessionId, uiState.liveRun?.status, uiState.liveRun?.id) {
        if (sessionId.isNullOrBlank()) return@LaunchedEffect
        val status = uiState.liveRun?.status
        if (status in activeRunStatuses) {
            refreshedTerminalRunId = null
            while (isActive) {
                delay(ACTIVE_POLL_INTERVAL_MS)
                viewModel.refreshLiveRun(serverId, sessionId)
            }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title ?: detailProjectLabel(initialCwd)) },
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
        }
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                if (session != null) {
                                    SessionHeaderCard(
                                        session = session,
                                        liveRun = uiState.liveRun,
                                    )
                                } else {
                                    DraftSessionHeaderCard(cwd = initialCwd)
                                }
                            }

                            if (historyGroups.isNotEmpty()) {
                                item {
                                    SectionHeader(
                                        title = "历史上下文",
                                        subtitle = "${historyGroups.size} 轮 / ${uiState.messages.size} 条",
                                    )
                                }
                                items(historyGroups, key = { it.id }) { group ->
                                HistoryGroupCard(
                                        group = group,
                                        expanded = !group.folded || expandedGroups.contains(group.id),
                                        processExpanded = expandedGroups.contains("${group.id}-process"),
                                        editingMessageId = editingMessageId,
                                        editingText = editingText,
                                        canEditMessages = !isRunning && !uiState.sending && !uiState.stopping,
                                        onToggleGroup = {
                                            expandedGroups =
                                                if (expandedGroups.contains(group.id)) {
                                                    expandedGroups - group.id
                                                } else {
                                                    expandedGroups + group.id
                                                }
                                        },
                                        onToggleProcess = {
                                            val processKey = "${group.id}-process"
                                            expandedGroups =
                                                if (expandedGroups.contains(processKey)) {
                                                    expandedGroups - processKey
                                                } else {
                                                    expandedGroups + processKey
                                                }
                                        },
                                        onStartEdit = { message ->
                                            editingMessageId = message.id
                                            editingText = message.text
                                            editingArtifacts = emptyList()
                                        },
                                        onEditChange = { editingText = it },
                                        onCancelEdit = {
                                            editingArtifacts.forEach { artifact ->
                                                viewModel.removeArtifact(artifact.id)
                                            }
                                            editingMessageId = null
                                            editingText = ""
                                            editingArtifacts = emptyList()
                                        },
                                        onSubmitEdit = { prompt ->
                                            val activeSessionId = sessionId
                                            if (activeSessionId.isNullOrBlank()) {
                                                viewModel.showError("请先创建会话后再编辑并重发消息。")
                                            } else {
                                                val artifactsToSend = editingArtifacts
                                                viewModel.sendEditedPrompt(
                                                    serverId,
                                                    activeSessionId,
                                                    prompt,
                                                    artifactsToSend,
                                                )
                                                artifactsToSend.forEach { artifact ->
                                                    viewModel.removeArtifact(artifact.id)
                                                }
                                                editingMessageId = null
                                                editingText = ""
                                                editingArtifacts = emptyList()
                                            }
                                        },
                                        editingArtifacts = editingArtifacts,
                                        onAttachImage = {
                                            attachmentTarget = AttachmentTarget.Edit
                                            attachmentPicker.launch(arrayOf("image/*", "*/*"))
                                        },
                                    )
                                }
                            } else {
                                item {
                                    EmptyTimelineCard()
                                }
                            }

                            if (showCurrentRunCard) {
                                item {
                                    CurrentRunCard(
                                        liveRun = uiState.liveRun,
                                        messages = uiState.messages,
                                        sending = uiState.sending,
                                        draft = isDraftSession,
                                        onRetry = { prompt ->
                                            if (!sessionId.isNullOrBlank()) {
                                                viewModel.resendLatestPrompt(serverId, sessionId, prompt)
                                            }
                                        },
                                        onReusePrompt = { prompt ->
                                            viewModel.retryLatestPrompt(prompt)
                                        },
                                    )
                                }
                            }

                            if (uiState.error != null) {
                                item {
                                    ErrorBanner(
                                        message = uiState.error!!,
                                        onDismiss = { viewModel.clearError() },
                                    )
                                }
                            }
                        }
                    }

                    if (composerAttachments.isNotEmpty() && editingMessageId == null) {
                        AttachmentQueueCard(
                            attachments = composerAttachments,
                            onRemove = { attachment ->
                                if (attachment.localOnly) {
                                    viewModel.removeLocalAttachment(attachment.id)
                                } else {
                                    viewModel.removeArtifact(attachment.id)
                                }
                            },
                        )
                    }

                    ComposerBar(
                        prompt = uiState.prompt,
                        uploading = uiState.uploading,
                        sending = uiState.sending,
                        stopping = uiState.stopping,
                        isRunning = isRunning,
                        artifactCount = composerAttachments.size,
                        onPromptChange = { viewModel.setPrompt(it) },
                        onUploadClick = {
                            attachmentTarget = AttachmentTarget.Composer
                            attachmentPicker.launch(arrayOf("*/*"))
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

@Composable
private fun DraftSessionHeaderCard(
    cwd: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = detailProjectLabel(cwd),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = cwd ?: "未选择项目目录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "这是一条空白项目会话。输入首条消息后，才会创建真实会话并开始运行。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionHeaderCard(
    session: Session,
    liveRun: Run?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = session.cwd ?: "Codex 会话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(
                    label = when (liveRun?.status) {
                        null -> "空闲"
                        else -> statusLabel(liveRun.status)
                    },
                    tone = liveRun?.status,
                )
                Text(
                    text = "更新于 ${formatDate(session.updatedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryGroupCard(
    group: HistoryGroup,
    expanded: Boolean,
    processExpanded: Boolean,
    editingMessageId: String?,
    editingText: String,
    canEditMessages: Boolean,
    onToggleGroup: () -> Unit,
    onToggleProcess: () -> Unit,
    onStartEdit: (SessionMessage) -> Unit,
    onEditChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (String) -> Unit,
    editingArtifacts: List<Artifact>,
    onAttachImage: () -> Unit,
) {
    if (!expanded) {
        ButtonLikeCard(
            group = group,
            onClick = onToggleGroup,
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isHistorical) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (group.folded) {
                TextButtonRow(
                    title = "收起这轮历史",
                    onClick = onToggleGroup,
                )
            }

            group.primaryMessages.forEachIndexed { index, message ->
                val shouldInsertProcessCard =
                    group.foldedMessages.isNotEmpty() &&
                        index == group.primaryMessages.lastIndex &&
                        message.role == "assistant"

                if (shouldInsertProcessCard) {
                    if (!processExpanded) {
                        ButtonLikeCard(
                            title = "Codex 过程",
                            preview = summarizeGroupTitle(group.foldedMessages.firstOrNull()?.text),
                            count = group.foldedMessages.size,
                            isHistorical = group.isHistorical,
                            onClick = onToggleProcess,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButtonRow(
                                title = "收起过程",
                                onClick = onToggleProcess,
                            )
                            group.foldedMessages.forEach { foldedMessage ->
                                HistoryMessageCard(
                                    message = foldedMessage,
                                    current = false,
                                    editable = false,
                                    isEditing = false,
                                    editingText = "",
                                    editEnabled = false,
                                    onStartEdit = {},
                                    onEditChange = {},
                                    onCancelEdit = {},
                                    onSubmitEdit = {},
                                    editingArtifacts = emptyList(),
                                    onAttachImage = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                HistoryMessageCard(
                    message = message,
                    current = !group.isHistorical,
                    editable = group.isHistorical && message.role == "user" && message.kind == "message",
                    isEditing = editingMessageId == message.id,
                    editingText = if (editingMessageId == message.id) editingText else message.text,
                    editEnabled = canEditMessages,
                    onStartEdit = { onStartEdit(message) },
                    onEditChange = onEditChange,
                    onCancelEdit = onCancelEdit,
                    onSubmitEdit = onSubmitEdit,
                    editingArtifacts = if (editingMessageId == message.id) editingArtifacts else emptyList(),
                    onAttachImage = onAttachImage,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ButtonLikeCard(
    group: HistoryGroup? = null,
    title: String? = null,
    preview: String? = null,
    count: Int? = null,
    isHistorical: Boolean = false,
    onClick: () -> Unit,
) {
    val resolvedTitle = title ?: group?.title.orEmpty()
    val resolvedPreview = preview ?: group?.preview.orEmpty()
    val resolvedCount = count ?: group?.messages?.size ?: 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isHistorical || group?.isHistorical == true) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = resolvedTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$resolvedCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = resolvedPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextButtonRow(
    title: String,
    onClick: () -> Unit,
) {
    Surface(color = Color.Transparent) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HistoryMessageCard(
    message: SessionMessage,
    current: Boolean,
    editable: Boolean,
    isEditing: Boolean,
    editingText: String,
    editEnabled: Boolean,
    onStartEdit: () -> Unit,
    onEditChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (String) -> Unit,
    editingArtifacts: List<Artifact>,
    onAttachImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isProcess = message.kind == "reasoning"
    val containerColor = when {
        isProcess -> MaterialTheme.colorScheme.secondaryContainer
        current && message.role == "assistant" -> MaterialTheme.colorScheme.primaryContainer
        current && message.role == "user" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val label = when {
        isProcess -> "Codex 过程"
        message.role == "user" -> "你"
        message.role == "assistant" -> "Codex"
        else -> "系统"
    }
    val textColor = if (isProcess) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
                Text(
                    text = formatDate(message.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isEditing) {
                InlineHistoryEditor(
                    value = editingText,
                    enabled = editEnabled,
                    onValueChange = onEditChange,
                    onCancel = onCancelEdit,
                    onSubmit = { onSubmitEdit(editingText) },
                    artifacts = editingArtifacts,
                    onAttachImage = onAttachImage,
                )
            } else {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (isProcess) FontFamily.Monospace else FontFamily.Default,
                    ),
                    color = textColor,
                )
            }

            if (!isEditing && editable) {
                TextButton(
                    onClick = onStartEdit,
                    enabled = editEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
            }
        }
    }
}

@Composable
private fun InlineHistoryEditor(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    artifacts: List<Artifact>,
    onAttachImage: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                minLines = 2,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = "编辑这条历史消息...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onAttachImage, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("插入图片")
                }
                TextButton(onClick = onCancel, enabled = enabled) {
                    Text("取消")
                }
                Button(
                    onClick = onSubmit,
                    enabled = enabled && value.trim().isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("重新发送")
                }
            }

            if (artifacts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    artifacts.forEach { artifact ->
                        Text(
                            text = "已附带 ${artifact.originalName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentRunCard(
    liveRun: Run?,
    messages: List<SessionMessage>,
    sending: Boolean,
    draft: Boolean,
    onRetry: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
) {
    val active = liveRun?.status in activeRunStatuses
    val title = when {
        liveRun == null -> "当前没有运行中的任务"
        active -> "当前运行"
        else -> "最近一次运行"
    }
    val containerColor = when {
        liveRun == null -> MaterialTheme.colorScheme.surfaceVariant
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (liveRun == null) {
                Text(
                    text = if (draft) {
                        "输入首条消息后会创建会话，并立即开始第一轮处理。"
                    } else {
                        "发送一条提示词来继续处理这个 Codex 会话"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(
                    label = statusLabel(liveRun.status),
                    tone = liveRun.status,
                )
                liveRun.model?.let {
                    StatusBadge(
                        label = it,
                        tone = "model",
                    )
                }
                liveRun.reasoningEffort?.let {
                    StatusBadge(
                        label = it,
                        tone = "effort",
                    )
                }
            }

            val prompt = when {
                active -> sanitizePromptDisplay(liveRun.prompt)
                else -> latestCanonicalPrompt(messages) ?: sanitizePromptDisplay(liveRun.prompt)
            }
            if (prompt.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "提示词",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Text(
                            text = prompt,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (active) "流式输出" else "运行结果",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    val output = when {
                        active -> cleanLiveOutput(
                            output = liveRun.lastOutput,
                            rawPrompt = liveRun.prompt,
                        )
                        else -> latestCanonicalAssistantReply(messages)
                            ?: cleanLiveOutput(
                                output = liveRun.lastOutput,
                                rawPrompt = liveRun.prompt,
                            )
                    }
                    Text(
                        text = output ?: if (active) "Codex 正在思考…" else "这次运行没有可显示的文本输出",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .heightIn(min = 72.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            liveRun.error?.let { error ->
                ErrorBanner(
                    message = error,
                    onDismiss = {},
                )
            }

            if (!active && liveRun.prompt.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onReusePrompt(liveRun.prompt) },
                        enabled = !sending,
                    ) {
                        Text("回填提示词")
                    }
                    Button(
                        onClick = { onRetry(liveRun.prompt) },
                        enabled = !sending,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("重试本轮")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    tone: String?,
) {
    val containerColor = when (tone) {
        "running" -> MaterialTheme.colorScheme.primaryContainer
        "pending" -> MaterialTheme.colorScheme.tertiaryContainer
        "completed" -> MaterialTheme.colorScheme.surfaceVariant
        "failed" -> MaterialTheme.colorScheme.errorContainer
        "stopped" -> MaterialTheme.colorScheme.surfaceVariant
        "model" -> MaterialTheme.colorScheme.surfaceVariant
        "effort" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (tone) {
        "failed" -> MaterialTheme.colorScheme.onErrorContainer
        "running", "pending" -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun EmptyTimelineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "还没有可展示的历史消息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "这里会显示用户消息、Codex 回复和折叠的过程消息。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentQueueCard(
    attachments: List<ComposerAttachmentItem>,
    onRemove: (ComposerAttachmentItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "待发送附件",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${attachments.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            attachments.forEach { attachment ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.originalName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append("${attachment.mimeType} · ${formatBytes(attachment.sizeBytes)}")
                                    if (attachment.localOnly) append(" · 本地暂存")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemove(attachment) }) {
                            Icon(Icons.Filled.Close, contentDescription = "移除附件")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    prompt: String,
    uploading: Boolean,
    sending: Boolean,
    stopping: Boolean,
    isRunning: Boolean,
    artifactCount: Int,
    onPromptChange: (String) -> Unit,
    onUploadClick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendContentDescription: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (artifactCount > 0) {
            Text(
                text = "已选择 $artifactCount 个附件，发送时会自动附带",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    IconButton(
                        onClick = onUploadClick,
                        enabled = !uploading && !sending,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "上传附件")
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    BasicTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        enabled = !sending && !stopping,
                        minLines = 1,
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        decorationBox = { innerTextField ->
                            if (prompt.isBlank()) {
                                Text(
                                    text = if (isRunning) "任务进行中..." else "继续处理这个会话...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                if (isRunning) {
                    Button(
                        onClick = onStop,
                        enabled = !stopping,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        if (stopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("停止")
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        IconButton(
                            onClick = onSend,
                            enabled = prompt.trim().isNotEmpty() && !sending && !uploading,
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.ArrowUpward,
                                    contentDescription = sendContentDescription,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uploading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
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
