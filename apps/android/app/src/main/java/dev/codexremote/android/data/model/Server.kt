package dev.codexremote.android.data.model

import kotlinx.serialization.Serializable

/**
 * A saved CodexRemote server entry stored locally on the device.
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
) {
    /** Resolved web origin — falls back to [baseUrl] for single-origin deployments. */
    val effectiveWebUrl: String get() = webUrl?.takeIf { it.isNotBlank() } ?: baseUrl
}
