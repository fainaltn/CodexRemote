package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val relativePath: String,
    val kind: String,
)

@Serializable
data class ListFilesResponse(
    val rootPath: String,
    val currentPath: String,
    val parentPath: String? = null,
    val entries: List<FileEntry>,
)

@Serializable
data class SearchFilesResponse(
    val rootPath: String,
    val currentPath: String,
    val parentPath: String? = null,
    val query: String,
    val limit: Int,
    val results: List<FileEntry>,
)

@Serializable
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val sourceRoot: String,
    val skillPath: String,
    val definitionPath: String,
    val relativePath: String,
)

@Serializable
data class ListSkillsResponse(
    val skills: List<SkillEntry>,
)
