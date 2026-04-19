package app.findeck.mobile.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Sealed hierarchy of all screens in the app.
 * Matches the Phase 1 APK Screens list from the development plan:
 * splash, server list, add server, login, session list, session detail shell.
 */
sealed class Screen(val route: String) {
    companion object {
        const val ARG_SERVER_ID = "serverId"
        const val ARG_CWD = "cwd"
        const val ARG_HOST_ID = "hostId"
        const val ARG_SESSION_ID = "sessionId"

        private fun encodeRouteValue(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    data object Splash : Screen("splash")
    data object ServerList : Screen("servers")
    data object AddServer : Screen("servers/add")
    data object Pairing : Screen("pairing?serverId={$ARG_SERVER_ID}") {
        fun createRoute(serverId: String? = null): String {
            val encoded = serverId?.takeIf { it.isNotBlank() }?.let(Companion::encodeRouteValue).orEmpty()
            return "pairing?serverId=$encoded"
        }
    }
    data object Login : Screen("login/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "login/${encodeRouteValue(serverId)}"
    }
    data object SessionList : Screen("sessions/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "sessions/${encodeRouteValue(serverId)}"
    }
    data object NewSession : Screen("sessions/{$ARG_SERVER_ID}/new?cwd={$ARG_CWD}") {
        fun createRoute(serverId: String, cwd: String? = null): String {
            val encoded = cwd?.let(Companion::encodeRouteValue) ?: ""
            return "sessions/${encodeRouteValue(serverId)}/new?cwd=$encoded"
        }
    }
    data object DraftSessionDetail : Screen("sessions/{$ARG_SERVER_ID}/draft?cwd={$ARG_CWD}") {
        fun createRoute(serverId: String, cwd: String): String {
            val encoded = encodeRouteValue(cwd)
            return "sessions/${encodeRouteValue(serverId)}/draft?cwd=$encoded"
        }
    }
    data object Inbox : Screen("inbox/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "inbox/${encodeRouteValue(serverId)}"
    }
    data object ArchivedSessions : Screen("sessions/{$ARG_SERVER_ID}/archived") {
        fun createRoute(serverId: String) = "sessions/${encodeRouteValue(serverId)}/archived"
    }
    data object Settings : Screen("settings/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "settings/${encodeRouteValue(serverId)}"
    }
    data object SessionDetail :
        Screen("sessions/{$ARG_SERVER_ID}/{$ARG_HOST_ID}/{$ARG_SESSION_ID}") {
        fun createRoute(serverId: String, hostId: String, sessionId: String) =
            "sessions/${encodeRouteValue(serverId)}/" +
                "${encodeRouteValue(hostId)}/${encodeRouteValue(sessionId)}"
    }
}
