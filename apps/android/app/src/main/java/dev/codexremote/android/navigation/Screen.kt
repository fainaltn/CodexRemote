package dev.codexremote.android.navigation

/**
 * Sealed hierarchy of all screens in the app.
 * Matches the Phase 1 APK Screens list from the development plan:
 * splash, server list, add server, login, session list, session detail shell.
 */
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object ServerList : Screen("servers")
    data object AddServer : Screen("servers/add")
    data object Login : Screen("login/{serverId}") {
        fun createRoute(serverId: String) = "login/$serverId"
    }
    data object SessionList : Screen("sessions/{serverId}") {
        fun createRoute(serverId: String) = "sessions/$serverId"
    }
    data object NewSession : Screen("sessions/{serverId}/new?cwd={cwd}") {
        fun createRoute(serverId: String, cwd: String? = null): String {
            val encoded = cwd?.let(android.net.Uri::encode) ?: ""
            return "sessions/$serverId/new?cwd=$encoded"
        }
    }
    data object DraftSessionDetail : Screen("sessions/{serverId}/draft?cwd={cwd}") {
        fun createRoute(serverId: String, cwd: String): String {
            val encoded = android.net.Uri.encode(cwd)
            return "sessions/$serverId/draft?cwd=$encoded"
        }
    }
    data object Inbox : Screen("inbox/{serverId}") {
        fun createRoute(serverId: String) = "inbox/$serverId"
    }
    data object SessionDetail : Screen("sessions/{serverId}/{hostId}/{sessionId}") {
        fun createRoute(serverId: String, hostId: String, sessionId: String) =
            "sessions/$serverId/$hostId/$sessionId"
    }
}
