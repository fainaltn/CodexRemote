package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class InboxItem(
    val id: String,
    val hostId: String,
    val kind: String,
    val status: String,
    val url: String? = null,
    val title: String? = null,
    val originalName: String? = null,
    val note: String? = null,
    val source: String? = null,
    val storedPath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val createdAt: String,
    val submissionPath: String? = null,
    val submissionId: String? = null,
    val stagingDir: String? = null,
    val contract: String? = null,
    val captureSessions: List<JsonObject> = emptyList(),
    val retryAttempts: List<JsonObject> = emptyList(),
    val retryPolicy: JsonObject? = null,
    val hasReviewBundle: Boolean = false,
    val hasSkillRunbook: Boolean = false,
)

@Serializable
data class ListInboxResponse(
    val items: List<InboxItem>,
)

@Serializable
data class SubmitInboxLinkRequest(
    val url: String,
    val title: String? = null,
    val note: String? = null,
    val source: String? = null,
)
