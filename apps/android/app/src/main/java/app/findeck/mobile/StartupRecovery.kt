package app.findeck.mobile

import app.findeck.mobile.data.network.RateLimitException
import app.findeck.mobile.data.network.ReconnectRecoveryException
import app.findeck.mobile.data.network.UnauthorizedException
import app.findeck.mobile.data.model.ColdLaunchRestoreCandidate
import app.findeck.mobile.data.model.ColdLaunchRestoreMethod
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class StartupFailureKind {
    TRUST_REJECTED,
    LOGIN_REJECTED,
    NETWORK_UNREACHABLE,
    BAD_SERVER_URL,
    TLS_ERROR,
    RATE_LIMITED,
    UNKNOWN,
}

enum class StartupNoTrustedServerKind {
    NONE_SAVED,
    NO_ELIGIBLE_RESTORE_HOST,
}

enum class StartupRecoveryAction {
    OPEN_PAIRING,
    OPEN_LOGIN,
    OPEN_SERVERS,
}

data class StartupFailureDescriptor(
    val kind: StartupFailureKind,
    val nextAction: StartupRecoveryAction,
    val message: String,
)

class MissingTrustedReconnectCredentialsException : IllegalStateException(
    "当前主机缺少可信重连凭据，请重新配对或重新登录。",
)

data class ColdLaunchRestoreResult(
    val method: ColdLaunchRestoreMethod,
    val token: String,
)

interface ColdLaunchRestoreClient {
    suspend fun reconnectTrusted(
        clientId: String,
        clientSecret: String,
        deviceLabel: String,
    ): String

    suspend fun login(
        password: String,
        deviceLabel: String,
    ): String
}

internal fun resolveFallbackRestoreCandidate(
    candidate: ColdLaunchRestoreCandidate,
    error: Throwable,
): ColdLaunchRestoreCandidate? {
    if (candidate.method != ColdLaunchRestoreMethod.TRUSTED_RECONNECT) {
        return null
    }
    if (!candidate.server.isStoredPasswordLoginEligible) {
        return null
    }

    val root = unwrapStartupError(error)
    return when (root) {
        is ReconnectRecoveryException,
        is MissingTrustedReconnectCredentialsException -> candidate.copy(
            method = ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN
        )

        else -> null
    }
}

internal suspend fun performColdLaunchRestore(
    candidate: ColdLaunchRestoreCandidate,
    client: ColdLaunchRestoreClient,
): ColdLaunchRestoreResult {
    return try {
        executeColdLaunchRestore(candidate, client)
    } catch (error: Exception) {
        val fallback = resolveFallbackRestoreCandidate(candidate, error) ?: throw error
        executeColdLaunchRestore(fallback, client)
    }
}

private suspend fun executeColdLaunchRestore(
    candidate: ColdLaunchRestoreCandidate,
    client: ColdLaunchRestoreClient,
): ColdLaunchRestoreResult {
    return when (candidate.method) {
        ColdLaunchRestoreMethod.TRUSTED_RECONNECT -> {
            val trustedClientId = candidate.server.trustedHost?.trustedClientId?.takeIf { it.isNotBlank() }
            val trustedClientSecret =
                candidate.server.trustedHost?.trustedClientSecret?.takeIf { it.isNotBlank() }

            if (trustedClientId == null || trustedClientSecret == null) {
                throw MissingTrustedReconnectCredentialsException()
            }

            val token = client.reconnectTrusted(
                clientId = trustedClientId,
                clientSecret = trustedClientSecret,
                deviceLabel = "android",
            )
            ColdLaunchRestoreResult(
                method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
                token = token,
            )
        }

        ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN -> {
            val storedPassword = candidate.server.appPassword?.takeIf { it.isNotBlank() }
                ?: throw MissingTrustedReconnectCredentialsException()
            val token = client.login(
                password = storedPassword,
                deviceLabel = "android",
            )
            ColdLaunchRestoreResult(
                method = ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN,
                token = token,
            )
        }
    }
}

internal fun classifyStartupFailure(error: Throwable): StartupFailureDescriptor {
    val root = unwrapStartupError(error)
    return when (root) {
        is ReconnectRecoveryException -> StartupFailureDescriptor(
            kind = StartupFailureKind.TRUST_REJECTED,
            nextAction = StartupRecoveryAction.OPEN_PAIRING,
            message = trustedReconnectMessage(root.reason),
        )

        is MissingTrustedReconnectCredentialsException -> StartupFailureDescriptor(
            kind = StartupFailureKind.TRUST_REJECTED,
            nextAction = StartupRecoveryAction.OPEN_PAIRING,
            message = root.message ?: "当前主机缺少可信重连凭据，请重新配对。",
        )

        is UnauthorizedException -> StartupFailureDescriptor(
            kind = StartupFailureKind.LOGIN_REJECTED,
            nextAction = StartupRecoveryAction.OPEN_LOGIN,
            message = root.message ?: "登录已失效，请重新登录。",
        )

        is RateLimitException -> StartupFailureDescriptor(
            kind = StartupFailureKind.RATE_LIMITED,
            nextAction = StartupRecoveryAction.OPEN_LOGIN,
            message = root.message ?: "登录尝试过于频繁，请稍后再试。",
        )

        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException -> StartupFailureDescriptor(
            kind = StartupFailureKind.NETWORK_UNREACHABLE,
            nextAction = StartupRecoveryAction.OPEN_SERVERS,
            message = when (root) {
                is UnknownHostException -> "找不到这台服务器，请检查 IP、域名或局域网连接。"
                is ConnectException -> "服务器没有响应，请确认后端已启动且端口可访问。"
                else -> "连接服务器超时，请稍后重试。"
            },
        )

        is IllegalArgumentException -> StartupFailureDescriptor(
            kind = StartupFailureKind.BAD_SERVER_URL,
            nextAction = StartupRecoveryAction.OPEN_SERVERS,
            message = root.message ?: "服务器地址格式不正确。",
        )

        is SSLException -> StartupFailureDescriptor(
            kind = StartupFailureKind.TLS_ERROR,
            nextAction = StartupRecoveryAction.OPEN_SERVERS,
            message = "HTTPS 握手失败，请检查证书或先改用 http 地址。",
        )

        else -> StartupFailureDescriptor(
            kind = StartupFailureKind.UNKNOWN,
            nextAction = StartupRecoveryAction.OPEN_SERVERS,
            message = root.message ?: "连接失败，请稍后重试。",
        )
    }
}

internal fun unwrapStartupError(error: Throwable): Throwable {
    var current: Throwable = error
    while (current.cause != null && current.cause !== current) {
        current = current.cause!!
    }
    return current
}

private fun trustedReconnectMessage(reason: String): String =
    when (reason) {
        "client_not_found" -> "这台主机已经不认识当前设备了，请重新配对。"
        "client_revoked" -> "这台设备的可信连接已在主机上被撤销，请重新配对。"
        "client_secret_mismatch" -> "本机保存的可信凭据已经失效，请重新配对。"
        else -> "可信重连已失效，请重新配对。"
    }
