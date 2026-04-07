package dev.codexremote.android.data.network

import dev.codexremote.android.data.model.ListSessionsResponse
import dev.codexremote.android.data.model.ArchiveSessionsResponse
import dev.codexremote.android.data.model.Artifact
import dev.codexremote.android.data.model.SessionDetailResponse
import dev.codexremote.android.data.model.ListInboxResponse
import dev.codexremote.android.data.model.LoginRequest
import dev.codexremote.android.data.model.LoginResponse
import dev.codexremote.android.data.model.InboxItem
import dev.codexremote.android.data.model.BrowseProjectsResponse
import dev.codexremote.android.data.model.CreateSessionRequest
import dev.codexremote.android.data.model.CreateSessionResponse
import dev.codexremote.android.data.model.StartLiveRunRequest
import dev.codexremote.android.data.model.StartLiveRunResponse
import dev.codexremote.android.data.model.StopLiveRunResponse
import dev.codexremote.android.data.model.SubmitInboxLinkRequest
import dev.codexremote.android.data.model.Run
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lightweight HTTP client for the CodexRemote backend.
 *
 * Phase 0 keeps this intentionally simple: one function per API endpoint,
 * no caching, no retry. The [baseUrl] points at the Fastify server
 * (e.g. "http://100.x.y.z:3000").
 */
class ApiClient(private val baseUrl: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private suspend inline fun <reified T> decodeResponse(response: HttpResponse): T {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) {
                throw UnauthorizedException(extractErrorMessage(body, response.status.value))
            }
            throw IllegalStateException(extractErrorMessage(body, response.status.value))
        }
        return json.decodeFromString(body)
    }

    private fun extractErrorMessage(body: String, statusCode: Int): String {
        val fallback = "请求失败（HTTP $statusCode）"
        return runCatching {
            val element = json.parseToJsonElement(body)
            element.jsonObject["error"]?.jsonPrimitive?.content
                ?: element.jsonObject["message"]?.jsonPrimitive?.content
                ?: fallback
        }.getOrElse {
            body.ifBlank { fallback }
        }
    }

    // ── Auth ────────────────────────────────────────────────────────────

    suspend fun login(password: String, deviceLabel: String? = "android"): LoginResponse {
        return http.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = password, deviceLabel = deviceLabel))
        }.body()
    }

    suspend fun checkHealth(): Boolean {
        return try {
            val response = http.get("$baseUrl/api/health")
            response.status.value == 200
        } catch (_: Exception) {
            false
        }
    }

    // ── Sessions ────────────────────────────────────────────────────────

    suspend fun listSessions(token: String, hostId: String): ListSessionsResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun getSessionDetail(
        token: String,
        hostId: String,
        sessionId: String,
    ): SessionDetailResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun getLiveRun(
        token: String,
        hostId: String,
        sessionId: String,
    ): Run? {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun browseProjects(
        token: String,
        hostId: String,
        path: String? = null,
    ): BrowseProjectsResponse {
        val suffix = if (path.isNullOrBlank()) "" else "?path=${encode(path)}"
        val response = http.get("$baseUrl/api/hosts/$hostId/projects/browse$suffix") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun createSession(
        token: String,
        hostId: String,
        cwd: String,
        prompt: String? = null,
    ): CreateSessionResponse {
        val response = http.post("$baseUrl/api/hosts/$hostId/sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(cwd = cwd, prompt = prompt))
        }
        return decodeResponse(response)
    }

    suspend fun renameSession(
        token: String,
        hostId: String,
        sessionId: String,
        title: String,
    ): Boolean {
        http.patch("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/title") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }.body<Map<String, Boolean>>()
        return true
    }

    suspend fun archiveSessions(
        token: String,
        hostId: String,
        sessionIds: List<String>,
    ): Boolean {
        http.post("$baseUrl/api/hosts/$hostId/sessions/archive") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("sessionIds" to sessionIds))
        }.body<ArchiveSessionsResponse>()
        return true
    }

    suspend fun archiveSession(
        token: String,
        hostId: String,
        sessionId: String,
    ): Boolean {
        return archiveSessions(token, hostId, listOf(sessionId))
    }

    suspend fun startLiveRun(
        token: String,
        hostId: String,
        sessionId: String,
        prompt: String,
    ): StartLiveRunResponse {
        return http.post("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(StartLiveRunRequest(prompt = prompt))
        }.body()
    }

    suspend fun stopLiveRun(
        token: String,
        hostId: String,
        sessionId: String,
    ): StopLiveRunResponse {
        return http.post("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live/stop") {
            bearerAuth(token)
        }.body()
    }

    // ── Inbox ───────────────────────────────────────────────────────────

    suspend fun listInbox(token: String, hostId: String): ListInboxResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/inbox") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun submitInboxLink(
        token: String,
        hostId: String,
        url: String,
        title: String? = null,
        note: String? = null,
        source: String? = null,
    ): InboxItem {
        val response = http.post("$baseUrl/api/hosts/$hostId/inbox/link") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                SubmitInboxLinkRequest(
                    url = url,
                    title = title,
                    note = note,
                    source = source,
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun uploadInboxFile(
        token: String,
        hostId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        note: String? = null,
        source: String? = null,
    ): InboxItem {
        val response = http.post("$baseUrl/api/hosts/$hostId/inbox/file") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        if (!note.isNullOrBlank()) append("note", note)
                        if (!source.isNullOrBlank()) append("source", source)
                        append(
                            key = "file",
                            value = bytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "form-data; name=\"file\"; filename=\"$fileName\"",
                                )
                            },
                        )
                    }
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun uploadInboxFiles(
        token: String,
        hostId: String,
        files: List<Triple<String, String, ByteArray>>,
        note: String? = null,
        source: String? = null,
    ): InboxItem {
        val response = http.post("$baseUrl/api/hosts/$hostId/inbox/files") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        if (!note.isNullOrBlank()) append("note", note)
                        if (!source.isNullOrBlank()) append("source", source)
                        files.forEach { (fileName, mimeType, bytes) ->
                            append(
                                key = "file",
                                value = bytes,
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, mimeType)
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "form-data; name=\"file\"; filename=\"$fileName\"",
                                    )
                                },
                            )
                        }
                    }
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun uploadInboxSubmissionBundle(
        token: String,
        hostId: String,
        files: List<Pair<String, ByteArray>>,
    ): InboxItem {
        val response = http.post("$baseUrl/api/hosts/$hostId/inbox/submission") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        files.forEach { (relativePath, bytes) ->
                            append(
                                key = "file:${encode(relativePath)}",
                                value = bytes,
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "form-data; name=\"file:${encode(relativePath)}\"; filename=\"${relativePath.substringAfterLast('/')}\"",
                                    )
                                },
                            )
                        }
                    }
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun uploadSessionArtifact(
        token: String,
        hostId: String,
        sessionId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Artifact {
        return http.post("$baseUrl/api/hosts/$hostId/uploads") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("sessionId", sessionId)
                        append(
                            key = "file",
                            value = bytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "form-data; name=\"file\"; filename=\"$fileName\"",
                                )
                            },
                        )
                    }
                )
            )
        }.body()
    }

    fun close() {
        http.close()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

class UnauthorizedException(
    override val message: String = "登录已失效，请重新登录",
) : IllegalStateException(message)
