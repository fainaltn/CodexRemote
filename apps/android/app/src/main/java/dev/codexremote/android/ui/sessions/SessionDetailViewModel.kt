package dev.codexremote.android.ui.sessions

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Artifact
import dev.codexremote.android.data.model.RepoActionRequest
import dev.codexremote.android.data.model.RepoActionResponse
import dev.codexremote.android.data.model.RepoLogEntry
import dev.codexremote.android.data.model.Run
import dev.codexremote.android.data.model.RepoStatus
import dev.codexremote.android.data.model.Session
import dev.codexremote.android.data.model.SessionDetailResponse
import dev.codexremote.android.data.model.SessionMessage
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.network.LiveRunStreamEvent
import dev.codexremote.android.data.repository.ServerRepository
import dev.codexremote.android.notifications.RunCompletedNotificationHelper
import dev.codexremote.android.notifications.RunNotificationTier
import dev.codexremote.android.notifications.RunCompletedNotificationPayload
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

data class SessionDetailUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val archiving: Boolean = false,
    val archived: Boolean = false,
    val session: Session? = null,
    val repoStatus: RepoStatus? = null,
    val messages: List<SessionMessage> = emptyList(),
    val liveRun: Run? = null,
    val prompt: String = "",
    val lastSubmittedPrompt: String? = null,
    val lastSubmittedPromptWithAttachments: String? = null,
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    val runtimeControlsInitialized: Boolean = false,
    val pendingArtifacts: List<Artifact> = emptyList(),
    val pendingLocalAttachments: List<PendingLocalAttachment> = emptyList(),
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val uploading: Boolean = false,
    val sending: Boolean = false,
    val stopping: Boolean = false,
    val repoActionBusy: Boolean = false,
    val repoActionSummary: String? = null,
    val repoLogEntries: List<RepoLogEntry> = emptyList(),
    val repoLogLoading: Boolean = false,
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
)

private data class RuntimeControls(
    val model: String? = null,
    val reasoningEffort: String? = null,
)

data class QueuedPrompt(
    val id: String,
    val rawPrompt: String,
    val artifacts: List<Artifact> = emptyList(),
    val model: String? = null,
    val reasoningEffort: String? = null,
)

private data class ServerHandle(
    val client: ApiClient,
    val token: String,
)

private data class SessionRef(
    val serverId: String,
    val sessionId: String,
)

private fun buildAttachmentPrompt(prompt: String, artifacts: List<Artifact>): String {
    if (artifacts.isEmpty()) return prompt.trim()
    return buildString {
        appendLine("You have access to these uploaded session artifacts on the local filesystem.")
        appendLine("Use the exact absolute file paths below directly before answering.")
        appendLine("Do not search the workspace for alternate copies unless a listed path is missing.")
        appendLine()
        artifacts.forEachIndexed { index, artifact ->
            appendLine(
                "[Attachment ${index + 1}] id=${artifact.id} ${artifact.originalName} (${artifact.mimeType})",
            )
            appendLine("Absolute path: ${artifact.storedPath}")
        }
        appendLine()
        append("User request: ${prompt.trim()}")
    }.trim()
}

private fun sanitizePromptDisplay(prompt: String?): String? {
    val trimmed = prompt?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("You have access to these uploaded session artifacts")) {
        val marker = "User request:"
        val idx = trimmed.indexOf(marker)
        if (idx != -1) {
            return trimmed.substring(idx + marker.length).trim().ifBlank { null }
        }
    }
    return trimmed
}

