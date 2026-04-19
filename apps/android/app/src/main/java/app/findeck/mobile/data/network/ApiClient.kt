package app.findeck.mobile.data.network

import app.findeck.mobile.data.model.ArchiveSessionsResponse
import app.findeck.mobile.data.model.Artifact
import app.findeck.mobile.data.model.BrowseProjectsResponse
import app.findeck.mobile.data.model.ChangePasswordRequest
import app.findeck.mobile.data.model.ChangePasswordResponse
import app.findeck.mobile.data.model.CreateSessionRequest
import app.findeck.mobile.data.model.CreateSessionResponse
import app.findeck.mobile.data.model.InboxItem
import app.findeck.mobile.data.model.ListFilesResponse
import app.findeck.mobile.data.model.ListInboxResponse
import app.findeck.mobile.data.model.ListPendingApprovalsResponse
import app.findeck.mobile.data.model.ListSessionsResponse
import app.findeck.mobile.data.model.ListSkillsResponse
import app.findeck.mobile.data.model.LoginRequest
import app.findeck.mobile.data.model.LoginResponse
import app.findeck.mobile.data.model.PairingClaimRequest
import app.findeck.mobile.data.model.PairingClaimResponse
import app.findeck.mobile.data.model.PendingApproval
import app.findeck.mobile.data.model.PendingApprovalDecisionRequest
import app.findeck.mobile.data.model.PendingApprovalDecisionResponse
import app.findeck.mobile.data.model.RepoActionRequest
import app.findeck.mobile.data.model.RepoActionResponse
import app.findeck.mobile.data.model.RepoLogResponse
import app.findeck.mobile.data.model.RepoStatusResponse
import app.findeck.mobile.data.model.SearchFilesResponse
import app.findeck.mobile.data.model.RuntimeCatalogResponse
import app.findeck.mobile.data.model.RuntimeUsageResponse
import app.findeck.mobile.data.model.Run
import app.findeck.mobile.data.model.SessionDetailResponse
import app.findeck.mobile.data.model.SessionHydrationResponse
import app.findeck.mobile.data.model.SessionSummaryResponse
import app.findeck.mobile.data.model.SessionMessagesResponse
import app.findeck.mobile.data.model.StartLiveRunRequest
import app.findeck.mobile.data.model.StartLiveRunResponse
import app.findeck.mobile.data.model.StopLiveRunResponse
import app.findeck.mobile.data.model.SubmitInboxLinkRequest
import app.findeck.mobile.data.model.TrustedReconnectRequest
import app.findeck.mobile.data.model.TrustedReconnectResponse
import app.findeck.mobile.data.model.UnarchiveSessionsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Lightweight HTTP client for the findeck backend.
 *
 * Phase 0 keeps this intentionally simple: one function per API endpoint,
 * no caching, no retry. The [baseUrl] points at the Fastify server
 * (e.g. "http://100.x.y.z:3000").
 */
data class ConnectionCheckResult(
    val ok: Boolean,
    val normalizedBaseUrl: String,
    val summary: String,
    val detail: String? = null,
    val degraded: Boolean = false,
)

data class DownloadedFilePayload(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

sealed interface LiveRunStreamEvent {
    data class Connected(
        val resumedFromEventId: Long?,
    ) : LiveRunStreamEvent

    data class RunSnapshot(
        val run: Run?,
        val eventId: Long?,
    ) : LiveRunStreamEvent

    data class Gap(
        val missedFrom: Long,
        val currentSeq: Long,
    ) : LiveRunStreamEvent

    data class StreamEnd(
        val reason: String,
    ) : LiveRunStreamEvent

    data class IdleTimeout(
        val timeoutMs: Long,
    ) : LiveRunStreamEvent

    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val message: String,
    ) : LiveRunStreamEvent

    data class ApprovalUpdate(
        val approval: PendingApproval,
        val eventId: Long?,
    ) : LiveRunStreamEvent
}

