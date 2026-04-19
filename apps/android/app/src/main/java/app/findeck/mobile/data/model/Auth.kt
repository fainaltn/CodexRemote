package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

/** Auth API request / response types matching packages/shared. */
@Serializable
data class LoginRequest(
    val password: String,
    val deviceLabel: String? = null,
)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class ChangePasswordResponse(
    val ok: Boolean,
    val restartScheduled: Boolean,
)

@Serializable
data class PairingClaimRequest(
    val code: String,
    val deviceLabel: String? = null,
)

@Serializable
data class TrustedClient(
    val clientId: String,
    val deviceLabel: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
)

@Serializable
data class TrustedClientCredentials(
    val clientId: String,
    val clientSecret: String,
    val deviceLabel: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
)

@Serializable
data class PairingClaimResponse(
    val token: String,
    val expiresAt: String,
    val trustedClient: TrustedClientCredentials,
)

@Serializable
data class TrustedReconnectRequest(
    val clientId: String,
    val clientSecret: String,
    val deviceLabel: String? = null,
)

@Serializable
data class TrustedReconnectResponse(
    val token: String,
    val expiresAt: String,
    val trustedClient: TrustedClient,
)