private fun normalizeRuntimeControl(value: String?): String? =
    value?.trim()?.takeIf { it.isNotBlank() }

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

    fun load(serverId: String, sessionId: String) {
        rememberActiveSession(serverId, sessionId)
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, recoveryNotice = null) }
            try {
                refreshSessionAndLive(serverId, sessionId)
            } catch (e: Exception) {
                val existingState = _uiState.value
                if (
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
                _uiState.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    fun prepareDraft() {
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
            selectedModel = snapshot.selectedModel,
            selectedReasoningEffort = snapshot.selectedReasoningEffort,
            runtimeControlsInitialized = snapshot.runtimeControlsInitialized,
        )
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
                refreshSessionAndLive(serverId, sessionId)
            } catch (_: Exception) {
                // Best-effort; the page already has streaming data.
            }
        }
    }

    fun refreshLiveRun(serverId: String, sessionId: String) {
        rememberActiveSession(serverId, sessionId)
        viewModelScope.launch {
            try {
                val liveRun = fetchLiveRun(serverId, sessionId)
                _uiState.update { state ->
                    applyConversationContinuity(
                        state = state.copy(liveRun = liveRun),
                        liveRun = liveRun,
                    )
                }
                seedRuntimeControlsFromRun(liveRun)
                maybeNotifyRunState(serverId, sessionId, liveRun)
                maybeNotifyRecovered(serverId, sessionId, liveRun)
                markRecoverySyncedIfNeeded()
                startBackgroundMonitorIfNeeded()
            } catch (_: Exception) {
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

    fun setRuntimeControls(
        model: String?,
        reasoningEffort: String?,
    ) {
        _uiState.update { state ->
            state.copy(
                selectedModel = normalizeRuntimeControl(model),
                selectedReasoningEffort = normalizeRuntimeControl(reasoningEffort),
                runtimeControlsInitialized = true,
            )
        }
    }

    fun queuePrompt(
        sessionId: String?,
        model: String? = null,
        reasoningEffort: String? = null,
    ) {
        val snapshot = _uiState.value
        val runtimeControls = resolveRuntimeControls(
            snapshot = snapshot,
            model = model,
            reasoningEffort = reasoningEffort,
        )
        if (model != null || reasoningEffort != null) {
            _uiState.update { state ->
                state.copy(
                    selectedModel = runtimeControls.model,
                    selectedReasoningEffort = runtimeControls.reasoningEffort,
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
                ?: buildAttachmentPrompt(
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

            val composedPrompt = buildAttachmentPrompt(editedPrompt, artifacts)
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
    ) {
        viewModelScope.launch {
            val snapshot = _uiState.value
            val runtimeControls = resolveRuntimeControls(
                snapshot = snapshot,
                model = model,
                reasoningEffort = reasoningEffort,
            )
            if (model != null || reasoningEffort != null) {
                _uiState.update { state ->
                    state.copy(
                        selectedModel = runtimeControls.model,
                        selectedReasoningEffort = runtimeControls.reasoningEffort,
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
                } else if (hasExplicitRuntimeControls(snapshot, model, reasoningEffort)) {
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
                composedPrompt = buildAttachmentPrompt(rawPrompt, snapshot.pendingArtifacts),
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
    ) {
        withServerClient(serverId) { handle ->
            handle.client.startLiveRun(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                prompt = composedPrompt,
                model = model,
                reasoningEffort = reasoningEffort,
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
                val repoDeferred = async(Dispatchers.IO) {
                    runCatching {
                        handle.client.getRepoStatus(handle.token, SESSION_HOST_ID, sessionId).repoStatus
                    }.getOrNull()
                }
                val session = sessionDeferred.await()
                val liveRun = liveDeferred.await()
                val repoStatus = repoDeferred.await()
                _uiState.update { state ->
                    applyConversationContinuity(
                        state = state.copy(
                            session = session.session,
                            repoStatus = repoStatus,
                            messages = session.messages,
                            liveRun = liveRun,
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
                composedPrompt = buildAttachmentPrompt(
                    nextQueuedPrompt.rawPrompt,
                    nextQueuedPrompt.artifacts,
                ),
                clearComposer = false,
                runtimeControls = RuntimeControls(
                    model = nextQueuedPrompt.model,
                    reasoningEffort = nextQueuedPrompt.reasoningEffort,
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
            val composedPrompt = buildAttachmentPrompt(prompt, snapshot.pendingArtifacts)
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

            val composedPrompt = buildAttachmentPrompt(rawPrompt, uploadedArtifacts)
            val effectiveRuntimeControls = runtimeControls ?: currentRuntimeControls(_uiState.value)
            sendPromptInternal(
                serverId = serverId,
                sessionId = createdSessionId,
                composedPrompt = composedPrompt,
                model = effectiveRuntimeControls.model,
                reasoningEffort = effectiveRuntimeControls.reasoningEffort,
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
    ): RuntimeControls {
        val normalizedModel = normalizeRuntimeControl(model)
        val normalizedReasoningEffort = normalizeRuntimeControl(reasoningEffort)
        return RuntimeControls(
            model = normalizedModel ?: currentRuntimeControls(snapshot).model,
            reasoningEffort = normalizedReasoningEffort ?: currentRuntimeControls(snapshot).reasoningEffort,
        )
    }

    private fun hasExplicitRuntimeControls(
        snapshot: SessionDetailUiState,
        model: String?,
        reasoningEffort: String?,
    ): Boolean {
        val controls = resolveRuntimeControls(snapshot, model, reasoningEffort)
        return controls.model != null || controls.reasoningEffort != null
    }

    private fun currentRuntimeControls(snapshot: SessionDetailUiState): RuntimeControls {
        val selectedModel = normalizeRuntimeControl(snapshot.selectedModel)
        val selectedReasoningEffort = normalizeRuntimeControl(snapshot.selectedReasoningEffort)
        return if (snapshot.runtimeControlsInitialized) {
            RuntimeControls(
                model = selectedModel,
                reasoningEffort = selectedReasoningEffort,
            )
        } else {
            RuntimeControls(
                model = selectedModel ?: normalizeRuntimeControl(snapshot.liveRun?.model),
                reasoningEffort = selectedReasoningEffort ?: normalizeRuntimeControl(snapshot.liveRun?.reasoningEffort),
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
                    val detail = withServerClient(serverId) { handle ->
                        handle.client.getSessionDetail(handle.token, SESSION_HOST_ID, sessionId)
                    }
                    _uiState.update { state ->
                        applyConversationContinuity(
                            state = state.copy(
                                session = detail.session,
                                messages = detail.messages,
                            ),
                            messages = detail.messages,
                            liveRun = state.liveRun,
                        )
                    }
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
