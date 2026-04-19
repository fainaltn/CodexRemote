package app.findeck.mobile.navigation

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
    }

    data object Splash : Screen("splash")
    data object ServerList : Screen("servers")
    data object AddServer : Screen("servers/add")
    data object Pairing : Screen("pairing?serverId={$ARG_SERVER_ID}") {
        fun createRoute(serverId: String? = null): String {
            val encoded = serverId?.takeIf { it.isNotBlank() }?.let(android.net.Uri::encode).orEmpty()
            return "pairing?serverId=$encoded"
        }
    }
    data object Login : Screen("login/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "login/${android.net.Uri.encode(serverId)}"
    }
    data object SessionList : Screen("sessions/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "sessions/${android.net.Uri.encode(serverId)}"
    }
    data object NewSession : Screen("sessions/{$ARG_SERVER_ID}/new?cwd={$ARG_CWD}") {
        fun createRoute(serverId: String, cwd: String? = null): String {
            val encoded = cwd?.let(android.net.Uri::encode) ?: ""
            return "sessions/${android.net.Uri.encode(serverId)}/new?cwd=$encoded"
        }
    }
    data object DraftSessionDetail : Screen("sessions/{$ARG_SERVER_ID}/draft?cwd={$ARG_CWD}") {
        fun createRoute(serverId: String, cwd: String): String {
            val encoded = android.net.Uri.encode(cwd)
            return "sessions/${android.net.Uri.encode(serverId)}/draft?cwd=$encoded"
        }
    }
    data object Inbox : Screen("inbox/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "inbox/${android.net.Uri.encode(serverId)}"
    }
    data object ArchivedSessions : Screen("sessions/{$ARG_SERVER_ID}/archived") {
        fun createRoute(serverId: String) = "sessions/${android.net.Uri.encode(serverId)}/archived"
    }
    data object Settings : Screen("settings/{$ARG_SERVER_ID}") {
        fun createRoute(serverId: String) = "settings/${android.net.Uri.encode(serverId)}"
    }
    data object SessionDetail :
        Screen("sessions/{$ARG_SERVER_ID}/{$ARG_HOST_ID}/{$ARG_SESSION_ID}") {
        fun createRoute(serverId: String, hostId: String, sessionId: String) =
            "sessions/${android.net.Uri.encode(serverId)}/" +
                "${android.net.Uri.encode(hostId)}/${android.net.Uri.encode(sessionId)}"
    }
}
