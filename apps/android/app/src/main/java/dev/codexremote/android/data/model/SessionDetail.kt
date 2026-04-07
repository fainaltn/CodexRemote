package dev.codexremote.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionMessage(
    val id: String,
    val role: String,
    val kind: String = "message",
    val text: String,
    val createdAt: String,
)

@Serializable
data class SessionDetailResponse(
    val session: Session,
    val messages: List<SessionMessage>,
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
)

@Serializable
data class StartLiveRunResponse(
    val runId: String,
)

@Serializable
data class StopLiveRunResponse(
    val ok: Boolean,
)
