package dev.codexremote.android.ui.sessions

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexremote.android.data.model.Artifact
import dev.codexremote.android.data.model.Run
import dev.codexremote.android.data.model.Session
import dev.codexremote.android.data.model.SessionDetailResponse
import dev.codexremote.android.data.model.SessionMessage
import dev.codexremote.android.data.network.ApiClient
import dev.codexremote.android.data.network.LiveRunStreamEvent
import dev.codexremote.android.data.repository.ServerRepository
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
    val messages: List<SessionMessage> = emptyList(),
    val liveRun: Run? = null,
    val prompt: String = "",
    val lastSubmittedPrompt: String? = null,
    val lastSubmittedPromptWithAttachments: String? = null,
    val pendingArtifacts: List<Artifact> = emptyList(),
    val pendingLocalAttachments: List<PendingLocalAttachment> = emptyList(),
    val uploading: Boolean = false,
    val sending: Boolean = false,
    val stopping: Boolean = false,
    val error: String? = null,
    val createdSessionId: String? = null,
    val liveStreamConnected: Boolean = false,
    val liveStreamStatus: String? = null,
)

data class PendingLocalAttachment(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

private data class ServerHandle(
    val client: ApiClient,
    val token: String,
)

private fun buildAttachmentPrompt(prompt: String, artifacts: List<Artifact>): String {
    if (artifacts.isEmpty()) return prompt.trim()
    return buildString {
        appendLine("You have access to these uploaded session artifacts on the local filesystem.")
        appendLine("Inspect them directly if relevant before answering.")
        appendLine()
        artifacts.forEachIndexed { index, artifact ->
            appendLine(
                "[Attachment ${index + 1}] ${artifact.originalName} (${artifact.mimeType}) at path: ${artifact.storedPath}",
            )
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
    private var liveStreamKey: Pair<String, String>? = null
    private var liveStreamLastEventId: Long? = null

    private fun userFacingMessage(error: Throwable, fallback: String): String {
        val resolved = ApiClient.describeNetworkFailure(error)
        return if (resolved.isBlank() || resolved == "连接失败，请稍后重试") fallback else resolved
    }

    private suspend fun resolveServer(serverId: String): ServerHandle {
        val servers = repo.servers.first()
        val server = servers.find { it.id == serverId }
            ?: throw IllegalStateException("服务器不存在")
        val token = server.token ?: throw IllegalStateException("尚未登录")
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
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                refreshSessionAndLive(serverId, sessionId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = userFacingMessage(e, "加载会话失败"),
                        session = it.session,
                        messages = it.messages,
                    )
                }
            } finally {
                _uiState.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    fun prepareDraft() {
        stopLiveRunStream()
        _uiState.value = SessionDetailUiState(loading = false)
    }

    fun refresh(serverId: String, sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, error = null) }
            try {
                refreshSessionAndLive(serverId, sessionId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = userFacingMessage(e, "刷新会话失败")) }
            } finally {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun refreshLiveRun(serverId: String, sessionId: String) {
        viewModelScope.launch {
            try {
                val liveRun = withServerClient(serverId) { handle ->
                    handle.client.getLiveRun(handle.token, SESSION_HOST_ID, sessionId)
                }
                _uiState.update { it.copy(liveRun = liveRun) }
            } catch (_: Exception) {
                // Keep the last visible live state when polling blips.
            }
        }
    }

    fun startLiveRunStream(serverId: String, sessionId: String) {
        val nextKey = serverId to sessionId
        if (liveStreamKey == nextKey && liveStreamJob?.isActive == true) return

        stopLiveRunStream(resetState = false)
        liveStreamKey = nextKey
        _uiState.update {
            it.copy(
                liveStreamConnected = false,
                liveStreamStatus = "正在连接原生实时流…",
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
                                            "原生实时流已恢复"
                                        } else {
                                            "原生实时流已连接"
                                        },
                                    )
                                }
                            }
                            is LiveRunStreamEvent.RunSnapshot -> {
                                liveStreamLastEventId = event.eventId ?: liveStreamLastEventId
                                _uiState.update {
                                    it.copy(
                                        liveRun = event.run,
                                        liveStreamConnected = true,
                                        liveStreamStatus = when (event.run?.status) {
                                            "running", "pending" -> "原生实时流推送中"
                                            else -> "原生实时流已连接"
                                        },
                                    )
                                }
                            }
                            is LiveRunStreamEvent.Gap -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = true,
                                        liveStreamStatus = "已补齐中断后的实时流",
                                    )
                                }
                            }
                            is LiveRunStreamEvent.StreamEnd -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = true,
                                        liveStreamStatus = when (event.reason) {
                                            "no-run" -> "已连接，等待下一次运行"
                                            else -> "本轮已结束，保持实时连接"
                                        },
                                    )
                                }
                            }
                            is LiveRunStreamEvent.IdleTimeout -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = false,
                                        liveStreamStatus = "实时流空闲超时，正在恢复…",
                                    )
                                }
                            }
                            is LiveRunStreamEvent.Reconnecting -> {
                                _uiState.update {
                                    it.copy(
                                        liveStreamConnected = false,
                                        liveStreamStatus = event.message,
                                    )
                                }
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
                        liveStreamStatus = "实时流暂不可用，已切换为自动刷新",
                    )
                }
            }
        }
    }

    fun stopLiveRunStream(resetState: Boolean = true) {
        liveStreamJob?.cancel()
        liveStreamJob = null
        liveStreamKey = null
        liveStreamLastEventId = null
        if (resetState) {
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
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
            sendPromptInternal(serverId, sessionId, composedPrompt.trim())
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
                )
            }
            refreshLiveRun(serverId, sessionId)
            return true
        } catch (e: Exception) {
            _uiState.update { it.copy(error = userFacingMessage(e, "启动运行失败")) }
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
                _uiState.update { it.copy(archiving = false, error = userFacingMessage(e, "归档失败")) }
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
                        ?: throw IllegalStateException("无法读取文件")
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
                _uiState.update { it.copy(error = userFacingMessage(e, "上传失败")) }
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
                        ?: throw IllegalStateException("无法读取文件")
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
                _uiState.update { it.copy(error = userFacingMessage(e, "添加附件失败")) }
            } finally {
                _uiState.update { it.copy(uploading = false) }
            }
        }
    }

    fun sendPrompt(
        serverId: String,
        sessionId: String?,
        draftCwd: String? = null,
    ) {
        viewModelScope.launch {
            val snapshot = _uiState.value
            val rawPrompt = snapshot.prompt.trim()
            if (rawPrompt.isBlank() || snapshot.sending || snapshot.liveRun?.status == "running" || snapshot.liveRun?.status == "pending") {
                return@launch
            }

            val resolvedSessionId = sessionId ?: if (draftCwd.isNullOrBlank()) {
                _uiState.update { it.copy(error = "缺少项目目录，无法创建会话") }
                return@launch
            } else {
                if (snapshot.pendingLocalAttachments.isNotEmpty()) {
                    createDraftSessionWithQueuedAttachments(
                        serverId = serverId,
                        cwd = draftCwd,
                        rawPrompt = rawPrompt,
                        localAttachments = snapshot.pendingLocalAttachments,
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
            )
        }
    }

    private suspend fun sendPromptInternal(
        serverId: String,
        sessionId: String,
        composedPrompt: String,
    ) {
        withServerClient(serverId) { handle ->
            handle.client.startLiveRun(
                token = handle.token,
                hostId = SESSION_HOST_ID,
                sessionId = sessionId,
                prompt = composedPrompt,
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
                val session = sessionDeferred.await()
                val liveRun = liveDeferred.await()
                _uiState.update { state ->
                    state.copy(
                        session = session.session,
                        messages = session.messages,
                        liveRun = liveRun,
                    )
                }
                session
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
    ): String? {
        _uiState.update { it.copy(sending = true, error = null) }
        return try {
            val createdSessionId = withServerClient(serverId) { handle ->
                handle.client.createSession(
                    token = handle.token,
                    hostId = SESSION_HOST_ID,
                    cwd = cwd,
                    prompt = prompt,
                ).sessionId
            }
            refreshSessionAndLive(serverId, createdSessionId)
            _uiState.update {
                it.copy(
                    createdSessionId = createdSessionId,
                    lastSubmittedPrompt = prompt.trim(),
                    lastSubmittedPromptWithAttachments = prompt.trim(),
                    prompt = "",
                    pendingArtifacts = emptyList(),
                )
            }
            createdSessionId
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    sending = false,
                    error = userFacingMessage(e, "创建会话失败"),
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
            sendPromptInternal(serverId, createdSessionId, composedPrompt)
            refreshSessionAndLive(serverId, createdSessionId)

            _uiState.update {
                it.copy(
                    createdSessionId = createdSessionId,
                    lastSubmittedPrompt = rawPrompt.trim(),
                    lastSubmittedPromptWithAttachments = composedPrompt.trim(),
                    prompt = "",
                    pendingArtifacts = emptyList(),
                    pendingLocalAttachments = emptyList(),
                )
            }
            createdSessionId
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    error = userFacingMessage(e, "创建会话失败"),
                )
            }
            null
        } finally {
            _uiState.update { it.copy(sending = false, uploading = false) }
        }
    }

    override fun onCleared() {
        stopLiveRunStream()
        super.onCleared()
    }
}
