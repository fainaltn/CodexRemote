package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

/** Mirrors the shared Session schema from packages/shared. */
@Serializable
data class Session(
    val id: String,
    val hostId: String,
    val provider: String = "codex",
    val codexSessionId: String? = null,
    val title: String,
    val cwd: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastPreview: String? = null,
    val archivedAt: String? = null,
)

@Serializable
data class ListSessionsResponse(
    val sessions: List<Session>,
)

@Serializable
data class ArchiveSessionsResponse(
    val ok: Boolean,
    val archivedCount: Int,
)

@Serializable
data class UnarchiveSessionsResponse(
    val ok: Boolean,
    val unarchivedCount: Int,
)
