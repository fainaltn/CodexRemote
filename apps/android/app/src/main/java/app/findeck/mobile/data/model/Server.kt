package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

/** Local trust metadata for a saved host entry. */
@Serializable
data class TrustedHostMetadata(
    /** ISO-8601 timestamp for when this device was first paired/trusted. */
    val pairedAt: String? = null,
    /** ISO-8601 timestamp for the last successful auto reconnect. */
    val lastAutoReconnectAt: String? = null,
    /** Optional ISO-8601 trust expiration returned by the pairing endpoint. */
    val trustedUntil: String? = null,
    /** When false, the UI can keep the trust record but skip auto reconnect. */
    val autoReconnectEnabled: Boolean = true,
    /** Optional note about how trust was established, e.g. "qr" or "manual-code". */
    val pairingMethod: String? = null,
    /** Optional user-facing label for the trusted device entry. */
    val trustLabel: String? = null,
    /** Long-lived client id returned by the pairing claim flow. */
    val trustedClientId: String? = null,
    /** Long-lived client secret returned by the pairing claim flow. */
    val trustedClientSecret: String? = null,
)

/**
 * A saved findeck server entry stored locally on the device.
 *
 * [baseUrl] is the Fastify API origin (e.g. "http://100.x.y.z:3000").
 * [webUrl]  is the Next.js web UI origin (e.g. "http://100.x.y.z:3001").
 *
 * When both services sit behind a single reverse-proxy origin, [webUrl]
 * can be left null and [baseUrl] is used for everything.
 */
@Serializable
data class Server(
    val id: String,
    val label: String,
    val baseUrl: String,
    val webUrl: String? = null,
    val token: String? = null,
    val appPassword: String? = null,
    val trustedHost: TrustedHostMetadata? = null,
) {
    /** Resolved web origin — falls back to [baseUrl] for single-origin deployments. */
    val effectiveWebUrl: String get() = webUrl?.takeIf { it.isNotBlank() } ?: baseUrl

    /**
     * True when the local trust record says this host can auto reconnect and we
     * still have credentials on disk to support that reconnect.
     */
    val isTrustedReconnectEligible: Boolean
        get() = (trustedHost?.autoReconnectEnabled ?: true) &&
            !trustedHost?.trustedClientId.isNullOrBlank() &&
            !trustedHost?.trustedClientSecret.isNullOrBlank()

    /** True when the host can be restored on cold launch with a saved password. */
    val isStoredPasswordLoginEligible: Boolean
        get() = !appPassword.isNullOrBlank()

    /** Whether a trusted pairing claim has already been completed on this device. */
    val hasTrustedPairing: Boolean
        get() = !trustedHost?.trustedClientId.isNullOrBlank() &&
            !trustedHost?.trustedClientSecret.isNullOrBlank()
}

enum class ColdLaunchRestoreMethod {
    TRUSTED_RECONNECT,
    STORED_PASSWORD_LOGIN,
}

enum class ServerRestoreReadiness {
    TRUSTED_RECONNECT_READY,
    SAVED_PASSWORD_READY,
    MANUAL_SIGN_IN_ONLY,
    NEEDS_PAIRING,
}

data class ColdLaunchRestoreCandidate(
    val server: Server,
    val method: ColdLaunchRestoreMethod,
)

enum class ColdLaunchRestoreUnavailableReason {
    NONE_SAVED,
    NO_ELIGIBLE_SAVED_HOST,
}

sealed interface ColdLaunchRestoreDecision {
    data class Restore(
        val candidate: ColdLaunchRestoreCandidate,
    ) : ColdLaunchRestoreDecision

    data class NoRestore(
        val reason: ColdLaunchRestoreUnavailableReason,
    ) : ColdLaunchRestoreDecision
}

fun Server.resolveColdLaunchRestoreMethod(): ColdLaunchRestoreMethod? =
    when {
        isTrustedReconnectEligible -> ColdLaunchRestoreMethod.TRUSTED_RECONNECT
        isStoredPasswordLoginEligible -> ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN
        else -> null
    }

fun Server.restoreReadiness(): ServerRestoreReadiness =
    when {
        isTrustedReconnectEligible -> ServerRestoreReadiness.TRUSTED_RECONNECT_READY
        isStoredPasswordLoginEligible -> ServerRestoreReadiness.SAVED_PASSWORD_READY
        hasTrustedPairing -> ServerRestoreReadiness.MANUAL_SIGN_IN_ONLY
        else -> ServerRestoreReadiness.NEEDS_PAIRING
    }

fun selectColdLaunchRestoreCandidate(
    servers: List<Server>,
    activeServerId: String?,
    preferActiveServer: Boolean = true,
): ColdLaunchRestoreCandidate? {
    if (preferActiveServer && !activeServerId.isNullOrBlank()) {
        servers.firstOrNull { it.id == activeServerId }
            ?.resolveColdLaunchRestoreMethod()
            ?.let { method ->
                return ColdLaunchRestoreCandidate(
                    server = servers.first { it.id == activeServerId },
                    method = method,
                )
            }
    }

    return servers
        .mapNotNull { server ->
            server.resolveColdLaunchRestoreMethod()?.let { method ->
                ColdLaunchRestoreCandidate(server = server, method = method)
            }
        }
        .sortedWith(
            compareBy<ColdLaunchRestoreCandidate> { candidate ->
                when (candidate.method) {
                    ColdLaunchRestoreMethod.TRUSTED_RECONNECT -> 0
                    ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN -> 1
                }
            }.thenByDescending { candidate ->
                candidate.server.trustedHost?.lastAutoReconnectAt ?: candidate.server.trustedHost?.pairedAt ?: ""
            }
        )
        .firstOrNull()
}

fun resolveColdLaunchRestoreDecision(
    servers: List<Server>,
    activeServerId: String?,
    preferActiveServer: Boolean = true,
): ColdLaunchRestoreDecision {
    val candidate = selectColdLaunchRestoreCandidate(
        servers = servers,
        activeServerId = activeServerId,
        preferActiveServer = preferActiveServer,
    )
    if (candidate != null) {
        return ColdLaunchRestoreDecision.Restore(candidate)
    }

    return ColdLaunchRestoreDecision.NoRestore(
        reason = if (servers.isEmpty()) {
            ColdLaunchRestoreUnavailableReason.NONE_SAVED
        } else {
            ColdLaunchRestoreUnavailableReason.NO_ELIGIBLE_SAVED_HOST
        }
    )
}
