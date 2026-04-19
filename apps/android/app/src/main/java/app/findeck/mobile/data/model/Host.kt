package app.findeck.mobile.data.model

import kotlinx.serialization.Serializable

/** Mirrors the shared Host schema from packages/shared. */
@Serializable
data class Host(
    val id: String,
    val label: String,
    val kind: String,
    val baseUrl: String? = null,
    val tailscaleIp: String? = null,
    val status: String,
    val lastSeenAt: String? = null,
)
