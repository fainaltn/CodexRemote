package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectDirectory(
    val name: String,
    val path: String,
)

@Serializable
data class BrowseProjectsResponse(
    val rootPath: String,
    val currentPath: String,
    val parentPath: String? = null,
    val entries: List<ProjectDirectory>,
)

@Serializable
data class CreateSessionRequest(
    val cwd: String,
    val prompt: String? = null,
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val runId: String,
)