class ApiClient(baseUrl: String) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
        }
    }

    private suspend inline fun <reified T> decodeResponse(response: HttpResponse): T {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) {
                throw UnauthorizedException(extractErrorMessage(body, response.status.value))
            }
            if (response.status.value == 429) {
                val retryAfter = response.headers["retry-after"]?.toIntOrNull()
                throw RateLimitException(
                    message = extractErrorMessage(body, response.status.value),
                    retryAfterSec = retryAfter,
                )
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
        val response = http.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(password = password, deviceLabel = deviceLabel))
        }
        return decodeResponse(response)
    }

    suspend fun claimPairingCode(
        pairingCode: String,
        deviceLabel: String? = "android",
    ): PairingClaimResponse {
        val response = http.post("$baseUrl/api/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(
                PairingClaimRequest(
                    code = pairingCode,
                    deviceLabel = deviceLabel,
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun reconnectTrustedClient(
        clientId: String,
        clientSecret: String,
        deviceLabel: String? = "android",
    ): TrustedReconnectResponse {
        val response = http.post("$baseUrl/api/auth/reconnect") {
            contentType(ContentType.Application.Json)
            setBody(
                TrustedReconnectRequest(
                    clientId = clientId,
                    clientSecret = clientSecret,
                    deviceLabel = deviceLabel,
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
    ): ChangePasswordResponse {
        val response = http.post("$baseUrl/api/auth/password") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                )
            )
        }
        return decodeResponse(response)
    }

    suspend fun validateConnection(): ConnectionCheckResult {
        return try {
            val response = http.get("$baseUrl/api/health")
            val body = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val summary = when (response.status.value) {
                    404 -> "当前地址可访问，但它不是 findeck API"
                    401, 403 -> "API 地址可访问，但当前请求被拒绝"
                    else -> extractErrorMessage(body, response.status.value)
                }
                val detail = when (response.status.value) {
                    404 -> "请确认填写的是后端 API 地址（默认 31807），而不是 Web 地址。"
                    else -> null
                }
                return ConnectionCheckResult(
                    ok = false,
                    normalizedBaseUrl = baseUrl,
                    summary = summary,
                    detail = detail,
                )
            }

            val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val status = payload?.get("status")?.jsonPrimitive?.contentOrNull
            val checks = payload?.get("checks")?.let { element ->
                runCatching { element.jsonObject }.getOrNull()
            }
            val degraded = status == "degraded"
            ConnectionCheckResult(
                ok = true,
                normalizedBaseUrl = baseUrl,
                summary = if (degraded) "API 可连接，但服务当前处于 degraded 状态" else "已连接到 findeck API",
                detail = checks?.let(::summarizeHealthChecks),
                degraded = degraded,
            )
        } catch (error: Exception) {
            ConnectionCheckResult(
                ok = false,
                normalizedBaseUrl = baseUrl,
                summary = describeNetworkFailure(error),
            )
        }
    }

    suspend fun checkHealth(): Boolean = validateConnection().ok

    // ── Sessions ────────────────────────────────────────────────────────

    suspend fun listSessions(token: String, hostId: String): ListSessionsResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun listArchivedSessions(token: String, hostId: String): ListSessionsResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/archived") {
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

    suspend fun getSessionSummary(
        token: String,
        hostId: String,
        sessionId: String,
    ): SessionSummaryResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/summary") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun getSessionMessages(
        token: String,
        hostId: String,
        sessionId: String,
        limit: Int? = null,
        beforeOrderIndex: Int? = null,
    ): SessionMessagesResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/messages") {
            bearerAuth(token)
            limit?.let { parameter("limit", it) }
            beforeOrderIndex?.let { parameter("beforeOrderIndex", it) }
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

    suspend fun getSessionHydration(
        token: String,
        hostId: String,
        sessionId: String,
    ): SessionHydrationResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live/hydration") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun getRepoStatus(
        token: String,
        hostId: String,
        sessionId: String,
    ): RepoStatusResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/repo-status") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun performRepoAction(
        token: String,
        hostId: String,
        sessionId: String,
        action: RepoActionRequest,
    ): RepoActionResponse {
        val response = http.post("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/repo-action") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(action)
        }
        return decodeResponse(response)
    }

    suspend fun getRepoLog(
        token: String,
        hostId: String,
        sessionId: String,
    ): RepoLogResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/repo-log") {
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

    suspend fun listFiles(
        token: String,
        hostId: String,
        sessionId: String? = null,
        cwd: String? = null,
        path: String? = null,
    ): ListFilesResponse {
        val queryParams = buildList {
            sessionId?.takeIf { it.isNotBlank() }?.let { add("sessionId=${encode(it)}") }
            cwd?.takeIf { it.isNotBlank() }?.let { add("cwd=${encode(it)}") }
            path?.takeIf { it.isNotBlank() }?.let { add("path=${encode(it)}") }
        }.joinToString("&")
        val suffix = if (queryParams.isBlank()) "" else "?$queryParams"
        val response = http.get("$baseUrl/api/hosts/$hostId/files$suffix") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun searchFiles(
        token: String,
        hostId: String,
        query: String,
        sessionId: String? = null,
        cwd: String? = null,
        path: String? = null,
        limit: Int = 12,
    ): SearchFilesResponse {
        val queryParams = buildList {
            sessionId?.takeIf { it.isNotBlank() }?.let { add("sessionId=${encode(it)}") }
            cwd?.takeIf { it.isNotBlank() }?.let { add("cwd=${encode(it)}") }
            path?.takeIf { it.isNotBlank() }?.let { add("path=${encode(it)}") }
            add("query=${encode(query)}")
            add("limit=$limit")
        }.joinToString("&")
        val response = http.get("$baseUrl/api/hosts/$hostId/files/search?$queryParams") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun listSkills(
        token: String,
        source: String? = null,
    ): ListSkillsResponse {
        val suffix = source?.takeIf { it.isNotBlank() }?.let { "?source=${encode(it)}" } ?: ""
        val response = http.get("$baseUrl/api/skills$suffix") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun getRuntimeCatalog(
        token: String,
        hostId: String,
    ): RuntimeCatalogResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/runtime/catalog") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun getRuntimeUsage(
        token: String,
        hostId: String,
    ): RuntimeUsageResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/runtime/usage") {
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

    suspend fun unarchiveSessions(
        token: String,
        hostId: String,
        sessionIds: List<String>,
    ): Boolean {
        http.post("$baseUrl/api/hosts/$hostId/sessions/unarchive") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("sessionIds" to sessionIds))
        }.body<UnarchiveSessionsResponse>()
        return true
    }

    suspend fun unarchiveSession(
        token: String,
        hostId: String,
        sessionId: String,
    ): Boolean {
        return unarchiveSessions(token, hostId, listOf(sessionId))
    }

    suspend fun startLiveRun(
        token: String,
        hostId: String,
        sessionId: String,
        prompt: String,
        model: String? = null,
        reasoningEffort: String? = null,
        permissionMode: String? = null,
    ): StartLiveRunResponse {
        return http.post("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                StartLiveRunRequest(
                    prompt = prompt,
                    model = model?.takeIf { it.isNotBlank() },
                    reasoningEffort = reasoningEffort?.takeIf { it.isNotBlank() },
                    permissionMode = permissionMode?.takeIf { it.isNotBlank() },
                )
            )
        }.body()
    }

    suspend fun listPendingApprovals(
        token: String,
        hostId: String,
        sessionId: String,
    ): ListPendingApprovalsResponse {
        val response = http.get("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live/approvals") {
            bearerAuth(token)
        }
        return decodeResponse(response)
    }

    suspend fun downloadSessionFile(
        token: String,
        hostId: String,
        sessionId: String,
        relativePath: String,
    ): DownloadedFilePayload {
        val response = http.get("$baseUrl/api/hosts/$hostId/files/download") {
            bearerAuth(token)
            url {
                parameters.append("source", "cwd")
                parameters.append("sessionId", sessionId)
                parameters.append("path", relativePath)
            }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                extractErrorMessage(response.bodyAsText(), response.status.value),
            )
        }
        val bytes = response.body<ByteArray>()
        val fileName = response.headers["X-Codex-File-Name"] ?: relativePath.substringAfterLast('/')
        val mimeType = response.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
        val sizeBytes = response.headers["X-Codex-File-Size-Bytes"]?.toLongOrNull()
            ?: bytes.size.toLong()
        return DownloadedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            bytes = bytes,
        )
    }

    suspend fun downloadAbsoluteFile(
        token: String,
        hostId: String,
        sessionId: String,
        absolutePath: String,
    ): DownloadedFilePayload {
        val response = http.get("$baseUrl/api/hosts/$hostId/files/download") {
            bearerAuth(token)
            url {
                parameters.append("source", "absolute")
                parameters.append("sessionId", sessionId)
                parameters.append("path", absolutePath)
            }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                extractErrorMessage(response.bodyAsText(), response.status.value),
            )
        }
        val bytes = response.body<ByteArray>()
        val fileName = response.headers["X-Codex-File-Name"] ?: absolutePath.substringAfterLast('/')
        val mimeType = response.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
        val sizeBytes = response.headers["X-Codex-File-Size-Bytes"]?.toLongOrNull()
            ?: bytes.size.toLong()
        return DownloadedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            bytes = bytes,
        )
    }

    suspend fun downloadArtifactFile(
        token: String,
        hostId: String,
        sessionId: String,
        artifactId: String,
    ): DownloadedFilePayload {
        val response = http.get("$baseUrl/api/hosts/$hostId/files/download") {
            bearerAuth(token)
            url {
                parameters.append("source", "artifact")
                parameters.append("sessionId", sessionId)
                parameters.append("artifactId", artifactId)
            }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                extractErrorMessage(response.bodyAsText(), response.status.value),
            )
        }
        val bytes = response.body<ByteArray>()
        val fileName = response.headers["X-Codex-File-Name"] ?: artifactId
        val mimeType = response.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
        val sizeBytes = response.headers["X-Codex-File-Size-Bytes"]?.toLongOrNull()
            ?: bytes.size.toLong()
        return DownloadedFilePayload(
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            bytes = bytes,
        )
    }

    suspend fun decidePendingApproval(
        token: String,
        hostId: String,
        sessionId: String,
        approvalId: String,
        request: PendingApprovalDecisionRequest,
    ): PendingApprovalDecisionResponse {
        val response = http.post(
            "$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live/approvals/${encode(approvalId)}/decision"
        ) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return decodeResponse(response)
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

    fun streamLiveRun(
        token: String,
        hostId: String,
        sessionId: String,
        initialLastEventId: Long? = null,
    ): Flow<LiveRunStreamEvent> = flow {
        var lastEventId = initialLastEventId
        var reconnectDelayMs = SSE_RECONNECT_BASE_MS
        var attempt = 0

        while (true) {
            currentCoroutineContext().ensureActive()

            val request = Request.Builder()
                .url("$baseUrl/api/hosts/$hostId/sessions/${encode(sessionId)}/live/stream")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer $token")
                .apply {
                    if (lastEventId != null) {
                        header("Last-Event-ID", lastEventId.toString())
                    }
                }
                .build()

            val call = STREAM_HTTP.newCall(request)
            val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion {
                call.cancel()
            }

            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string().orEmpty()
                        val message = extractErrorMessage(errorBody, response.code)
                        when (response.code) {
                            401 -> throw UnauthorizedException(message)
                            404 -> throw IllegalStateException(message)
                            else -> throw IllegalStateException(message)
                        }
                    }

                    val body = response.body
                        ?: throw IllegalStateException("SSE 响应体为空")

                    emit(LiveRunStreamEvent.Connected(lastEventId))
                    attempt = 0
                    reconnectDelayMs = SSE_RECONNECT_BASE_MS

                    val pendingData = mutableListOf<String>()
                    var currentEvent: String? = null
                    var currentId: Long? = null
                    val streamContext = currentCoroutineContext()

                    fun resetFrame() {
                        pendingData.clear()
                        currentEvent = null
                        currentId = null
                    }

                    suspend fun parseFrame() {
                        val eventName = currentEvent ?: "message"
                        val eventData = pendingData.joinToString("\n")
                        if (currentId != null) {
                            lastEventId = currentId
                        }
                        when (eventName) {
                            "run" -> {
                                val run = json.decodeFromString<Run?>(eventData)
                                emit(LiveRunStreamEvent.RunSnapshot(run = run, eventId = currentId))
                            }
                            "approval" -> {
                                val approval = json.decodeFromString<PendingApproval>(eventData)
                                emit(LiveRunStreamEvent.ApprovalUpdate(approval = approval, eventId = currentId))
                            }
                            "gap" -> {
                                val payload = json.decodeFromString<GapPayload>(eventData)
                                emit(
                                    LiveRunStreamEvent.Gap(
                                        missedFrom = payload.missedFrom,
                                        currentSeq = payload.currentSeq,
                                    )
                                )
                            }
                            "stream-end" -> {
                                val payload = json.decodeFromString<StreamEndPayload>(eventData)
                                emit(LiveRunStreamEvent.StreamEnd(payload.reason))
                            }
                            "idle-timeout" -> {
                                val payload = json.decodeFromString<IdleTimeoutPayload>(eventData)
                                emit(LiveRunStreamEvent.IdleTimeout(payload.timeoutMs))
                            }
                        }
                    }

                    body.charStream().buffered().use { reader ->
                        while (true) {
                            streamContext.ensureActive()
                            val rawLine = reader.readLine() ?: break
                            when {
                                rawLine.isEmpty() -> {
                                    if (pendingData.isNotEmpty() || currentEvent != null || currentId != null) {
                                        parseFrame()
                                    }
                                    resetFrame()
                                }
                                rawLine.startsWith(":") -> Unit
                                rawLine.startsWith("event:") -> currentEvent = rawLine.substringAfter(':').trimStart()
                                rawLine.startsWith("data:") -> pendingData += rawLine.substringAfter(':').trimStart()
                                rawLine.startsWith("id:") -> {
                                    currentId = rawLine.substringAfter(':').trim().toLongOrNull()
                                }
                            }
                        }
                        if (pendingData.isNotEmpty() || currentEvent != null || currentId != null) {
                            parseFrame()
                        }
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                attempt += 1
                val message = describeNetworkFailure(error)
                val permanent = error is UnauthorizedException ||
                    (error is IllegalStateException && (error.message?.contains("not found") == true))
                if (permanent || attempt >= SSE_MAX_CONSECUTIVE_ERRORS) {
                    throw error
                }
                emit(
                    LiveRunStreamEvent.Reconnecting(
                        attempt = attempt,
                        delayMs = reconnectDelayMs,
                        message = message,
                    )
                )
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(SSE_RECONNECT_MAX_MS)
                continue
            } finally {
                cancellationHandle.dispose()
            }

            attempt += 1
            if (attempt >= SSE_MAX_CONSECUTIVE_ERRORS) {
                throw IllegalStateException("实时流已多次断开，请下拉刷新后重试")
            }
            emit(
                LiveRunStreamEvent.Reconnecting(
                    attempt = attempt,
                    delayMs = reconnectDelayMs,
                    message = "实时连接已断开，正在重连…",
                )
            )
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(SSE_RECONNECT_MAX_MS)
        }
    }

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
        val response = http.post("$baseUrl/api/hosts/$hostId/uploads") {
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
        }
        return decodeResponse(response)
    }

    fun close() {
        http.close()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val SSE_RECONNECT_BASE_MS = 1_000L
        private const val SSE_RECONNECT_MAX_MS = 15_000L
        private const val SSE_MAX_CONSECUTIVE_ERRORS = 3
        private val STREAM_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun normalizeBaseUrl(rawValue: String): String {
            return normalizeServerUrl(rawValue, stripApiPath = true)
        }

        fun normalizeWebUrl(rawValue: String): String {
            return normalizeServerUrl(rawValue, stripApiPath = false)
        }

        private fun normalizeServerUrl(
            rawValue: String,
            stripApiPath: Boolean,
        ): String {
            val trimmed = rawValue.trim()
            require(trimmed.isNotBlank()) { "请输入服务器地址" }

            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }

            val uri = try {
                URI(withScheme)
            } catch (_: Exception) {
                throw IllegalArgumentException("服务器地址格式不正确")
            }

            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") {
                "服务器地址只支持 http 或 https"
            }
            require(!uri.host.isNullOrBlank()) { "服务器地址缺少主机名或 IP" }

            val rawPath = uri.path.orEmpty().trim()
            val normalizedPath = when {
                rawPath.isBlank() || rawPath == "/" -> ""
                stripApiPath && (rawPath == "/api" || rawPath.startsWith("/api/")) -> ""
                else -> rawPath.trimEnd('/')
            }

            return buildString {
                append(scheme)
                append("://")
                append(uri.host)
                if (uri.port != -1) {
                    append(":")
                    append(uri.port)
                }
                if (normalizedPath.isNotBlank()) {
                    append(normalizedPath)
                }
            }
        }

        fun describeNetworkFailure(error: Throwable): String {
            return when (val root = unwrapNetworkCause(error)) {
                is UnknownHostException -> "找不到这台服务器，请检查 IP、域名或局域网连接"
                is ConnectException -> "服务器没有响应，请确认后端已启动且端口可访问"
                is SocketTimeoutException -> "连接服务器超时，请稍后重试"
                is SSLException -> "HTTPS 握手失败，请检查证书或先改用 http 地址"
                is IllegalArgumentException -> root.message ?: "服务器地址格式不正确"
                is UnauthorizedException -> root.message
                is RateLimitException -> buildString {
                    append("登录尝试过于频繁，请稍后再试")
                    root.retryAfterSec?.takeIf { it > 0 }?.let {
                        append("（约 $it 秒后）")
                    }
                }
                else -> root.message ?: "连接失败，请稍后重试"
            }
        }

        private fun unwrapNetworkCause(error: Throwable): Throwable {
            var current: Throwable = error
            while (current.cause != null && current.cause !== current) {
                current = current.cause!!
            }
            return current
        }

        private fun summarizeHealthChecks(checks: JsonObject): String? {
            val highlights = buildList {
                val database = checks["database"]?.jsonPrimitive?.contentOrNull
                if (database != null && database != "ok") add("数据库异常")

                val runs = checks["runs"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val stale = runs?.get("stale")?.jsonPrimitive?.contentOrNull
                if (stale != null && stale != "0") add("存在卡住的运行")

                val disk = checks["disk"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val diskLow = disk?.get("low")?.jsonPrimitive?.contentOrNull
                if (diskLow == "true") add("磁盘空间偏低")

                val dbWriteErrors = checks["dbWriteErrors"]?.jsonPrimitive?.contentOrNull
                if (dbWriteErrors != null && dbWriteErrors != "0") add("最近有数据库写入错误")
            }

            return highlights.takeIf { it.isNotEmpty() }?.joinToString("，")
        }
    }
}

class UnauthorizedException(
    override val message: String = "登录已失效，请重新登录",
) : IllegalStateException(message)

class RateLimitException(
    override val message: String = "登录尝试过于频繁，请稍后再试",
    val retryAfterSec: Int? = null,
) : IllegalStateException(message)

@kotlinx.serialization.Serializable
private data class GapPayload(
    val missedFrom: Long,
    val currentSeq: Long,
)

@kotlinx.serialization.Serializable
private data class StreamEndPayload(
    val reason: String,
)

@kotlinx.serialization.Serializable
private data class IdleTimeoutPayload(
    val timeoutMs: Long,
)
