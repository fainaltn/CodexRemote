package app.findeck.mobile.ui.sessions

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.findeck.mobile.R
import app.findeck.mobile.data.model.Artifact
import app.findeck.mobile.data.model.FileEntry
import app.findeck.mobile.data.model.ListFilesResponse
import app.findeck.mobile.data.model.PendingApproval
import app.findeck.mobile.data.model.PendingApprovalDecisionRequest
import app.findeck.mobile.data.model.PendingPermissionProfile
import app.findeck.mobile.data.model.RepoActionRequest
import app.findeck.mobile.data.model.RepoActionResponse
import app.findeck.mobile.data.model.RepoLogEntry
import app.findeck.mobile.data.model.Run
import app.findeck.mobile.data.model.RuntimeModelDescriptor
import app.findeck.mobile.data.model.RepoStatus
import app.findeck.mobile.data.model.Session
import app.findeck.mobile.data.model.SessionDetailResponse
import app.findeck.mobile.data.model.SessionMessage
import app.findeck.mobile.data.model.SkillEntry
import app.findeck.mobile.data.network.ApiClient
import app.findeck.mobile.data.network.LiveRunStreamEvent
import app.findeck.mobile.data.repository.ServerRepository
import app.findeck.mobile.notifications.RunCompletedNotificationHelper
import app.findeck.mobile.notifications.RunNotificationTier
import app.findeck.mobile.notifications.RunCompletedNotificationPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SESSION_HOST_ID = "local"
internal const val FOREGROUND_MESSAGE_TAIL_LIMIT = 40
private const val SESSION_BOOTSTRAP_CACHE_TTL_MS = 15_000L
private const val PERF_TAG = "FindeckPerf"

internal data class SessionDetailBootstrapSnapshot(
    val session: Session,
    val messages: List<SessionMessage> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val nextBeforeOrderIndex: Int? = null,
    val bootstrapLoadMs: Long? = null,
    val summaryLoadMs: Long? = null,
    val tailLoadMs: Long? = null,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

internal data class SessionPerformanceSnapshot(
    val bootstrapLoadMs: Long? = null,
    val bootstrapStatus: String? = null,
    val summaryLoadMs: Long? = null,
    val tailLoadMs: Long? = null,
    val liveRefreshMs: Long? = null,
    val liveStatus: String? = null,
    val repoRefreshMs: Long? = null,
    val repoStatus: String? = null,
    val approvalsRefreshMs: Long? = null,
    val approvalsStatus: String? = null,
)

internal object SessionPerformanceStore {
    private val snapshots = linkedMapOf<String, SessionPerformanceSnapshot>()

    fun update(
        sessionId: String,
        block: (SessionPerformanceSnapshot) -> SessionPerformanceSnapshot,
    ) {
        val current = snapshots[sessionId] ?: SessionPerformanceSnapshot()
        snapshots[sessionId] = block(current)
    }

    fun peek(sessionId: String): SessionPerformanceSnapshot? = snapshots[sessionId]
}

internal object SessionDetailBootstrapStore {
    private val snapshots = linkedMapOf<String, SessionDetailBootstrapSnapshot>()

    fun seed(session: Session) {
        val existing = snapshots[session.id]
        snapshots[session.id] = if (existing != null) {
            existing.copy(session = session, fetchedAtMillis = System.currentTimeMillis())
        } else {
            SessionDetailBootstrapSnapshot(session = session)
        }
    }

    fun store(snapshot: SessionDetailBootstrapSnapshot) {
        snapshots[snapshot.session.id] = snapshot.copy(fetchedAtMillis = System.currentTimeMillis())
    }

    fun peek(sessionId: String): SessionDetailBootstrapSnapshot? {
        val snapshot = snapshots[sessionId] ?: return null
        if (System.currentTimeMillis() - snapshot.fetchedAtMillis > SESSION_BOOTSTRAP_CACHE_TTL_MS) {
            snapshots.remove(sessionId)
            return null
        }
        return snapshot
    }
}

data class DownloadedSessionFile(
    val file: File,
    val fileName: String,
    val mimeType: String,
)

data class RecordedVoiceInput(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val durationMs: Long,
)

data class SessionDetailUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val archiving: Boolean = false,
    val archived: Boolean = false,
    val transitionLoading: Boolean = false,
    val session: Session? = null,
    val repoStatus: RepoStatus? = null,
    val messages: List<SessionMessage> = emptyList(),
    val liveRun: Run? = null,
    val prompt: String = "",
    val lastSubmittedPrompt: String? = null,
    val lastSubmittedPromptWithAttachments: String? = null,
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    val selectedPermissionMode: String = "on-request",
    val lastRequestedPermissionMode: String? = null,
    val runtimeControlsInitialized: Boolean = false,
    val runtimeModels: List<RuntimeModelDescriptor> = emptyList(),
    val pendingArtifacts: List<Artifact> = emptyList(),
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val resolvingApprovalId: String? = null,
    val hasMoreHistory: Boolean = false,
    val nextBeforeOrderIndex: Int? = null,
    val loadingOlderHistory: Boolean = false,
    val pendingLocalAttachments: List<PendingLocalAttachment> = emptyList(),
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val uploading: Boolean = false,
    val sending: Boolean = false,
    val stopping: Boolean = false,
    val repoActionBusy: Boolean = false,
    val repoActionSummary: String? = null,
    val repoLogEntries: List<RepoLogEntry> = emptyList(),
    val repoLogLoading: Boolean = false,
    val lastBootstrapLoadMs: Long? = null,
    val lastSummaryLoadMs: Long? = null,
    val lastTailLoadMs: Long? = null,
    val lastLiveRefreshMs: Long? = null,
    val lastRepoRefreshMs: Long? = null,
    val lastApprovalsRefreshMs: Long? = null,
    val error: String? = null,
    val createdSessionId: String? = null,
    val currentTurnPromptOverride: String? = null,
    val retainedLiveOutput: String? = null,
    val retainedLiveRunId: String? = null,
    val liveStreamConnected: Boolean = false,
    val liveStreamStatus: String? = null,
    val appInBackground: Boolean = false,
    val recoveryNotice: String? = null,
)

data class PendingLocalAttachment(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
    val durationMs: Long? = null,
)

private data class RuntimeControls(
    val model: String? = null,
    val reasoningEffort: String? = null,
    val permissionMode: String = "on-request",
)

data class QueuedPrompt(
    val id: String,
    val rawPrompt: String,
    val artifacts: List<Artifact> = emptyList(),
    val model: String? = null,
    val reasoningEffort: String? = null,
    val permissionMode: String = "on-request",
)

private data class ServerHandle(
    val client: ApiClient,
    val token: String,
)

private data class SessionRef(
    val serverId: String,
    val sessionId: String,
)

private fun buildAttachmentPrompt(
    prompt: String,
    artifacts: List<Artifact>,
    audioDurationsMs: Map<String, Long> = emptyMap(),
): String {
    if (artifacts.isEmpty()) return prompt.trim()
    val hasAudioAttachment = artifacts.any { it.mimeType.startsWith("audio/", ignoreCase = true) }
    return buildString {
        appendLine("You have access to these uploaded session artifacts on the local filesystem.")
        appendLine("Use the exact absolute file paths below directly before answering.")
        appendLine("Do not search the workspace for alternate copies unless a listed path is missing.")
        if (hasAudioAttachment) {
            appendLine("If an attachment is audio, transcribe it locally before answering.")
            appendLine("Use `/opt/homebrew/bin/whisper` directly and use `/opt/homebrew/bin/ffmpeg` only if format inspection or conversion is needed.")
            appendLine("Do not probe tool availability first with shell fallback chains like `command -v ... || ...`.")
            if (prompt.trim() == hiddenVoiceNoteInstruction) {
                appendLine("Treat the voice note as the user's actual instruction.")
                appendLine("Quietly transcribe it first, then execute or answer the spoken request directly.")
                appendLine("Only include the transcript when the speech is ambiguous or when the user explicitly asks for it.")
            }
        }
        appendLine()
        artifacts.forEachIndexed { index, artifact ->
            appendLine(
                "[Attachment ${index + 1}] id=${artifact.id} ${artifact.originalName} (${artifact.mimeType})",
            )
            audioDurationsMs[artifact.id]?.let { durationMs ->
                appendLine("Duration ms: $durationMs")
            }
            appendLine("Absolute path: ${artifact.storedPath}")
        }
        appendLine()
        append("User request: ${prompt.trim()}")
    }.trim()
}

private fun sanitizePromptDisplay(prompt: String?): String? {
    val trimmed = prompt?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    return app.findeck.mobile.ui.sessions
        .sanitizePromptDisplay(trimmed)
        .ifBlank { null }
}

private fun normalizeRuntimeControl(value: String?): String? =
    value?.trim()?.takeIf { it.isNotBlank() }

private fun normalizePermissionMode(value: String?): String =
    when (value?.trim()) {
        null, "", "default", "on-request", "onRequest" -> "on-request"
        "full", "full-access", "fullAccess" -> "full-access"
        else -> "on-request"
    }

private fun buildComposedPrompt(
    prompt: String,
    artifacts: List<Artifact> = emptyList(),
    audioDurationsMs: Map<String, Long> = emptyMap(),
): String {
    val skillAwarePrompt = augmentPromptWithSkillMentions(prompt)
    return buildAttachmentPrompt(skillAwarePrompt, artifacts, audioDurationsMs)
}

private fun mergeApprovalEvent(
    current: List<PendingApproval>,
    update: PendingApproval,
): List<PendingApproval> {
    val remaining = current.filterNot { it.id == update.id }
    return if (update.status == "pending") {
        (remaining + update).sortedBy { it.createdAt }
    } else {
        remaining
    }
}

private fun isRecoverableConnectivityError(error: Throwable): Boolean {
    val resolved = ApiClient.describeNetworkFailure(error)
    val message = error.message.orEmpty()
    return resolved.contains("超时") ||
        resolved.contains("timeout", ignoreCase = true) ||
        resolved.contains("network", ignoreCase = true) ||
        resolved.contains("连接", ignoreCase = true) ||
        message.contains("timeout", ignoreCase = true) ||
        message.contains("socket", ignoreCase = true) ||
        message.contains("network", ignoreCase = true) ||
        message.contains("Connection reset", ignoreCase = true) ||
        message.contains("broken pipe", ignoreCase = true)
}

