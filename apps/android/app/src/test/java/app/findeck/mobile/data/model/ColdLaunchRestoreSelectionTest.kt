package app.findeck.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColdLaunchRestoreSelectionTest {
    private fun server(
        id: String,
        appPassword: String? = null,
        trustedHost: TrustedHostMetadata? = null,
    ): Server = Server(
        id = id,
        label = id,
        baseUrl = "http://$id.local:31807",
        appPassword = appPassword,
        trustedHost = trustedHost,
    )

    @Test
    fun `prefers active server when it can restore with saved password`() {
        val active = server(
            id = "active",
            appPassword = "saved-password",
        )
        val trusted = server(
            id = "trusted",
            trustedHost = TrustedHostMetadata(
                pairedAt = "2026-04-19T10:00:00Z",
                trustedClientId = "client-id",
                trustedClientSecret = "client-secret",
            ),
        )

        val candidate = selectColdLaunchRestoreCandidate(
            servers = listOf(active, trusted),
            activeServerId = "active",
        )

        assertEquals("active", candidate?.server?.id)
        assertEquals(ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN, candidate?.method)
    }

    @Test
    fun `prefers trusted reconnect over saved password when no active server is selected`() {
        val trusted = server(
            id = "trusted",
            trustedHost = TrustedHostMetadata(
                pairedAt = "2026-04-19T10:00:00Z",
                trustedClientId = "client-id",
                trustedClientSecret = "client-secret",
            ),
        )
        val passwordOnly = server(
            id = "password-only",
            appPassword = "saved-password",
        )

        val candidate = selectColdLaunchRestoreCandidate(
            servers = listOf(passwordOnly, trusted),
            activeServerId = null,
        )

        assertEquals("trusted", candidate?.server?.id)
        assertEquals(ColdLaunchRestoreMethod.TRUSTED_RECONNECT, candidate?.method)
    }

    @Test
    fun `skips paused trusted reconnect and falls back to saved password when available`() {
        val pausedTrusted = server(
            id = "paused",
            appPassword = "saved-password",
            trustedHost = TrustedHostMetadata(
                autoReconnectEnabled = false,
                pairedAt = "2026-04-19T10:00:00Z",
                trustedClientId = "client-id",
                trustedClientSecret = "client-secret",
            ),
        )

        val candidate = selectColdLaunchRestoreCandidate(
            servers = listOf(pausedTrusted),
            activeServerId = null,
        )

        assertEquals("paused", candidate?.server?.id)
        assertEquals(ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN, candidate?.method)
    }

    @Test
    fun `returns null when no saved host can be restored automatically`() {
        val candidate = selectColdLaunchRestoreCandidate(
            servers = listOf(
                server(id = "plain"),
                server(
                    id = "incomplete-trust",
                    trustedHost = TrustedHostMetadata(
                        trustedClientId = "client-id",
                    ),
                ),
            ),
            activeServerId = null,
        )

        assertNull(candidate)
    }

    @Test
    fun `restore readiness distinguishes trusted saved password manual and pairing states`() {
        assertEquals(
            ServerRestoreReadiness.TRUSTED_RECONNECT_READY,
            server(
                id = "trusted",
                trustedHost = TrustedHostMetadata(
                    trustedClientId = "client-id",
                    trustedClientSecret = "client-secret",
                ),
            ).restoreReadiness(),
        )
        assertEquals(
            ServerRestoreReadiness.SAVED_PASSWORD_READY,
            server(
                id = "saved",
                appPassword = "saved-password",
            ).restoreReadiness(),
        )
        assertEquals(
            ServerRestoreReadiness.MANUAL_SIGN_IN_ONLY,
            server(
                id = "manual",
                trustedHost = TrustedHostMetadata(
                    autoReconnectEnabled = false,
                    trustedClientId = "client-id",
                    trustedClientSecret = "client-secret",
                ),
            ).restoreReadiness(),
        )
        assertEquals(
            ServerRestoreReadiness.NEEDS_PAIRING,
            server(id = "plain").restoreReadiness(),
        )
    }
}
