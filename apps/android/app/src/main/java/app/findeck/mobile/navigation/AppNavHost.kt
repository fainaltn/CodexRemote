package app.findeck.mobile.navigation

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.findeck.mobile.notifications.RunCompletedNotificationPayload
import app.findeck.mobile.StartupUiState
import app.findeck.mobile.ui.theme.ThemePreference
import app.findeck.mobile.ui.login.LoginScreen
import app.findeck.mobile.ui.inbox.InboxScreen
import app.findeck.mobile.ui.servers.AddServerScreen
import app.findeck.mobile.ui.servers.PairingScreen
import app.findeck.mobile.ui.settings.ServerSettingsScreen
import app.findeck.mobile.ui.sessions.NewSessionScreen
import app.findeck.mobile.ui.servers.ServerListScreen
import app.findeck.mobile.ui.sessions.ArchivedSessionsScreen
import app.findeck.mobile.ui.sessions.SessionDetailScreen
import app.findeck.mobile.ui.sessions.SessionListScreen
import app.findeck.mobile.ui.splash.SplashScreen
import kotlinx.coroutines.delay

private const val DRAFT_RETURN_ROUTE_KEY = "draft_return_route"
private const val MIN_SPLASH_DURATION_MS = 2_000L

internal data class DraftReturnTarget(
    val route: String,
    val inclusive: Boolean,
)

internal fun resolveDraftReturnTarget(parentRoute: String?): DraftReturnTarget =
    parentRoute
        ?.takeIf { it.isNotBlank() }
        ?.let { DraftReturnTarget(route = it, inclusive = false) }
        ?: DraftReturnTarget(route = Screen.DraftSessionDetail.route, inclusive = true)