private fun hasVisibleSessionContent(state: SessionDetailUiState): Boolean =
    state.session != null ||
        state.messages.isNotEmpty() ||
        !state.liveRun?.lastOutput.isNullOrBlank() ||
        !state.retainedLiveOutput.isNullOrBlank() ||
        !state.currentTurnPromptOverride.isNullOrBlank()

private fun mergeSessionMessages(
    current: List<SessionMessage>,
    incoming: List<SessionMessage>,
): List<SessionMessage> {
    if (current.isEmpty()) return incoming
    if (incoming.isEmpty()) return current

    val mergedById = linkedMapOf<String, SessionMessage>()
    current.forEach { message ->
        mergedById[message.id] = message
    }
    incoming.forEach { message ->
        mergedById[message.id] = message
    }

    return mergedById.values.sortedWith(
        compareBy<SessionMessage>({ it.orderIndex }, { it.createdAt }, { it.id }),
    )
}

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ServerRepository(application)

    private val _uiState = MutableStateFlow(SessionDetailUiState(loading = true))
    val uiState = _uiState.asStateFlow()

    // Derived flows — avoid redundant recomposition in the UI layer
    internal val historyRoundsFlow: Flow<List<HistoryRound>> = uiState
        .map { it.messages }.distinctUntilChanged()
        .map { buildHistoryRounds(it) }

    internal val latestUserPromptFlow: Flow<String?> = uiState
        .map { it.messages }.distinctUntilChanged()
        .map { latestCanonicalPrompt(it) }

    internal val latestAssistantReplyFlow: Flow<String?> = uiState
        .map { it.messages }.distinctUntilChanged()
        .map { latestCanonicalAssistantReply(it) }

    internal val cleanedOutputFlow: Flow<String?> = uiState
        .map { it.liveRun?.lastOutput to it.liveRun?.prompt }.distinctUntilChanged()
        .map { (output, prompt) -> cleanLiveOutput(output, prompt) }
    private var liveStreamJob: Job? = null
    private var backgroundMonitorJob: Job? = null
    private var foregroundMessageSyncJob: Job? = null
    private var queuedDispatchJob: Job? = null
    private var liveStreamKey: Pair<String, String>? = null
    private var liveStreamLastEventId: Long? = null
    private var activeSessionRef: SessionRef? = null
    private var lastNotifiedBackgroundEventKey: String? = null
    private var backgroundAttentionContextKey: String? = null

    private enum class BackgroundNotificationTier(val statusValue: String) {
        COMPLETED("completed"),
        FAILED("failed"),
        NEEDS_ATTENTION("needs-attention"),
        RECOVERED("recovered"),
    }

    private fun string(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    private fun isTransientTimeout(error: Throwable): Boolean {
        val message = ApiClient.describeNetworkFailure(error)
        return message.contains("超时") ||
            (error.message?.contains("request_timeout", ignoreCase = true) == true) ||
            (error.message?.contains("Request timeout has expired", ignoreCase = true) == true)
    }

    private suspend fun resolveServer(serverId: String): ServerHandle {
        val servers = repo.servers.first()
        val server = servers.find { it.id == serverId }
            ?: throw IllegalStateException(string(R.string.session_detail_error_missing_server))
        val token = server.token ?: throw IllegalStateException(string(R.string.session_detail_error_not_logged_in))
        return ServerHandle(ApiClient(server.baseUrl), token)
    }

    private suspend inline fun <T> withServerClient(
        serverId: String,
        crossinline block: suspend (ServerHandle) -> T,
    ): T {
        val handle = resolveServer(serverId)
        return try {
            block(handle)
        } finally {
            handle.client.close()
        }
    }

    private suspend fun applyStoredRuntimeDefaultsIfNeeded(serverId: String) {
        val snapshot = _uiState.value
        if (snapshot.runtimeControlsInitialized) return
        val defaultModel = repo.getRuntimeDefaultModel(serverId)
        val defaultReasoning = repo.getRuntimeDefaultReasoningEffort(serverId)
        val defaultPermissionMode = repo.getRuntimeDefaultPermissionMode(serverId)
        if (defaultModel.isNullOrBlank() && defaultReasoning.isNullOrBlank() && defaultPermissionMode.isNullOrBlank()) return
        _uiState.update { state ->
            if (state.runtimeControlsInitialized) {
                state
            } else {
                state.copy(
                    selectedModel = normalizeRuntimeControl(defaultModel),
                    selectedReasoningEffort = normalizeRuntimeControl(defaultReasoning),
                    selectedPermissionMode = normalizePermissionMode(defaultPermissionMode),
                    runtimeControlsInitialized = true,
                )
            }
        }
    }

    private suspend fun fetchRuntimeCatalog(serverId: String) {
        runCatching {
            withServerClient(serverId) { handle ->
                handle.client.getRuntimeCatalog(handle.token, SESSION_HOST_ID)
            }
        }.onSuccess { catalog ->
            _uiState.update { state ->
                state.copy(runtimeModels = catalog.models)
            }
        }
    }

    fun refreshRuntimeCatalog(serverId: String) {
        viewModelScope.launch {
            fetchRuntimeCatalog(serverId)
        }
    }

    private suspend fun refreshPendingApprovals(serverId: String, sessionId: String) {
        runCatching {
            withServerClient(serverId) { handle ->
                handle.client.listPendingApprovals(
                    token = handle.token,
                    hostId = SESSION_HOST_ID,
                    sessionId = sessionId,
                ).approvals
            }
        }.onSuccess { approvals ->
            _uiState.update { state ->
                state.copy(
                    pendingApprovals = approvals.sortedBy { it.createdAt },
                    resolvingApprovalId = state.resolvingApprovalId?.takeIf { id ->
                        approvals.any { it.id == id }
                    },
                )
            }
        }
    }

    private fun launchDeferredSessionRefreshes(serverId: String, sessionId: String) {
        startForegroundMessageSyncIfNeeded(serverId, sessionId)
        viewModelScope.launch {
            val hydrationStartedAt = SystemClock.elapsedRealtime()
            runCatching {
                withServerClient(serverId) { handle ->
                    handle.client.getSessionHydration(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                    )
                }
            }.also {
                val hydrationMs = SystemClock.elapsedRealtime() - hydrationStartedAt
                val status = if (it.isSuccess) "ok" else "error"
                _uiState.update { state ->
                    state.copy(
                        lastLiveRefreshMs = hydrationMs,
                        lastRepoRefreshMs = hydrationMs,
                        lastApprovalsRefreshMs = hydrationMs,
                    )
                }
                SessionPerformanceStore.update(sessionId) {
                    it.copy(
                        liveRefreshMs = hydrationMs,
                        liveStatus = status,
                        repoRefreshMs = hydrationMs,
                        repoStatus = status,
                        approvalsRefreshMs = hydrationMs,
                        approvalsStatus = status,
                    )
                }
            }.onSuccess { payload ->
                _uiState.update { state ->
                    applyConversationContinuity(
                        state = state.copy(
                            liveRun = payload.run,
                            repoStatus = payload.repoStatus,
                            pendingApprovals = payload.approvals.sortedBy { approval -> approval.createdAt },
                        ),
                        liveRun = payload.run,
                    )
                }
                seedRuntimeControlsFromRun(payload.run)
                maybeNotifyRunState(serverId, sessionId, payload.run)
                maybeNotifyRecovered(serverId, sessionId, payload.run)
                startBackgroundMonitorIfNeeded()
                Log.d(
                    PERF_TAG,
                    "hydration session=$sessionId ms=${_uiState.value.lastLiveRefreshMs ?: 0L} hasRun=${payload.run != null} approvals=${payload.approvals.size} hasRepo=${payload.repoStatus != null}",
                )
            }.onFailure {
                Log.d(
                    PERF_TAG,
                    "hydration session=$sessionId ms=${_uiState.value.lastLiveRefreshMs ?: 0L} success=false",
                )
            }
        }
    }

    private suspend fun fetchBootstrapSnapshot(
        serverId: String,
        sessionId: String,
        loadStartedAt: Long,
    ): SessionDetailBootstrapSnapshot = withServerClient(serverId) { handle ->
        coroutineScope {
            val summaryDeferred = async(Dispatchers.IO) {
                handle.client.getSessionSummary(handle.token, SESSION_HOST_ID, sessionId)
            }
            val tailDeferred = async(Dispatchers.IO) {
                handle.client.getSessionMessages(
                    token = handle.token,
                    hostId = SESSION_HOST_ID,
                    sessionId = sessionId,
                    limit = FOREGROUND_MESSAGE_TAIL_LIMIT,
                )
            }

            val summary = summaryDeferred.await()
            val summaryLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
            val tailPayload = tailDeferred.await()
            val tailLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
            val bootstrapLoadMs = tailLoadMs

            SessionDetailBootstrapSnapshot(
                session = summary.session,
                messages = tailPayload.messages,
                hasMoreHistory = tailPayload.hasMore,
                nextBeforeOrderIndex = tailPayload.nextBeforeOrderIndex,
                bootstrapLoadMs = bootstrapLoadMs,
                summaryLoadMs = summaryLoadMs,
                tailLoadMs = tailLoadMs,
            )
        }
    }

    fun load(serverId: String, sessionId: String) {
        rememberActiveSession(serverId, sessionId)
        viewModelScope.launch {
            val cachedBootstrap = SessionDetailBootstrapStore.peek(sessionId)
            val startedFromBootstrap = cachedBootstrap != null
            val loadStartedAt = SystemClock.elapsedRealtime()
            _uiState.update {
                it.copy(
                    loading = !startedFromBootstrap,
                    transitionLoading = false,
                    error = null,
                    recoveryNotice = null,
                    session = cachedBootstrap?.session ?: it.session,
                    messages = cachedBootstrap?.messages ?: it.messages,
                    hasMoreHistory = cachedBootstrap?.hasMoreHistory ?: it.hasMoreHistory,
                    nextBeforeOrderIndex = cachedBootstrap?.nextBeforeOrderIndex ?: it.nextBeforeOrderIndex,
                    lastBootstrapLoadMs = cachedBootstrap?.bootstrapLoadMs ?: it.lastBootstrapLoadMs,
                    lastSummaryLoadMs = cachedBootstrap?.summaryLoadMs ?: it.lastSummaryLoadMs,
                    lastTailLoadMs = cachedBootstrap?.tailLoadMs ?: it.lastTailLoadMs,
                )
            }
            try {
                applyStoredRuntimeDefaultsIfNeeded(serverId)
                viewModelScope.launch {
                    fetchRuntimeCatalog(serverId)
                }
                val applyBootstrap: suspend (SessionDetailBootstrapSnapshot) -> Unit = { snapshot ->
                    _uiState.update { state ->
                        applyConversationContinuity(
                            state = state.copy(
                                session = snapshot.session,
                                messages = snapshot.messages,
                                hasMoreHistory = snapshot.hasMoreHistory,
                                nextBeforeOrderIndex = snapshot.nextBeforeOrderIndex,
                                loadingOlderHistory = false,
                                lastBootstrapLoadMs = snapshot.bootstrapLoadMs,
                                lastSummaryLoadMs = snapshot.summaryLoadMs,
                                lastTailLoadMs = snapshot.tailLoadMs,
                            ),
                            messages = snapshot.messages,
                            liveRun = state.liveRun,
                        )
                    }
                    Log.d(
                        PERF_TAG,
                        "bootstrap session=$sessionId cached=$startedFromBootstrap ms=${snapshot.bootstrapLoadMs ?: 0L} summaryMs=${snapshot.summaryLoadMs ?: 0L} tailMs=${snapshot.tailLoadMs ?: 0L} tail=${snapshot.messages.size} hasMore=${snapshot.hasMoreHistory}",
                    )
                    SessionPerformanceStore.update(sessionId) {
                        it.copy(
                            bootstrapLoadMs = snapshot.bootstrapLoadMs,
                            bootstrapStatus = "ok",
                            summaryLoadMs = snapshot.summaryLoadMs,
                            tailLoadMs = snapshot.tailLoadMs,
                        )
                    }
                    SessionDetailBootstrapStore.store(snapshot)
                }

                if (startedFromBootstrap) {
                    launchDeferredSessionRefreshes(serverId, sessionId)
                    viewModelScope.launch {
                        runCatching {
                            fetchBootstrapSnapshot(
                                serverId = serverId,
                                sessionId = sessionId,
                                loadStartedAt = SystemClock.elapsedRealtime(),
                            )
                        }.onSuccess { snapshot ->
                            applyBootstrap(snapshot)
                        }.onFailure { error ->
                            val bootstrapLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
                            SessionPerformanceStore.update(sessionId) {
                                it.copy(
                                    bootstrapLoadMs = bootstrapLoadMs,
                                    bootstrapStatus = "error",
                                )
                            }
                            Log.d(
                                PERF_TAG,
                                "bootstrap session=$sessionId cached=true ms=$bootstrapLoadMs success=false reason=${error.message ?: "unknown"}",
                            )
                        }
                    }
                    return@launch
                }

                val snapshot = fetchBootstrapSnapshot(serverId, sessionId, loadStartedAt)
                applyBootstrap(snapshot)
                _uiState.update { it.copy(loading = false) }
                launchDeferredSessionRefreshes(serverId, sessionId)
            } catch (e: Exception) {
                val bootstrapLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
                _uiState.update { state ->
                    state.copy(lastBootstrapLoadMs = bootstrapLoadMs)
                }
                Log.d(
                    PERF_TAG,
                    "bootstrap session=$sessionId cached=$startedFromBootstrap ms=$bootstrapLoadMs success=false",
                )
                SessionPerformanceStore.update(sessionId) {
                    it.copy(
                        bootstrapLoadMs = bootstrapLoadMs,
                        bootstrapStatus = "error",
                    )
                }
                val existingState = _uiState.value
                if (
                    startedFromBootstrap ||
                    (isTransientTimeout(e) || isRecoverableConnectivityError(e)) &&
                    hasVisibleSessionContent(existingState)
                ) {
                    _uiState.update {
                        it.copy(
                            session = it.session,
                            messages = it.messages,
                            recoveryNotice = string(R.string.session_detail_recovery_degraded),
                        )
                    }
                    launchDeferredSessionRefreshes(serverId, sessionId)
                    maybeNotifyBackgroundNeedsAttention(serverId, sessionId, existingState)
                } else {
                    _uiState.update {
                        it.copy(
                            error = userFacingMessage(e, string(R.string.session_detail_error_load)),
                            session = it.session,
                            messages = it.messages,
                        )
                    }
                }
        } finally {
                _uiState.update { it.copy(loading = false, refreshing = false, transitionLoading = false) }
            }
        }
    }

    fun prepareDraft(serverId: String? = null) {
        stopLiveRunStream()
        backgroundMonitorJob?.cancel()
        backgroundMonitorJob = null
        stopForegroundMessageSync()
        activeSessionRef = null
        backgroundAttentionContextKey = null
        lastNotifiedBackgroundEventKey = null
        val snapshot = _uiState.value
        _uiState.value = SessionDetailUiState(
            loading = false,
            transitionLoading = false,
            selectedModel = snapshot.selectedModel,
            selectedReasoningEffort = snapshot.selectedReasoningEffort,
            selectedPermissionMode = snapshot.selectedPermissionMode,
            lastRequestedPermissionMode = snapshot.lastRequestedPermissionMode,
            runtimeControlsInitialized = snapshot.runtimeControlsInitialized,
            runtimeModels = snapshot.runtimeModels,
        )
        if (!serverId.isNullOrBlank()) {
            viewModelScope.launch {
                applyStoredRuntimeDefaultsIfNeeded(serverId)
                fetchRuntimeCatalog(serverId)
            }
        }
    }

    fun refresh(serverId: String, sessionId: String) {
        rememberActiveSession(serverId, sessionId)
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, error = null) }
            try {
                refreshSessionAndLive(serverId, sessionId)
            } catch (e: Exception) {
                val snapshot = _uiState.value
                val canRecover = (isTransientTimeout(e) || isRecoverableConnectivityError(e)) &&
                    (hasVisibleSessionContent(snapshot) || snapshot.liveRun?.status in activeRunStatuses || snapshot.liveStreamConnected)
                if (!canRecover) {
                    _uiState.update {
                        it.copy(error = userFacingMessage(e, string(R.string.session_detail_error_refresh)))
                    }
                } else {
                    _uiState.update {
                        it.copy(recoveryNotice = string(R.string.session_detail_recovery_degraded))
                    }
                    maybeNotifyBackgroundNeedsAttention(serverId, sessionId, snapshot)
                }
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Silently refresh session detail and live run.
     * Used when SSE signals a run has ended so the page can settle to final state.
     */
    private fun silentRefreshSessionAndLive(serverId: String, sessionId: String) {
        viewModelScope.launch {
            try {
                refreshSessionMessagesTail(serverId, sessionId)
                val snapshot = _uiState.value
                val needsFullRefresh = snapshot.session == null ||
                    !hasSettledCurrentTurn(snapshot.messages, snapshot.currentTurnPromptOverride)
                if (needsFullRefresh) {
                    refreshSessionAndLive(serverId, sessionId)
                }
            } catch (_: Exception) {
                try {
                    refreshSessionAndLive(serverId, sessionId)
                } catch (_: Exception) {
                    // Best-effort; the page already has streaming data.
                }
            }
        }
    }

    fun refreshLiveRun(serverId: String, sessionId: String) {
        rememberActiveSession(serverId, sessionId)
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val liveRun = fetchLiveRun(serverId, sessionId)
                val liveMs = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { state ->
                    applyConversationContinuity(
                        state = state.copy(
                            liveRun = liveRun,
                            lastLiveRefreshMs = liveMs,
                        ),
                        liveRun = liveRun,
                    )
                }
                Log.d(
                    PERF_TAG,
                    "live session=$sessionId ms=$liveMs status=${liveRun?.status ?: "none"}",
                )
                SessionPerformanceStore.update(sessionId) {
                    it.copy(
                        liveRefreshMs = liveMs,
                        liveStatus = "ok",
                    )
                }
                seedRuntimeControlsFromRun(liveRun)
                maybeNotifyRunState(serverId, sessionId, liveRun)
                maybeNotifyRecovered(serverId, sessionId, liveRun)
                markRecoverySyncedIfNeeded()
                startBackgroundMonitorIfNeeded()
            } catch (_: Exception) {
                val liveMs = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { state ->
                    state.copy(lastLiveRefreshMs = liveMs)
                }
                Log.d(
                    PERF_TAG,
                    "live session=$sessionId ms=$liveMs status=error",
                )
                SessionPerformanceStore.update(sessionId) {
                    it.copy(
                        liveRefreshMs = liveMs,
                        liveStatus = "error",
                    )
                }
                // Keep the last visible live state when polling blips.
            }
        }
    }

    fun startLiveRunStream(serverId: String, sessionId: String) {
        rememberActiveSession(serverId, sessionId)
        val nextKey = serverId to sessionId
        if (liveStreamKey == nextKey && liveStreamJob?.isActive == true) return

        stopLiveRunStream(resetState = false)
        liveStreamKey = nextKey
        _uiState.update {
            it.copy(
                liveStreamConnected = false,
                liveStreamStatus = string(R.string.session_detail_stream_connecting),
            )
        }

        liveStreamJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val handle = resolveServer(serverId)
                try {
                    handle.client.streamLiveRun(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                        initialLastEventId = liveStreamLastEventId,
                    ).collect { event ->
                        when (event) {
                            is LiveRunStreamEvent.Connected -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = true,
                                        liveStreamStatus = if (event.resumedFromEventId != null) {
                                            string(R.string.session_detail_stream_resumed)
                                        } else {
                                            string(R.string.session_detail_stream_connected)
                                        },
                                    )
                                }
                            }
                            is LiveRunStreamEvent.RunSnapshot -> {
                                liveStreamLastEventId = event.eventId ?: liveStreamLastEventId
                                val isTerminal = event.run?.status in terminalRunStatuses
                                _uiState.update { state ->
                                    applyConversationContinuity(
                                        state = state.copy(
                                            liveRun = event.run,
                                            liveStreamConnected = true,
                                            liveStreamStatus = when (event.run?.status) {
                                                "running", "pending" -> string(R.string.session_detail_stream_live)
                                                else -> string(R.string.session_detail_stream_connected)
                                            },
                                        ),
                                        liveRun = event.run,
                                    )
                                }
                                seedRuntimeControlsFromRun(event.run)
                                maybeNotifyRunState(serverId, sessionId, event.run)
                                maybeNotifyRecovered(serverId, sessionId, event.run)
                                // Auto-refresh full session when run reaches terminal state
                                if (isTerminal) {
                                    silentRefreshSessionAndLive(serverId, sessionId)
                                    maybeDispatchQueuedPrompt(serverId, sessionId)
                                }
                            }
                            is LiveRunStreamEvent.ApprovalUpdate -> {
                                liveStreamLastEventId = event.eventId ?: liveStreamLastEventId
                                _uiState.update { state ->
                                    state.copy(
                                        pendingApprovals = mergeApprovalEvent(
                                            state.pendingApprovals,
                                            event.approval,
                                        ),
                                        resolvingApprovalId = state.resolvingApprovalId
                                            ?.takeIf { it != event.approval.id || event.approval.status == "pending" },
                                    )
                                }
                            }
                            is LiveRunStreamEvent.Gap -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = true,
                                        liveStreamStatus = string(R.string.session_detail_stream_caught_up),
                                    )
                                }
                            }
                            is LiveRunStreamEvent.StreamEnd -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = true,
                                        liveStreamStatus = when (event.reason) {
                                            "no-run" -> string(R.string.session_detail_stream_waiting_next)
                                            else -> string(R.string.session_detail_stream_finished_keep_alive)
                                        },
                                    )
                                }
                                // Run ended — pull final messages and run state
                                silentRefreshSessionAndLive(serverId, sessionId)
                                maybeDispatchQueuedPrompt(serverId, sessionId)
                            }
                            is LiveRunStreamEvent.IdleTimeout -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = false,
                                        liveStreamStatus = string(R.string.session_detail_stream_idle_recovering),
                                    )
                                }
                                maybeNotifyBackgroundNeedsAttention(serverId, sessionId, _uiState.value)
                            }
                            is LiveRunStreamEvent.Reconnecting -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = false,
                                        liveStreamStatus = event.message,
                                    )
                                }
                                maybeNotifyBackgroundNeedsAttention(serverId, sessionId, _uiState.value)
                            }
                        }
                    }
                } finally {
                    handle.client.close()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) return@launch
                _uiState.update {
                    it.copy(
                        liveStreamConnected = false,
                        liveStreamStatus = string(R.string.session_detail_stream_fallback_polling),
                    )
                }
                maybeNotifyBackgroundNeedsAttention(serverId, sessionId, _uiState.value)
            }
        }
    }

    fun restartLiveRunStream(serverId: String, sessionId: String) {
        stopLiveRunStream(resetState = false)
        startLiveRunStream(serverId, sessionId)
    }

    fun stopLiveRunStream(resetState: Boolean = true) {
        liveStreamJob?.cancel()
        liveStreamJob = null
        backgroundMonitorJob?.cancel()
        backgroundMonitorJob = null
        liveStreamKey = null
        liveStreamLastEventId = null
        if (resetState) {
            backgroundAttentionContextKey = null
            lastNotifiedBackgroundEventKey = null
            _uiState.update {
                it.copy(
                    liveStreamConnected = false,
                    liveStreamStatus = null,
                )
            }
        }
    }

    fun setPrompt(prompt: String) {
        _uiState.update { it.copy(prompt = prompt) }
    }

    fun appendPrompt(promptFragment: String) {
        val trimmed = promptFragment.trim()
        if (trimmed.isBlank()) return
        _uiState.update { state ->
            val nextPrompt = if (state.prompt.isBlank()) {
                trimmed
            } else {
                "${state.prompt.trimEnd()}\n$trimmed"
            }
            state.copy(prompt = nextPrompt)
        }
    }

    fun setSelectedModel(model: String?) {
        _uiState.update {
            it.copy(
                selectedModel = normalizeRuntimeControl(model),
                runtimeControlsInitialized = true,
            )
        }
    }

    fun setSelectedReasoningEffort(reasoningEffort: String?) {
        _uiState.update {
            it.copy(
                selectedReasoningEffort = normalizeRuntimeControl(reasoningEffort),
                runtimeControlsInitialized = true,
            )
        }
    }

    fun setSelectedPermissionMode(permissionMode: String?) {
        _uiState.update {
            it.copy(
                selectedPermissionMode = normalizePermissionMode(permissionMode),
                runtimeControlsInitialized = true,
            )
        }
    }

    fun setRuntimeControls(
        model: String?,
        reasoningEffort: String?,
        permissionMode: String?,
    ) {
        _uiState.update { state ->
            state.copy(
                selectedModel = normalizeRuntimeControl(model),
                selectedReasoningEffort = normalizeRuntimeControl(reasoningEffort),
                selectedPermissionMode = normalizePermissionMode(permissionMode),
                runtimeControlsInitialized = true,
            )
        }
    }

    fun queuePrompt(
        sessionId: String?,
        model: String? = null,
        reasoningEffort: String? = null,
        permissionMode: String? = null,
    ) {
        val snapshot = _uiState.value
        val runtimeControls = resolveRuntimeControls(
            snapshot = snapshot,
            model = model,
            reasoningEffort = reasoningEffort,
            permissionMode = permissionMode,
        )
        if (model != null || reasoningEffort != null || permissionMode != null) {
            _uiState.update { state ->
                state.copy(
                    selectedModel = runtimeControls.model,
                    selectedReasoningEffort = runtimeControls.reasoningEffort,
                    selectedPermissionMode = runtimeControls.permissionMode,
                    runtimeControlsInitialized = true,
                )
            }
        }
        if (sessionId.isNullOrBlank()) {
            _uiState.update { it.copy(error = string(R.string.session_detail_queue_requires_session)) }
            return
        }
        if (snapshot.liveRun?.status !in activeRunStatuses) {
            _uiState.update { it.copy(error = string(R.string.session_detail_queue_no_active_run)) }
            return
        }

        val rawPrompt = snapshot.prompt.trim()
        if (rawPrompt.isBlank()) return

        val queuedPrompt = QueuedPrompt(
            id = java.util.UUID.randomUUID().toString(),
            rawPrompt = rawPrompt,
            artifacts = snapshot.pendingArtifacts,
            model = runtimeControls.model,
            reasoningEffort = runtimeControls.reasoningEffort,
            permissionMode = runtimeControls.permissionMode,
        )
        _uiState.update {
            it.copy(
                prompt = "",
                pendingArtifacts = emptyList(),
                queuedPrompts = it.queuedPrompts + queuedPrompt,
                error = null,
            )
        }
    }

    fun removeQueuedPrompt(queuedPromptId: String) {
        _uiState.update { state ->
            state.copy(
                queuedPrompts = state.queuedPrompts.filterNot { it.id == queuedPromptId },
            )
        }
    }

    fun restoreQueuedPrompt(queuedPromptId: String): QueuedPrompt? {
        val queuedPrompt = _uiState.value.queuedPrompts.firstOrNull { it.id == queuedPromptId } ?: return null
        _uiState.update { state ->
            state.copy(
                prompt = queuedPrompt.rawPrompt,
                pendingArtifacts = queuedPrompt.artifacts,
                selectedModel = queuedPrompt.model,
                selectedReasoningEffort = queuedPrompt.reasoningEffort,
                selectedPermissionMode = queuedPrompt.permissionMode,
                runtimeControlsInitialized = true,
                queuedPrompts = state.queuedPrompts.filterNot { it.id == queuedPromptId },
                error = null,
            )
        }
        return queuedPrompt
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearRecoveryNotice() {
        _uiState.update { it.copy(recoveryNotice = null) }
    }

    fun dismissRepoActionSummary() {
        _uiState.update { it.copy(repoActionSummary = null) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    suspend fun loadSkillSuggestions(serverId: String, source: String? = null): List<SkillEntry> =
        withServerClient(serverId) { handle ->
            handle.client.listSkills(handle.token, source = source).skills
        }

    suspend fun searchFileSuggestions(
        serverId: String,
        query: String,
        sessionId: String?,
        cwd: String?,
        limit: Int = 12,
    ): List<FileEntry> = withServerClient(serverId) { handle ->
        handle.client.searchFiles(
            token = handle.token,
            hostId = SESSION_HOST_ID,
            query = query,
            sessionId = sessionId,
            cwd = if (sessionId.isNullOrBlank()) cwd else null,
            limit = limit,
        ).results
    }

    suspend fun listFileSuggestions(
        serverId: String,
        sessionId: String?,
        cwd: String?,
        path: String? = null,
    ): ListFilesResponse = withServerClient(serverId) { handle ->
        handle.client.listFiles(
            token = handle.token,
            hostId = SESSION_HOST_ID,
            sessionId = sessionId,
            cwd = if (sessionId.isNullOrBlank()) cwd else null,
            path = path,
        )
    }

    fun onAppBackgrounded() {
        _uiState.update { state ->
            state.copy(
                appInBackground = true,
                error = null,
            )
        }
        startBackgroundMonitorIfNeeded()
    }

    fun onAppForegrounded(serverId: String, sessionId: String?) {
        backgroundMonitorJob?.cancel()
        backgroundMonitorJob = null
        _uiState.update {
            it.copy(
                appInBackground = false,
                recoveryNotice = if (sessionId.isNullOrBlank()) null else string(R.string.session_detail_recovery_restoring),
                error = null,
            )
        }
        if (!sessionId.isNullOrBlank()) {
            rememberActiveSession(serverId, sessionId)
            restartLiveRunStream(serverId, sessionId)
            refreshLiveRun(serverId, sessionId)
            refresh(serverId, sessionId)
            startForegroundMessageSyncIfNeeded(serverId, sessionId)
        }
    }

    fun createBranch(serverId: String, sessionId: String?, branch: String) {
        if (sessionId.isNullOrBlank()) return
        performRepoAction(
            serverId = serverId,
            sessionId = sessionId,
            action = RepoActionRequest(
                action = "createBranch",
                branch = branch.trim(),
            ),
        )
    }

    fun checkoutBranch(serverId: String, sessionId: String?, branch: String) {
        if (sessionId.isNullOrBlank()) return
        performRepoAction(
            serverId = serverId,
            sessionId = sessionId,
            action = RepoActionRequest(
                action = "checkout",
                branch = branch.trim(),
            ),
        )
    }

    fun commitRepo(serverId: String, sessionId: String?, message: String) {
        if (sessionId.isNullOrBlank()) return
        performRepoAction(
            serverId = serverId,
            sessionId = sessionId,
            action = RepoActionRequest(
                action = "commit",
                message = message.trim(),
            ),
        )
    }

    fun pushRepo(serverId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        performRepoAction(
            serverId = serverId,
            sessionId = sessionId,
            action = RepoActionRequest(action = "push"),
        )
    }

    fun pullRepo(serverId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        performRepoAction(
            serverId = serverId,
            sessionId = sessionId,
            action = RepoActionRequest(action = "pull"),
        )
    }

    fun stashRepo(serverId: String, sessionId: String?, message: String? = null) {
        if (sessionId.isNullOrBlank()) return
        performRepoAction(
            serverId = serverId,
            sessionId = sessionId,
            action = RepoActionRequest(
                action = "stash",
                message = message?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
    }

    fun loadRepoLog(serverId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(repoLogLoading = true, error = null) }
            try {
                val entries = withServerClient(serverId) { handle ->
                    handle.client.getRepoLog(handle.token, SESSION_HOST_ID, sessionId).entries
                }
                _uiState.update { it.copy(repoLogEntries = entries, repoLogLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        repoLogLoading = false,
                        error = userFacingMessage(e, string(R.string.session_detail_error_repo_action)),
                    )
                }
            }
        }
    }

    fun consumeArchived() {
        _uiState.update { it.copy(archived = false) }
    }

    fun consumeCreatedSession() {
        _uiState.update { it.copy(createdSessionId = null) }
    }

    fun retryLatestPrompt(fallbackPrompt: String? = null) {
        val snapshot = _uiState.value
        val latestPrompt = (
            snapshot.lastSubmittedPrompt?.trim()?.takeIf { it.isNotBlank() }
                ?: sanitizePromptDisplay(fallbackPrompt)
            ).orEmpty()
        if (latestPrompt.isBlank()) return

        _uiState.update {
            it.copy(
                prompt = latestPrompt,
                error = null,
            )
        }
    }

    fun resendLatestPrompt(
        serverId: String,
        sessionId: String,
        fallbackPrompt: String? = null,
    ) {
        viewModelScope.launch {
            val snapshot = _uiState.value
            val promptToResend = snapshot.lastSubmittedPromptWithAttachments
                ?: sanitizePromptDisplay(fallbackPrompt)
                ?: buildComposedPrompt(
                    snapshot.lastSubmittedPrompt ?: snapshot.prompt,
                    snapshot.pendingArtifacts,
                )
            if (promptToResend.isBlank() || snapshot.sending || snapshot.liveRun?.status == "running" || snapshot.liveRun?.status == "pending") {
                return@launch
            }

            submitPrompt(
                serverId = serverId,
                sessionId = sessionId,
                rawPrompt = sanitizePromptDisplay(snapshot.lastSubmittedPrompt)
                    ?: sanitizePromptDisplay(fallbackPrompt)
                    ?: snapshot.prompt.trim(),
                composedPrompt = promptToResend.trim(),
                clearComposer = true,
            )
        }
    }

    fun sendEditedPrompt(
        serverId: String,
        sessionId: String,
        prompt: String,
        artifacts: List<Artifact> = emptyList(),
    ) {
        viewModelScope.launch {
            val editedPrompt = prompt.trim()
            if (editedPrompt.isBlank()) return@launch

            val composedPrompt = buildComposedPrompt(editedPrompt, artifacts)
            submitPrompt(
                serverId = serverId,
                sessionId = sessionId,
                rawPrompt = editedPrompt,
                composedPrompt = composedPrompt,
                clearComposer = false,
            )
        }
    }

    private suspend fun submitPrompt(
        serverId: String,
        sessionId: String,
        rawPrompt: String,
        composedPrompt: String,
        clearComposer: Boolean,
        runtimeControls: RuntimeControls? = null,
    ): Boolean {
        val snapshot = _uiState.value
        if (
            snapshot.sending ||
            snapshot.liveRun?.status == "running" ||
            snapshot.liveRun?.status == "pending"
        ) {
            return false
        }

        _uiState.update { it.copy(sending = true, error = null) }
        try {
            val controls = runtimeControls ?: currentRuntimeControls(snapshot)
            sendPromptInternal(
                serverId = serverId,
                sessionId = sessionId,
                composedPrompt = composedPrompt.trim(),
                model = controls.model,
                reasoningEffort = controls.reasoningEffort,
                permissionMode = controls.permissionMode,
            )
            _uiState.update {
                val afterComposer =
                    if (clearComposer) {
                        it.copy(
                            prompt = "",
                            pendingArtifacts = emptyList(),
                        )
                    } else {
                        it
                    }
                afterComposer.copy(
                    lastSubmittedPrompt = rawPrompt.trim(),
                    lastSubmittedPromptWithAttachments = composedPrompt.trim(),
                    currentTurnPromptOverride = rawPrompt.trim(),
                    retainedLiveOutput = null,
                    retainedLiveRunId = null,
                )
            }
            startForegroundMessageSyncIfNeeded(serverId, sessionId)
            refreshLiveRun(serverId, sessionId)
            runCatching {
                refreshSessionMessagesTail(serverId, sessionId)
            }
            return true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = userFacingMessage(e, string(R.string.session_detail_error_start_run)))
            }
            return false
        } finally {
            _uiState.update { it.copy(sending = false) }
        }
    }

    fun removeArtifact(artifactId: String) {
        _uiState.update { state ->
            state.copy(
                pendingArtifacts = state.pendingArtifacts.filterNot { it.id == artifactId },
            )
        }
    }

    fun removeLocalAttachment(attachmentId: String) {
        _uiState.update { state ->
            state.copy(
                pendingLocalAttachments = state.pendingLocalAttachments.filterNot { it.id == attachmentId },
            )
        }
    }

    suspend fun sendRecordedVoiceNote(
        serverId: String,
        sessionId: String?,
        draftCwd: String?,
        audio: RecordedVoiceInput,
    ) {
        val hiddenPrompt = hiddenVoiceNoteInstruction
        val runtimeControls = currentRuntimeControls(_uiState.value)
        if (sessionId.isNullOrBlank()) {
            val cwd = draftCwd?.takeIf { it.isNotBlank() } ?: run {
                _uiState.update { it.copy(error = string(R.string.session_detail_error_missing_cwd)) }
                return
            }
            createDraftSessionWithQueuedAttachments(
                serverId = serverId,
                cwd = cwd,
                rawPrompt = hiddenPrompt,
                localAttachments = listOf(
                    PendingLocalAttachment(
                        id = java.util.UUID.randomUUID().toString(),
                        originalName = audio.fileName,
                        mimeType = audio.mimeType,
                        sizeBytes = audio.bytes.size.toLong(),
                        bytes = audio.bytes,
                        durationMs = audio.durationMs,
                    ),
                ),
                runtimeControls = runtimeControls,
            )
            return
        }

        _uiState.update { it.copy(uploading = true, error = null) }
        try {
            val artifact = withServerClient(serverId) { handle ->
                withContext(Dispatchers.IO) {
                    handle.client.uploadSessionArtifact(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                        fileName = audio.fileName,
                        mimeType = audio.mimeType,
                        bytes = audio.bytes,
                    )
                }
            }
            val success = submitPrompt(
                serverId = serverId,
                sessionId = sessionId,
                rawPrompt = hiddenPrompt,
                composedPrompt = buildComposedPrompt(
                    hiddenPrompt,
                    listOf(artifact),
                    audioDurationsMs = mapOf(artifact.id to audio.durationMs),
                ),
                clearComposer = false,
                runtimeControls = runtimeControls,
            )
            if (!success) {
                _uiState.update {
                    it.copy(error = string(R.string.session_detail_error_start_run))
                }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = userFacingMessage(e, string(R.string.session_detail_error_upload)))
            }
            throw e
        } finally {
            _uiState.update { it.copy(uploading = false) }
        }
    }

    fun archiveSession(serverId: String, sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(archiving = true, error = null) }
            try {
                withServerClient(serverId) { handle ->
                    handle.client.archiveSession(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                    )
                }
                _uiState.update { it.copy(archiving = false, archived = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        archiving = false,
                        error = userFacingMessage(e, string(R.string.session_detail_error_archive)),
                    )
                }
            }
        }
    }

    fun uploadAttachment(
        serverId: String,
        sessionId: String,
        uri: Uri,
        onUploaded: ((Artifact) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(uploading = true, error = null) }
            try {
                val app = getApplication<Application>()
                val resolver = app.contentResolver
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                val fileName = queryDisplayName(resolver, uri) ?: defaultFileName(uri)
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(string(R.string.session_detail_error_file_read))
                }

                val artifact = withServerClient(serverId) { handle ->
                    withContext(Dispatchers.IO) {
                        handle.client.uploadSessionArtifact(
                            token = handle.token,
                            hostId = SESSION_HOST_ID,
                            sessionId = sessionId,
                            fileName = fileName,
                            mimeType = mimeType,
                            bytes = bytes,
                        )
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        pendingArtifacts = state.pendingArtifacts + artifact,
                    )
                }
                onUploaded?.invoke(artifact)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = userFacingMessage(e, string(R.string.session_detail_error_upload)))
                }
            } finally {
                _uiState.update { it.copy(uploading = false) }
            }
        }
    }

    fun queueLocalAttachment(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(uploading = true, error = null) }
            try {
                val app = getApplication<Application>()
                val resolver = app.contentResolver
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                val fileName = queryDisplayName(resolver, uri) ?: defaultFileName(uri)
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(string(R.string.session_detail_error_file_read))
                }
                _uiState.update { state ->
                    state.copy(
                        pendingLocalAttachments = state.pendingLocalAttachments + PendingLocalAttachment(
                            id = java.util.UUID.randomUUID().toString(),
                            originalName = fileName,
                            mimeType = mimeType,
                            sizeBytes = bytes.size.toLong(),
                            bytes = bytes,
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = userFacingMessage(e, string(R.string.session_detail_error_add_attachment)),
                    )
                }
            } finally {
                _uiState.update { it.copy(uploading = false) }
            }
        }
    }

    fun sendPrompt(
        serverId: String,
        sessionId: String?,
        draftCwd: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        permissionMode: String? = null,
    ) {
        viewModelScope.launch {
            val snapshot = _uiState.value
            val runtimeControls = resolveRuntimeControls(
                snapshot = snapshot,
                model = model,
                reasoningEffort = reasoningEffort,
                permissionMode = permissionMode,
            )
            if (model != null || reasoningEffort != null || permissionMode != null) {
                _uiState.update { state ->
                    state.copy(
                        selectedModel = runtimeControls.model,
                        selectedReasoningEffort = runtimeControls.reasoningEffort,
                        selectedPermissionMode = runtimeControls.permissionMode,
                        runtimeControlsInitialized = true,
                    )
                }
            }
            val rawPrompt = snapshot.prompt.trim()
            if (rawPrompt.isBlank() || snapshot.sending || snapshot.liveRun?.status == "running" || snapshot.liveRun?.status == "pending") {
                return@launch
            }

            val resolvedSessionId = sessionId ?: if (draftCwd.isNullOrBlank()) {
                _uiState.update { it.copy(error = string(R.string.session_detail_error_missing_cwd)) }
                return@launch
            } else {
                if (snapshot.pendingLocalAttachments.isNotEmpty()) {
                    createDraftSessionWithQueuedAttachments(
                        serverId = serverId,
                        cwd = draftCwd,
                        rawPrompt = rawPrompt,
                        localAttachments = snapshot.pendingLocalAttachments,
                        runtimeControls = runtimeControls,
                    )
                } else if (hasExplicitRuntimeControls(snapshot, model, reasoningEffort, permissionMode)) {
                    createSession(
                        serverId = serverId,
                        cwd = draftCwd,
                        prompt = rawPrompt,
                        runtimeControls = runtimeControls,
                    )
                } else {
                    createSession(serverId, draftCwd, rawPrompt)
                }
                return@launch
            }

            submitPrompt(
                serverId = serverId,
                sessionId = resolvedSessionId,
                rawPrompt = rawPrompt,
                composedPrompt = buildComposedPrompt(rawPrompt, snapshot.pendingArtifacts),
                clearComposer = true,
                runtimeControls = runtimeControls,
            )
        }
    }

    private suspend fun sendPromptInternal(
        serverId: String,
        sessionId: String,
        composedPrompt: String,
        model: String? = null,
        reasoningEffort: String? = null,
        permissionMode: String = "full-access",
    ) {
        _uiState.update {
            it.copy(lastRequestedPermissionMode = normalizePermissionMode(permissionMode))
        }
        withServerClient(serverId) { handle ->
            handle.client.startLiveRun(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                prompt = composedPrompt,
                model = model,
                reasoningEffort = reasoningEffort,
                permissionMode = permissionMode,
            )
        }
    }

    fun stopRun(serverId: String, sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(stopping = true) }
            try {
                withServerClient(serverId) { handle ->
                    handle.client.stopLiveRun(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                    )
                }
                refreshLiveRun(serverId, sessionId)
            } catch (_: Exception) {
                // Best effort, matching the web client.
            } finally {
                _uiState.update { it.copy(stopping = false) }
            }
        }
    }

    fun decidePendingApproval(
        serverId: String,
        sessionId: String?,
        approval: PendingApproval,
        decision: String,
        onResolved: (() -> Unit)? = null,
    ) {
        if (sessionId.isNullOrBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(resolvingApprovalId = approval.id, error = null) }
            try {
                withServerClient(serverId) { handle ->
                    handle.client.decidePendingApproval(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                        approvalId = approval.id,
                        request = when (approval.kind) {
                            "permissions" -> PendingApprovalDecisionRequest(
                                kind = "permissions",
                                permissions = approval.permissions ?: PendingPermissionProfile(),
                                scope = if (decision == "acceptForSession") "session" else "turn",
                            )
                            else -> PendingApprovalDecisionRequest(
                                kind = approval.kind,
                                decision = decision,
                            )
                        },
                    )
                }
                refreshPendingApprovals(serverId, sessionId)
                onResolved?.invoke()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = userFacingMessage(e, string(R.string.session_detail_error_refresh)),
                    )
                }
            } finally {
                _uiState.update { state ->
                    state.copy(
                        resolvingApprovalId = state.resolvingApprovalId?.takeIf { it != approval.id },
                    )
                }
            }
        }
    }

    suspend fun downloadSessionFile(
        serverId: String,
        sessionId: String,
        relativePath: String,
    ): DownloadedSessionFile {
        val payload = withServerClient(serverId) { handle ->
            handle.client.downloadSessionFile(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                relativePath = relativePath,
            )
        }

        val downloadsDir = File(getApplication<Application>().cacheDir, "downloads").apply {
            mkdirs()
        }
        val outputFile = File(downloadsDir, payload.fileName)
        withContext(Dispatchers.IO) {
            outputFile.writeBytes(payload.bytes)
        }
        return DownloadedSessionFile(
            file = outputFile,
            fileName = payload.fileName,
            mimeType = payload.mimeType,
        )
    }

    suspend fun downloadAbsoluteFile(
        serverId: String,
        sessionId: String,
        absolutePath: String,
    ): DownloadedSessionFile {
        val payload = withServerClient(serverId) { handle ->
            handle.client.downloadAbsoluteFile(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                absolutePath = absolutePath,
            )
        }

        val downloadsDir = File(getApplication<Application>().cacheDir, "downloads").apply {
            mkdirs()
        }
        val outputFile = File(downloadsDir, payload.fileName)
        withContext(Dispatchers.IO) {
            outputFile.writeBytes(payload.bytes)
        }
        return DownloadedSessionFile(
            file = outputFile,
            fileName = payload.fileName,
            mimeType = payload.mimeType,
        )
    }

    suspend fun downloadArtifactFile(
        serverId: String,
        sessionId: String,
        artifactId: String,
    ): DownloadedSessionFile {
        val payload = withServerClient(serverId) { handle ->
            handle.client.downloadArtifactFile(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                artifactId = artifactId,
            )
        }

        val downloadsDir = File(getApplication<Application>().cacheDir, "downloads").apply {
            mkdirs()
        }
        val outputFile = File(downloadsDir, payload.fileName)
        withContext(Dispatchers.IO) {
            outputFile.writeBytes(payload.bytes)
        }
        return DownloadedSessionFile(
            file = outputFile,
            fileName = payload.fileName,
            mimeType = payload.mimeType,
        )
    }

    private suspend fun refreshSessionAndLive(
        serverId: String,
        sessionId: String,
    ) {
        withServerClient(serverId) { handle ->
            val detail: SessionDetailResponse = coroutineScope {
                val sessionDeferred = async(Dispatchers.IO) {
                    handle.client.getSessionDetail(handle.token, SESSION_HOST_ID, sessionId)
                }
                val liveDeferred = async(Dispatchers.IO) {
                    handle.client.getLiveRun(handle.token, SESSION_HOST_ID, sessionId)
                }
                val approvalsDeferred = async(Dispatchers.IO) {
                    runCatching {
                        handle.client.listPendingApprovals(handle.token, SESSION_HOST_ID, sessionId).approvals
                    }.getOrElse { emptyList() }
                }
                val repoDeferred = async(Dispatchers.IO) {
                    runCatching {
                        handle.client.getRepoStatus(handle.token, SESSION_HOST_ID, sessionId).repoStatus
                    }.getOrNull()
                }
                val session = sessionDeferred.await()
                val liveRun = liveDeferred.await()
                val approvals = approvalsDeferred.await()
                val repoStatus = repoDeferred.await()
                _uiState.update { state ->
                    applyConversationContinuity(
                        state = state.copy(
                            session = session.session,
                            repoStatus = repoStatus,
                            messages = session.messages,
                            liveRun = liveRun,
                            pendingApprovals = approvals.sortedBy { it.createdAt },
                        ),
                        messages = session.messages,
                        liveRun = liveRun,
                    )
                }
                seedRuntimeControlsFromRun(liveRun)
                maybeNotifyRunState(serverId, sessionId, liveRun)
                maybeNotifyRecovered(serverId, sessionId, liveRun)
                startBackgroundMonitorIfNeeded()
                startForegroundMessageSyncIfNeeded(serverId, sessionId)
                markRecoverySyncedIfNeeded()
                session
            }
            if (_uiState.value.liveRun?.status !in activeRunStatuses) {
                stopForegroundMessageSync()
                maybeDispatchQueuedPrompt(serverId, sessionId)
            }
            if (_uiState.value.session == null) {
                _uiState.update { state ->
                    state.copy(
                        session = detail.session,
                        messages = detail.messages,
                    )
                }
            }
        }
    }

    private suspend fun refreshSessionMessagesTail(
        serverId: String,
        sessionId: String,
        limit: Int = FOREGROUND_MESSAGE_TAIL_LIMIT,
    ) {
        val tailMessages = withServerClient(serverId) { handle ->
            handle.client.getSessionMessages(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                limit = limit,
            )
        }
        _uiState.update { state ->
            val mergedMessages = mergeSessionMessages(state.messages, tailMessages.messages)
            applyConversationContinuity(
                state = state.copy(
                    messages = mergedMessages,
                    hasMoreHistory = if (state.nextBeforeOrderIndex != null) {
                        state.hasMoreHistory
                    } else {
                        tailMessages.hasMore
                    },
                    nextBeforeOrderIndex = state.nextBeforeOrderIndex ?: tailMessages.nextBeforeOrderIndex,
                ),
                messages = mergedMessages,
                liveRun = state.liveRun,
            )
        }
    }

    fun loadOlderHistory(serverId: String, sessionId: String) {
        val cursor = _uiState.value.nextBeforeOrderIndex ?: return
        if (_uiState.value.loadingOlderHistory) return

        viewModelScope.launch {
            _uiState.update { it.copy(loadingOlderHistory = true, error = null) }
            try {
                val payload = withServerClient(serverId) { handle ->
                    handle.client.getSessionMessages(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                        limit = FOREGROUND_MESSAGE_TAIL_LIMIT,
                        beforeOrderIndex = cursor,
                    )
                }
                _uiState.update { state ->
                    val mergedMessages = mergeSessionMessages(state.messages, payload.messages)
                    applyConversationContinuity(
                        state = state.copy(
                            messages = mergedMessages,
                            hasMoreHistory = payload.hasMore,
                            nextBeforeOrderIndex = payload.nextBeforeOrderIndex,
                            loadingOlderHistory = false,
                        ),
                        messages = mergedMessages,
                        liveRun = state.liveRun,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadingOlderHistory = false,
                        error = userFacingMessage(e, string(R.string.session_detail_error_refresh)),
                    )
                }
            }
        }
    }

    private fun performRepoAction(
        serverId: String,
        sessionId: String,
        action: RepoActionRequest,
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    repoActionBusy = true,
                    repoActionSummary = null,
                    error = null,
                )
            }
            try {
                val response = withServerClient(serverId) { handle ->
                    handle.client.performRepoAction(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        sessionId = sessionId,
                        action = action,
                    )
                }
                applyRepoActionResponse(response)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = userFacingMessage(e, string(R.string.session_detail_error_repo_action)),
                    )
                }
            } finally {
                _uiState.update { it.copy(repoActionBusy = false) }
            }
        }
    }

    private fun applyRepoActionResponse(response: RepoActionResponse) {
        _uiState.update {
            it.copy(
                repoStatus = response.repoStatus,
                repoActionSummary = response.summary,
                error = null,
            )
        }
    }

    private fun maybeDispatchQueuedPrompt(serverId: String, sessionId: String) {
        val snapshot = _uiState.value
        if (queuedDispatchJob?.isActive == true) return
        if (snapshot.sending) return
        if (snapshot.liveRun?.status in activeRunStatuses) return
        val nextQueuedPrompt = snapshot.queuedPrompts.firstOrNull() ?: return

        queuedDispatchJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    queuedPrompts = state.queuedPrompts.filterNot { it.id == nextQueuedPrompt.id },
                )
            }

            val success = submitPrompt(
                serverId = serverId,
                sessionId = sessionId,
                rawPrompt = nextQueuedPrompt.rawPrompt,
                composedPrompt = buildComposedPrompt(
                    nextQueuedPrompt.rawPrompt,
                    nextQueuedPrompt.artifacts,
                ),
                clearComposer = false,
                runtimeControls = RuntimeControls(
                    model = nextQueuedPrompt.model,
                    reasoningEffort = nextQueuedPrompt.reasoningEffort,
                    permissionMode = nextQueuedPrompt.permissionMode,
                ),
            )

            if (!success) {
                _uiState.update { state ->
                    state.copy(
                        queuedPrompts = listOf(nextQueuedPrompt) + state.queuedPrompts,
                    )
                }
            }
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx)
                }
            }
        return null
    }

    private fun defaultFileName(uri: Uri): String =
        uri.lastPathSegment?.let { File(it).name }?.takeIf { it.isNotBlank() }
            ?: "attachment"

    private suspend fun createSession(
        serverId: String,
        cwd: String,
        prompt: String,
        runtimeControls: RuntimeControls? = null,
    ): String? {
        _uiState.update { it.copy(sending = true, error = null) }
        return try {
            val snapshot = _uiState.value
            val effectiveRuntimeControls = runtimeControls ?: currentRuntimeControls(snapshot)
            val composedPrompt = buildComposedPrompt(prompt, snapshot.pendingArtifacts)
            val inlineCreate = runtimeControls == null
            val createdSessionId = if (inlineCreate) {
                withServerClient(serverId) { handle ->
                    handle.client.createSession(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        cwd = cwd,
                        prompt = composedPrompt,
                    ).sessionId
                }
            } else {
                val newSessionId = withServerClient(serverId) { handle ->
                    handle.client.createSession(
                        token = handle.token,
                        hostId = SESSION_HOST_ID,
                        cwd = cwd,
                        prompt = null,
                    ).sessionId
                }
                sendPromptInternal(
                    serverId = serverId,
                    sessionId = newSessionId,
                    composedPrompt = composedPrompt,
                    model = effectiveRuntimeControls.model,
                    reasoningEffort = effectiveRuntimeControls.reasoningEffort,
                    permissionMode = effectiveRuntimeControls.permissionMode,
                )
                newSessionId
            }
            startForegroundMessageSyncIfNeeded(serverId, createdSessionId)
            refreshSessionAndLive(serverId, createdSessionId)
            _uiState.update {
                it.copy(
                    createdSessionId = createdSessionId,
                    lastSubmittedPrompt = prompt.trim(),
                    lastSubmittedPromptWithAttachments = composedPrompt.trim(),
                    currentTurnPromptOverride = prompt.trim(),
                    retainedLiveOutput = null,
                    retainedLiveRunId = null,
                    prompt = "",
                    pendingArtifacts = emptyList(),
                )
            }
            createdSessionId
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    sending = false,
                    error = userFacingMessage(e, string(R.string.session_detail_error_create_session)),
                )
            }
            null
        } finally {
            _uiState.update { it.copy(sending = false) }
        }
    }

    private suspend fun createDraftSessionWithQueuedAttachments(
        serverId: String,
        cwd: String,
        rawPrompt: String,
        localAttachments: List<PendingLocalAttachment>,
        runtimeControls: RuntimeControls? = null,
    ): String? {
        _uiState.update { it.copy(sending = true, uploading = true, error = null) }
        return try {
            val createdSessionId = withServerClient(serverId) { handle ->
                handle.client.createSession(
                    token = handle.token,
                    hostId = SESSION_HOST_ID,
                    cwd = cwd,
                    prompt = null,
                ).sessionId
            }

            val uploadedArtifacts = withServerClient(serverId) { handle ->
                localAttachments.map { attachment ->
                    withContext(Dispatchers.IO) {
                        handle.client.uploadSessionArtifact(
                            token = handle.token,
                            hostId = SESSION_HOST_ID,
                            sessionId = createdSessionId,
                            fileName = attachment.originalName,
                            mimeType = attachment.mimeType,
                            bytes = attachment.bytes,
                        )
                    }
                }
            }

            val audioDurationsMs = buildMap {
                uploadedArtifacts.forEachIndexed { index, artifact ->
                    localAttachments.getOrNull(index)?.durationMs?.let { durationMs ->
                        put(artifact.id, durationMs)
                    }
                }
            }
            val composedPrompt = buildComposedPrompt(
                rawPrompt,
                uploadedArtifacts,
                audioDurationsMs = audioDurationsMs,
            )
            val effectiveRuntimeControls = runtimeControls ?: currentRuntimeControls(_uiState.value)
            sendPromptInternal(
                serverId = serverId,
                sessionId = createdSessionId,
                composedPrompt = composedPrompt,
                model = effectiveRuntimeControls.model,
                reasoningEffort = effectiveRuntimeControls.reasoningEffort,
                permissionMode = effectiveRuntimeControls.permissionMode,
            )
            startForegroundMessageSyncIfNeeded(serverId, createdSessionId)
            refreshSessionAndLive(serverId, createdSessionId)

            _uiState.update {
                it.copy(
                    createdSessionId = createdSessionId,
                    lastSubmittedPrompt = rawPrompt.trim(),
                    lastSubmittedPromptWithAttachments = composedPrompt.trim(),
                    currentTurnPromptOverride = rawPrompt.trim(),
                    retainedLiveOutput = null,
                    retainedLiveRunId = null,
                    prompt = "",
                    pendingArtifacts = emptyList(),
                    pendingLocalAttachments = emptyList(),
                )
            }
            createdSessionId
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    error = userFacingMessage(e, string(R.string.session_detail_error_create_session)),
                )
            }
            null
        } finally {
            _uiState.update { it.copy(sending = false, uploading = false) }
        }
    }

    override fun onCleared() {
        queuedDispatchJob?.cancel()
        stopForegroundMessageSync()
        stopLiveRunStream()
        super.onCleared()
    }

    private fun resolveRuntimeControls(
        snapshot: SessionDetailUiState,
        model: String?,
        reasoningEffort: String?,
        permissionMode: String? = null,
    ): RuntimeControls {
        val normalizedModel = normalizeRuntimeControl(model)
        val normalizedReasoningEffort = normalizeRuntimeControl(reasoningEffort)
        return RuntimeControls(
            model = normalizedModel ?: currentRuntimeControls(snapshot).model,
            reasoningEffort = normalizedReasoningEffort ?: currentRuntimeControls(snapshot).reasoningEffort,
            permissionMode = permissionMode?.let(::normalizePermissionMode)
                ?: currentRuntimeControls(snapshot).permissionMode,
        )
    }

    private fun hasExplicitRuntimeControls(
        snapshot: SessionDetailUiState,
        model: String?,
        reasoningEffort: String?,
        permissionMode: String? = null,
    ): Boolean {
        val controls = resolveRuntimeControls(snapshot, model, reasoningEffort, permissionMode)
        return controls.model != null ||
            controls.reasoningEffort != null ||
            controls.permissionMode != "on-request"
    }

    private fun currentRuntimeControls(snapshot: SessionDetailUiState): RuntimeControls {
        val selectedModel = normalizeRuntimeControl(snapshot.selectedModel)
        val selectedReasoningEffort = normalizeRuntimeControl(snapshot.selectedReasoningEffort)
        val selectedPermissionMode = normalizePermissionMode(snapshot.selectedPermissionMode)
        return if (snapshot.runtimeControlsInitialized) {
            RuntimeControls(
                model = selectedModel,
                reasoningEffort = selectedReasoningEffort,
                permissionMode = selectedPermissionMode,
            )
        } else {
            RuntimeControls(
                model = selectedModel ?: normalizeRuntimeControl(snapshot.liveRun?.model),
                reasoningEffort = selectedReasoningEffort ?: normalizeRuntimeControl(snapshot.liveRun?.reasoningEffort),
                permissionMode = selectedPermissionMode,
            )
        }
    }

    private fun seedRuntimeControlsFromRun(liveRun: Run?) {
        if (liveRun == null) return
        _uiState.update { state ->
            if (state.runtimeControlsInitialized) {
                state
            } else {
                val seededModel = state.selectedModel ?: normalizeRuntimeControl(liveRun.model)
                val seededReasoningEffort = state.selectedReasoningEffort ?: normalizeRuntimeControl(liveRun.reasoningEffort)
                state.copy(
                    selectedModel = seededModel,
                    selectedReasoningEffort = seededReasoningEffort,
                    selectedPermissionMode = normalizePermissionMode(state.selectedPermissionMode),
                    runtimeControlsInitialized = true,
                )
            }
        }
    }

    private fun applyConversationContinuity(
        state: SessionDetailUiState,
        messages: List<SessionMessage> = state.messages,
        liveRun: Run? = state.liveRun,
    ): SessionDetailUiState {
        var next = state
        val livePrompt = sanitizePromptDisplay(liveRun?.prompt)
        val liveOutput = cleanLiveOutput(liveRun?.lastOutput, liveRun?.prompt)

        if (!livePrompt.isNullOrBlank()) {
            next = next.copy(currentTurnPromptOverride = livePrompt)
        }

        if (liveRun != null) {
            next = when {
                !liveOutput.isNullOrBlank() -> next.copy(
                    retainedLiveOutput = liveOutput,
                    retainedLiveRunId = liveRun.id,
                )
                next.retainedLiveRunId != liveRun.id -> next.copy(
                    retainedLiveOutput = null,
                    retainedLiveRunId = liveRun.id,
                )
                else -> next
            }
        }

        if (liveRun?.status in terminalRunStatuses && hasSettledCurrentTurn(messages, next.currentTurnPromptOverride)) {
            next = next.copy(
                currentTurnPromptOverride = null,
                retainedLiveOutput = null,
                retainedLiveRunId = null,
            )
        }

        return next
    }

    private fun startForegroundMessageSyncIfNeeded(serverId: String, sessionId: String) {
        if (_uiState.value.appInBackground) return
        if (foregroundMessageSyncJob?.isActive == true) return

        foregroundMessageSyncJob = viewModelScope.launch {
            while (isActive && !_uiState.value.appInBackground) {
                val liveRun = _uiState.value.liveRun
                if (liveRun?.status !in activeRunStatuses) {
                    if (_uiState.value.sending) {
                        kotlinx.coroutines.delay(450L)
                        continue
                    }
                    break
                }
                try {
                    refreshSessionMessagesTail(serverId, sessionId)
                } catch (_: Exception) {
                    // Best effort only. Live run continuity should not be blocked by message polling blips.
                }
                kotlinx.coroutines.delay(1200L)
            }
            foregroundMessageSyncJob = null
        }
    }

    private fun stopForegroundMessageSync() {
        foregroundMessageSyncJob?.cancel()
        foregroundMessageSyncJob = null
    }

    private fun hasSettledCurrentTurn(
        messages: List<SessionMessage>,
        currentTurnPromptOverride: String?,
    ): Boolean {
        val pendingPrompt = currentTurnPromptOverride?.trim().orEmpty()
        if (pendingPrompt.isBlank()) return false
        val currentTurnMessages = buildCurrentTurnProjection(
            messages = messages,
            liveRun = null,
            pendingTurnPrompt = pendingPrompt,
            cleanedOutput = null,
            retainedLiveOutput = null,
            isDraft = false,
        )
        val latestPrompt = sanitizePromptDisplay(latestCanonicalPrompt(messages))
        if (latestPrompt.isNullOrBlank() || latestPrompt != pendingPrompt) return false
        return currentTurnMessages.settledAssistantMessages.isNotEmpty()
    }

    private fun markRecoverySyncedIfNeeded() {
        val restoring = string(R.string.session_detail_recovery_restoring)
        val degraded = string(R.string.session_detail_recovery_degraded)
        val synced = string(R.string.session_detail_recovery_synced)
        _uiState.update { state ->
            if (!state.appInBackground && (state.recoveryNotice == restoring || state.recoveryNotice == degraded)) {
                state.copy(recoveryNotice = synced)
            } else {
                state
            }
        }
    }

    private fun rememberActiveSession(serverId: String, sessionId: String) {
        if (serverId.isBlank() || sessionId.isBlank()) return
        activeSessionRef = SessionRef(serverId = serverId, sessionId = sessionId)
    }

    private fun startBackgroundMonitorIfNeeded() {
        activeSessionRef ?: return
        if (!_uiState.value.appInBackground) return
        if (_uiState.value.liveRun?.status !in activeRunStatuses) return
        if (backgroundMonitorJob?.isActive == true) return

        backgroundMonitorJob = viewModelScope.launch {
            while (isActive && _uiState.value.appInBackground) {
                val currentRef = activeSessionRef ?: break
                try {
                    val liveRun = fetchLiveRun(currentRef.serverId, currentRef.sessionId)
                    _uiState.update { state ->
                        applyConversationContinuity(
                            state = state.copy(liveRun = liveRun),
                            liveRun = liveRun,
                        )
                    }
                    seedRuntimeControlsFromRun(liveRun)
                    maybeNotifyRunState(currentRef.serverId, currentRef.sessionId, liveRun)
                    maybeNotifyRecovered(currentRef.serverId, currentRef.sessionId, liveRun)
                    if (liveRun?.status in terminalRunStatuses) {
                        silentRefreshSessionAndLive(currentRef.serverId, currentRef.sessionId)
                        maybeDispatchQueuedPrompt(currentRef.serverId, currentRef.sessionId)
                        break
                    }
                } catch (_: Exception) {
                    maybeNotifyBackgroundNeedsAttention(currentRef.serverId, currentRef.sessionId, _uiState.value)
                }
                kotlinx.coroutines.delay(20_000L)
            }
        }
    }

    private suspend fun fetchLiveRun(serverId: String, sessionId: String): Run? =
        withServerClient(serverId) { handle ->
            handle.client.getLiveRun(handle.token, SESSION_HOST_ID, sessionId)
        }

    private fun maybeNotifyRunState(
        serverId: String,
        sessionId: String,
        liveRun: Run?,
    ) {
        val run = liveRun ?: return
        val tier = when (run.status) {
            "completed" -> BackgroundNotificationTier.COMPLETED
            "failed" -> BackgroundNotificationTier.FAILED
            "stopped" -> BackgroundNotificationTier.NEEDS_ATTENTION
            else -> null
        } ?: return
        postBackgroundNotification(
            serverId = serverId,
            sessionId = sessionId,
            run = run,
            tier = tier,
        )
        if (run.status in terminalRunStatuses) {
            backgroundAttentionContextKey = null
        }
    }

    private fun maybeNotifyBackgroundNeedsAttention(
        serverId: String,
        sessionId: String,
        snapshot: SessionDetailUiState,
    ) {
        if (!snapshot.appInBackground) return
        if (!hasVisibleSessionContent(snapshot) &&
            snapshot.liveRun?.status !in activeRunStatuses &&
            !snapshot.liveStreamConnected
        ) {
            return
        }
        val attentionContext = listOf(
            serverId,
            sessionId,
            snapshot.liveRun?.id.orEmpty(),
            snapshot.liveRun?.status.orEmpty(),
            snapshot.liveStreamStatus.orEmpty(),
        ).joinToString("|")
        if (attentionContext == backgroundAttentionContextKey) return
        backgroundAttentionContextKey = attentionContext
        postBackgroundNotification(
            serverId = serverId,
            sessionId = sessionId,
            run = snapshot.liveRun,
            tier = BackgroundNotificationTier.NEEDS_ATTENTION,
            additionalKey = attentionContext,
        )
    }

    private fun maybeNotifyRecovered(
        serverId: String,
        sessionId: String,
        liveRun: Run?,
    ) {
        val run = liveRun
        val attentionContext = backgroundAttentionContextKey ?: return
        val expectedPrefix = "$serverId|$sessionId|"
        if (!attentionContext.startsWith(expectedPrefix)) return
        if (!_uiState.value.appInBackground) {
            backgroundAttentionContextKey = null
            return
        }
        if (run?.status in terminalRunStatuses) {
            backgroundAttentionContextKey = null
            return
        }

        backgroundAttentionContextKey = null
        postBackgroundNotification(
            serverId = serverId,
            sessionId = sessionId,
            run = run,
            tier = BackgroundNotificationTier.RECOVERED,
            additionalKey = attentionContext,
        )
    }

    private fun postBackgroundNotification(
        serverId: String,
        sessionId: String,
        run: Run?,
        tier: BackgroundNotificationTier,
        additionalKey: String? = null,
    ) {
        val appInBackground = _uiState.value.appInBackground
        if (!appInBackground) return

        val notificationKey = listOf(
            serverId,
            sessionId,
            run?.id.orEmpty(),
            tier.statusValue,
            additionalKey.orEmpty(),
        ).joinToString("|")
        if (notificationKey == lastNotifiedBackgroundEventKey) return
        lastNotifiedBackgroundEventKey = notificationKey

        if (tier == BackgroundNotificationTier.NEEDS_ATTENTION) {
            backgroundAttentionContextKey = additionalKey ?: notificationKey
        }

        RunCompletedNotificationHelper(getApplication()).postRunCompleted(
            payload = RunCompletedNotificationPayload(
                serverId = serverId,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                tier = when (tier) {
                    BackgroundNotificationTier.COMPLETED -> RunNotificationTier.COMPLETED
                    BackgroundNotificationTier.FAILED -> RunNotificationTier.FAILED
                    BackgroundNotificationTier.NEEDS_ATTENTION -> RunNotificationTier.NEEDS_ATTENTION
                    BackgroundNotificationTier.RECOVERED -> RunNotificationTier.RECOVERED
                },
                runId = run?.id,
                sessionLabel = _uiState.value.session?.title?.takeIf { it.isNotBlank() },
                terminalStatus = run?.status ?: tier.statusValue,
            ),
            appInBackground = true,
        )
        if (tier == BackgroundNotificationTier.COMPLETED || tier == BackgroundNotificationTier.FAILED) {
            backgroundAttentionContextKey = null
        }
    }
}
