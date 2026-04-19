package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

/** Mirrors the shared Run schema from packages/shared. */
@Serializable
data class Run(
    val id: String,
    val sessionId: String,
    val status: String,
    val prompt: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val startedAt: String,
    val finishedAt: String? = null,
    val lastOutput: String? = null,
    val error: String? = null,
)
