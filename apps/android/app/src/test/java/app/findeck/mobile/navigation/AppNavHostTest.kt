package app.findeck.mobile.navigation

import app.findeck.mobile.data.model.ColdLaunchRestoreMethod
import app.findeck.mobile.StartupFailureKind
import app.findeck.mobile.StartupNoTrustedServerKind
import app.findeck.mobile.StartupRecoveryAction
import app.findeck.mobile.StartupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavHostTest {

    @Test
    fun `draft return target keeps the concrete parent route`() {
        val parentRoute = "sessions/server-1"

        val target = resolveDraftReturnTarget(parentRoute)

        assertEquals(parentRoute, target.route)
        assertFalse(target.inclusive)
    }

    @Test
    fun `draft return target falls back to popping the draft route`() {
        val target = resolveDraftReturnTarget(null)

        assertEquals(Screen.DraftSessionDetail.route, target.route)
        assertTrue(target.inclusive)
    }

    @Test
    fun `startup route stays on splash when no trusted server is available`() {
        assertNull(
            resolveStartupTargetRoute(
                StartupUiState.NoTrustedServer(StartupNoTrustedServerKind.NONE_SAVED)
            )
        )
        assertNull(
            resolveStartupTargetRoute(
                StartupUiState.NoTrustedServer(StartupNoTrustedServerKind.NO_ELIGIBLE_RESTORE_HOST)
            )
        )
    }

    @Test
    fun `startup route opens session list after reconnect succeeds`() {
        assertEquals(
            Screen.SessionList.createRoute("server-1"),
            resolveStartupTargetRoute(
                StartupUiState.Reconnected(
                    serverId = "server-1",
                    serverLabel = "Desk Mac",
                    restoreMethod = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
                )
            )
        )
    }

    @Test
    fun `startup route follows the recovery action on reconnect failure`() {
        assertEquals(
            Screen.Pairing.createRoute("server-1"),
            resolveStartupTargetRoute(
                StartupUiState.ReconnectFailed(
                    serverId = "server-1",
                    serverLabel = "Desk Mac",
                    message = "repair",
                    kind = StartupFailureKind.TRUST_REJECTED,
                    nextAction = StartupRecoveryAction.OPEN_PAIRING,
                )
            )
        )
        assertEquals(
            Screen.Login.createRoute("server-1"),
            resolveStartupTargetRoute(
                StartupUiState.ReconnectFailed(
                    serverId = "server-1",
                    serverLabel = "Desk Mac",
                    message = "login",
                    kind = StartupFailureKind.LOGIN_REJECTED,
                    nextAction = StartupRecoveryAction.OPEN_LOGIN,
                )
            )
        )
        assertEquals(
            Screen.ServerList.route,
            resolveStartupTargetRoute(
                StartupUiState.ReconnectFailed(
                    serverId = "server-1",
                    serverLabel = "Desk Mac",
                    message = "servers",
                    kind = StartupFailureKind.NETWORK_UNREACHABLE,
                    nextAction = StartupRecoveryAction.OPEN_SERVERS,
                )
            )
        )
    }
}
