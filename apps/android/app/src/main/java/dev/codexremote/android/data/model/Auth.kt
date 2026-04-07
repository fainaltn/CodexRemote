package dev.codexremote.android.data.model

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
