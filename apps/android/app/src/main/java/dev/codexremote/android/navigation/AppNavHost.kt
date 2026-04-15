package dev.codexremote.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.codexremote.android.notifications.RunCompletedNotificationPayload
import dev.codexremote.android.ui.theme.ThemePreference
import dev.codexremote.android.ui.login.LoginScreen
import dev.codexremote.android.ui.inbox.InboxScreen
import dev.codexremote.android.ui.servers.AddServerScreen
import dev.codexremote.android.ui.settings.ServerSettingsScreen
import dev.codexremote.android.ui.sessions.NewSessionScreen
import dev.codexremote.android.ui.servers.ServerListScreen
import dev.codexremote.android.ui.sessions.SessionDetailScreen
import dev.codexremote.android.ui.sessions.SessionListScreen
import dev.codexremote.android.ui.splash.SplashScreen

private fun NavHostController.navigateToServerScopedTopLevel(
    serverId: String,
    targetRoute: String,
) {
    navigate(targetRoute) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Screen.SessionList.createRoute(serverId)) {
            saveState = true
        }
    }
}

@Composable
fun AppNavHost(
    themePreference: ThemePreference,
    onToggleTheme: () -> Unit,
    pendingNotificationPayload: RunCompletedNotificationPayload? = null,
    onPendingNotificationHandled: () -> Unit = {},
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingNotificationPayload) {
        val payload = pendingNotificationPayload ?: return@LaunchedEffect
        navController.navigate(payload.toSessionDetailRoute()) {
            launchSingleTop = true
        }
        onPendingNotificationHandled()
    }

    NavHost(navController = navController, startDestination = Screen.Splash.route) {

        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToServers = {
                    navController.navigate(Screen.ServerList.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.ServerList.route) {
            ServerListScreen(
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onAddServer = { navController.navigate(Screen.AddServer.route) },
                onSelectServer = { serverId ->
                    navController.navigate(Screen.Login.createRoute(serverId)) {
                        launchSingleTop = true
                    }
                },
                onServerAuthenticated = { serverId ->
                    navController.navigate(Screen.SessionList.createRoute(serverId)) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.AddServer.route) {
            AddServerScreen(
                onServerAdded = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Login.route,
            arguments = listOf(navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            LoginScreen(
                serverId = serverId,
                onLoginSuccess = {
                    navController.navigate(Screen.SessionList.createRoute(serverId)) {
                        popUpTo(Screen.ServerList.route)
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SessionList.route,
            arguments = listOf(navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            val selectedProjectCwd by backStackEntry.savedStateHandle
                .getStateFlow("selected_project_cwd", "")
                .collectAsState()
            SessionListScreen(
                serverId = serverId,
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onSelectSession = { hostId, sessionId ->
                    navController.navigate(
                        Screen.SessionDetail.createRoute(serverId, hostId, sessionId)
                    ) {
                        launchSingleTop = true
                    }
                },
                onOpenInbox = {
                    navController.navigateToServerScopedTopLevel(
                        serverId = serverId,
                        targetRoute = Screen.Inbox.createRoute(serverId),
                    )
                },
                onOpenSettings = {
                    navController.navigateToServerScopedTopLevel(
                        serverId = serverId,
                        targetRoute = Screen.Settings.createRoute(serverId),
                    )
                },
                onNewProject = {
                    navController.navigate(Screen.NewSession.createRoute(serverId)) {
                        launchSingleTop = true
                    }
                },
                onNewThread = { cwd ->
                    if (cwd != null) {
                        navController.navigate(Screen.DraftSessionDetail.createRoute(serverId, cwd)) {
                            launchSingleTop = true
                        }
                    }
                },
                onAuthExpired = {
                    navController.navigate(Screen.Login.createRoute(serverId)) {
                        popUpTo(Screen.SessionList.createRoute(serverId)) { inclusive = true }
                    }
                },
                selectedProjectCwd = selectedProjectCwd.ifBlank { null },
                onProjectHandled = {
                    backStackEntry.savedStateHandle["selected_project_cwd"] = ""
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.NewSession.route,
            arguments = listOf(
                navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType },
                navArgument(Screen.ARG_CWD) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                },
            )
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            val cwd = backStackEntry.arguments?.getString(Screen.ARG_CWD)?.ifBlank { null }
            NewSessionScreen(
                serverId = serverId,
                initialCwd = cwd,
                onProjectSelected = { selectedCwd ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("selected_project_cwd", selectedCwd)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.DraftSessionDetail.route,
            arguments = listOf(
                navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType },
                navArgument(Screen.ARG_CWD) {
                    type = NavType.StringType
                    nullable = false
                },
            )
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            val cwd =
                backStackEntry.arguments?.getString(Screen.ARG_CWD)?.ifBlank { null }
                    ?: return@composable
            SessionDetailScreen(
                serverId = serverId,
                hostId = "local",
                sessionId = null,
                initialCwd = cwd,
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onSessionCreated = { hostId, sessionId ->
                    navController.navigate(
                        Screen.SessionDetail.createRoute(serverId, hostId, sessionId)
                    ) {
                        popUpTo(Screen.DraftSessionDetail.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Inbox.route,
            arguments = listOf(navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            InboxScreen(
                serverId = serverId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Settings.route,
            arguments = listOf(navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            ServerSettingsScreen(
                serverId = serverId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.SessionDetail.route,
            arguments = listOf(
                navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType },
                navArgument(Screen.ARG_HOST_ID) { type = NavType.StringType },
                navArgument(Screen.ARG_SESSION_ID) { type = NavType.StringType },
            )
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            val hostId =
                backStackEntry.arguments?.getString(Screen.ARG_HOST_ID) ?: return@composable
            val sessionId =
                backStackEntry.arguments?.getString(Screen.ARG_SESSION_ID) ?: return@composable
            SessionDetailScreen(
                serverId = serverId,
                hostId = hostId,
                sessionId = sessionId,
                initialCwd = null,
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onSessionCreated = { _, _ -> },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