private fun NavBackStackEntry.resolveConcreteRoute(): String? {
    val route = destination.route ?: return null
    return when (route) {
        Screen.SessionList.route ->
            arguments?.getString(Screen.ARG_SERVER_ID)?.let(Screen.SessionList::createRoute)

        Screen.SessionDetail.route -> {
            val serverId = arguments?.getString(Screen.ARG_SERVER_ID)
            val hostId = arguments?.getString(Screen.ARG_HOST_ID)
            val sessionId = arguments?.getString(Screen.ARG_SESSION_ID)
            if (serverId != null && hostId != null && sessionId != null) {
                Screen.SessionDetail.createRoute(serverId, hostId, sessionId)
            } else {
                null
            }
        }

        else -> route
    }
}

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
    startupState: StartupUiState,
    pendingNotificationPayload: RunCompletedNotificationPayload? = null,
    onPendingNotificationHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    var splashShownAtMs by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(currentRoute) {
        if (currentRoute == Screen.Splash.route) {
            splashShownAtMs = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(pendingNotificationPayload, currentRoute, splashShownAtMs) {
        val payload = pendingNotificationPayload ?: return@LaunchedEffect
        if (currentRoute == Screen.Splash.route) {
            val elapsedMs = SystemClock.elapsedRealtime() - splashShownAtMs
            delay((MIN_SPLASH_DURATION_MS - elapsedMs).coerceAtLeast(0L))
            if (navController.currentBackStackEntry?.destination?.route != Screen.Splash.route) {
                return@LaunchedEffect
            }
        }
        navController.navigate(payload.toSessionDetailRoute()) {
            launchSingleTop = true
        }
        onPendingNotificationHandled()
    }

    LaunchedEffect(startupState, currentRoute, splashShownAtMs) {
        if (currentRoute != Screen.Splash.route) return@LaunchedEffect
        val targetRoute = when (val state = startupState) {
            StartupUiState.Loading,
            is StartupUiState.Reconnecting,
            StartupUiState.NoTrustedServer -> null

            is StartupUiState.Reconnected -> Screen.SessionList.createRoute(state.serverId)

            is StartupUiState.ReconnectFailed -> {
                if (state.requiresPairing) {
                    Screen.Pairing.createRoute(state.serverId)
                } else {
                    Screen.Login.createRoute(state.serverId)
                }
            }
        } ?: return@LaunchedEffect

        val elapsedMs = SystemClock.elapsedRealtime() - splashShownAtMs
        delay((MIN_SPLASH_DURATION_MS - elapsedMs).coerceAtLeast(0L))
        if (navController.currentBackStackEntry?.destination?.route != Screen.Splash.route) return@LaunchedEffect

        navController.navigate(targetRoute) {
            popUpTo(Screen.Splash.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Screen.Splash.route) {

        composable(Screen.Splash.route) {
            SplashScreen(
                startupState = startupState,
                onOpenServers = {
                    navController.navigate(Screen.ServerList.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenPairing = {
                    navController.navigate(Screen.Pairing.createRoute()) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Screen.ServerList.route) {
            ServerListScreen(
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onAddServer = { navController.navigate(Screen.AddServer.route) },
                onPairServer = { serverId ->
                    navController.navigate(Screen.Pairing.createRoute(serverId)) {
                        launchSingleTop = true
                    }
                },
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
                onCancel = { navController.popBackStack() },
                onOpenPairing = {
                    navController.navigate(Screen.Pairing.createRoute()) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = Screen.Pairing.route,
            arguments = listOf(
                navArgument(Screen.ARG_SERVER_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID)?.ifBlank { null }
            PairingScreen(
                serverId = serverId,
                onBack = { navController.popBackStack() },
                onPairingComplete = { pairedServerId ->
                    navController.navigate(Screen.SessionList.createRoute(pairedServerId)) {
                        popUpTo(Screen.ServerList.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
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
            val selectedSessionId by backStackEntry.savedStateHandle
                .getStateFlow("selected_session_id", "")
                .collectAsState()
            SessionListScreen(
                serverId = serverId,
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onSelectSession = { hostId, sessionId ->
                    backStackEntry.savedStateHandle["selected_session_id"] = sessionId
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
                onOpenArchived = {
                    navController.navigateToServerScopedTopLevel(
                        serverId = serverId,
                        targetRoute = Screen.ArchivedSessions.createRoute(serverId),
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
                selectedSessionId = selectedSessionId.ifBlank { null },
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
            val draftParentRoute = navController.previousBackStackEntry?.resolveConcreteRoute()
            LaunchedEffect(backStackEntry, draftParentRoute) {
                if (draftParentRoute.isNullOrBlank()) return@LaunchedEffect
                val existingRoute = backStackEntry.savedStateHandle
                    .get<String>(DRAFT_RETURN_ROUTE_KEY)
                if (existingRoute.isNullOrBlank()) {
                    backStackEntry.savedStateHandle[DRAFT_RETURN_ROUTE_KEY] = draftParentRoute
                }
            }
            SessionDetailScreen(
                serverId = serverId,
                hostId = "local",
                sessionId = null,
                initialCwd = cwd,
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onSessionCreated = { hostId, sessionId ->
                    val returnTarget = resolveDraftReturnTarget(
                        backStackEntry.savedStateHandle.get<String>(DRAFT_RETURN_ROUTE_KEY)
                    )
                    navController.navigate(
                        Screen.SessionDetail.createRoute(serverId, hostId, sessionId)
                    ) {
                        popUpTo(returnTarget.route) { inclusive = returnTarget.inclusive }
                        launchSingleTop = true
                    }
                },
                onOpenNewThread = { selectedCwd ->
                    if (selectedCwd != null) {
                        navController.navigate(Screen.DraftSessionDetail.createRoute(serverId, selectedCwd)) {
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(Screen.NewSession.createRoute(serverId)) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenPairing = {
                    navController.navigate(Screen.Pairing.createRoute(serverId)) {
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
            route = Screen.ArchivedSessions.route,
            arguments = listOf(navArgument(Screen.ARG_SERVER_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val serverId =
                backStackEntry.arguments?.getString(Screen.ARG_SERVER_ID) ?: return@composable
            ArchivedSessionsScreen(
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
                themePreference = themePreference,
                onToggleTheme = onToggleTheme,
                onBack = { navController.popBackStack() },
                onOpenPairing = {
                    navController.navigate(Screen.Pairing.createRoute(serverId)) {
                        launchSingleTop = true
                    }
                },
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
                onOpenNewThread = { selectedCwd ->
                    if (selectedCwd != null) {
                        navController.navigate(Screen.DraftSessionDetail.createRoute(serverId, selectedCwd)) {
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(Screen.NewSession.createRoute(serverId)) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenPairing = {
                    navController.navigate(Screen.Pairing.createRoute(serverId)) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
