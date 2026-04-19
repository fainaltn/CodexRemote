package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionMessage(
    val id: String,
    val role: String,
    val kind: String = "message",
    val turnId: String? = null,
    val itemId: String? = null,
    val orderIndex: Int = 0,
    val isStreaming: Boolean = false,
    val text: String,
    val createdAt: String,
)

@Serializable
data class SessionDetailResponse(
    val session: Session,
    val messages: List<SessionMessage>,
)

@Serializable
data class SessionSummaryResponse(
    val session: Session,
)

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
    val limit: Int,
    val hasMore: Boolean,
    val nextBeforeOrderIndex: Int? = null,
)

@Serializable
data class Artifact(
    val id: String,
    val sessionId: String,
    val runId: String? = null,
    val kind: String,
    val originalName: String,
    val storedPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: String,
)

@Serializable
data class StartLiveRunRequest(
    val prompt: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val permissionMode: String? = null,
)

@Serializable
data class StartLiveRunResponse(
    val runId: String,
)

@Serializable
data class StopLiveRunResponse(
    val ok: Boolean,
)

@Serializable
data class SessionHydrationResponse(
    val run: Run? = null,
    val approvals: List<PendingApproval> = emptyList(),
    val repoStatus: RepoStatus? = null,
)

@Serializable
data class RepoStatus(
    val isRepo: Boolean? = null,
    val branch: String? = null,
    val rootPath: String? = null,
    val cwd: String? = null,
    val detached: Boolean? = null,
    val aheadBy: Int? = null,
    val behindBy: Int? = null,
    val dirtyCount: Int? = null,
    val stagedCount: Int? = null,
    val unstagedCount: Int? = null,
    val untrackedCount: Int? = null,
)

@Serializable
data class RepoStatusResponse(
    val repoStatus: RepoStatus,
)

@Serializable
data class RepoActionRequest(
    val action: String,
    val branch: String? = null,
    val message: String? = null,
)

@Serializable
data class RepoActionResponse(
    val ok: Boolean,
    val summary: String,
    val repoStatus: RepoStatus,
)

@Serializable
data class RepoLogEntry(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val authoredAt: String,
)

@Serializable
data class RepoLogResponse(
    val entries: List<RepoLogEntry>,
)
