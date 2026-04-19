package app.findeck.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdLaunchRestoreDecisionTest {
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
    fun `empty server list returns none saved`() {
        val decision = resolveColdLaunchRestoreDecision(
            servers = emptyList(),
            activeServerId = null,
        )

        assertEquals(
            ColdLaunchRestoreDecision.NoRestore(
                reason = ColdLaunchRestoreUnavailableReason.NONE_SAVED,
            ),
            decision,
        )
    }

    @Test
    fun `saved but ineligible hosts return no eligible saved host`() {
        val decision = resolveColdLaunchRestoreDecision(
            servers = listOf(
                server(id = "plain"),
                server(
                    id = "incomplete-trust",
                    trustedHost = TrustedHostMetadata(
                        trustedClientId = "client-id",
                    ),
                ),
                server(
                    id = "paused-trust",
                    trustedHost = TrustedHostMetadata(
                        autoReconnectEnabled = false,
                        trustedClientId = "client-id",
                        trustedClientSecret = "client-secret",
                    ),
                ),
            ),
            activeServerId = "plain",
        )

        assertEquals(
            ColdLaunchRestoreDecision.NoRestore(
                reason = ColdLaunchRestoreUnavailableReason.NO_ELIGIBLE_SAVED_HOST,
            ),
            decision,
        )
    }

    @Test
    fun `ineligible active server falls back to another eligible host`() {
        val fallback = server(
            id = "fallback",
            appPassword = "saved-password",
        )

        val decision = resolveColdLaunchRestoreDecision(
            servers = listOf(
                server(id = "active"),
                fallback,
            ),
            activeServerId = "active",
        )

        assertTrue(decision is ColdLaunchRestoreDecision.Restore)
        val restore = decision as ColdLaunchRestoreDecision.Restore
        assertEquals("fallback", restore.candidate.server.id)
        assertEquals(ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN, restore.candidate.method)
    }

    @Test
    fun `active eligible host returns restore with expected method`() {
        val active = server(
            id = "active",
            appPassword = "saved-password",
            trustedHost = TrustedHostMetadata(
                trustedClientId = "client-id",
                trustedClientSecret = "client-secret",
            ),
        )
        val other = server(
            id = "other",
            appPassword = "other-password",
        )

        val decision = resolveColdLaunchRestoreDecision(
            servers = listOf(active, other),
            activeServerId = "active",
        )

        assertTrue(decision is ColdLaunchRestoreDecision.Restore)
        val restore = decision as ColdLaunchRestoreDecision.Restore
        assertEquals("active", restore.candidate.server.id)
        assertEquals(ColdLaunchRestoreMethod.TRUSTED_RECONNECT, restore.candidate.method)
    }
}
