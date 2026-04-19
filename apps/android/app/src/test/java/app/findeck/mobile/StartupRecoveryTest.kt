package app.findeck.mobile

import app.findeck.mobile.data.model.ColdLaunchRestoreCandidate
import app.findeck.mobile.data.model.ColdLaunchRestoreMethod
import app.findeck.mobile.data.model.Server
import app.findeck.mobile.data.model.TrustedHostMetadata
import app.findeck.mobile.data.network.ReconnectRecoveryException
import app.findeck.mobile.data.network.UnauthorizedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ConnectException

class StartupRecoveryTest {
    private fun server(
        appPassword: String? = null,
        trustedHost: TrustedHostMetadata? = null,
    ): Server = Server(
        id = "server-1",
        label = "Desk Mac",
        baseUrl = "http://desk-mac.local:31807",
        appPassword = appPassword,
        trustedHost = trustedHost,
    )

    @Test
    fun `trusted reconnect rejection routes to pairing`() {
        val failure = classifyStartupFailure(
            ReconnectRecoveryException(
                reason = "client_secret_mismatch",
                recoveryAction = "re_pair",
                message = "Trusted reconnect secret no longer matches this host",
            )
        )

        assertEquals(StartupFailureKind.TRUST_REJECTED, failure.kind)
        assertEquals(StartupRecoveryAction.OPEN_PAIRING, failure.nextAction)
        assertEquals("本机保存的可信凭据已经失效，请重新配对。", failure.message)
    }

    @Test
    fun `unauthorized login routes to login`() {
        val failure = classifyStartupFailure(UnauthorizedException("登录已失效，请重新登录"))

        assertEquals(StartupFailureKind.LOGIN_REJECTED, failure.kind)
        assertEquals(StartupRecoveryAction.OPEN_LOGIN, failure.nextAction)
        assertEquals("登录已失效，请重新登录", failure.message)
    }

    @Test
    fun `network failures route to server list`() {
        val failure = classifyStartupFailure(ConnectException())

        assertEquals(StartupFailureKind.NETWORK_UNREACHABLE, failure.kind)
        assertEquals(StartupRecoveryAction.OPEN_SERVERS, failure.nextAction)
        assertEquals("服务器没有响应，请确认后端已启动且端口可访问。", failure.message)
    }

    @Test
    fun `trusted reconnect failure can fall back to saved password login`() {
        val candidate = ColdLaunchRestoreCandidate(
            server = server(
                appPassword = "saved-password",
                trustedHost = TrustedHostMetadata(
                    trustedClientId = "client-id",
                    trustedClientSecret = "client-secret",
                ),
            ),
            method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
        )

        val fallback = resolveFallbackRestoreCandidate(
            candidate = candidate,
            error = ReconnectRecoveryException(
                reason = "client_secret_mismatch",
                recoveryAction = "re_pair",
                message = "Trusted reconnect secret no longer matches this host",
            ),
        )

        assertEquals(ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN, fallback?.method)
    }

    @Test
    fun `network failures do not fall back to saved password login`() {
        val candidate = ColdLaunchRestoreCandidate(
            server = server(
                appPassword = "saved-password",
                trustedHost = TrustedHostMetadata(
                    trustedClientId = "client-id",
                    trustedClientSecret = "client-secret",
                ),
            ),
            method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
        )

        val fallback = resolveFallbackRestoreCandidate(
            candidate = candidate,
            error = ConnectException(),
        )

        assertNull(fallback)
    }

    @Test
    fun `perform cold launch restore falls back from trusted reconnect to saved password`() {
        val calls = mutableListOf<String>()
        val result = kotlinx.coroutines.runBlocking {
            performColdLaunchRestore(
                candidate = ColdLaunchRestoreCandidate(
                    server = server(
                        appPassword = "saved-password",
                        trustedHost = TrustedHostMetadata(
                            trustedClientId = "client-id",
                            trustedClientSecret = "client-secret",
                        ),
                    ),
                    method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
                ),
                client = object : ColdLaunchRestoreClient {
                    override suspend fun reconnectTrusted(
                        clientId: String,
                        clientSecret: String,
                        deviceLabel: String,
                    ): String {
                        calls += "trusted"
                        throw ReconnectRecoveryException(
                            reason = "client_secret_mismatch",
                            recoveryAction = "re_pair",
                            message = "Trusted reconnect secret no longer matches this host",
                        )
                    }

                    override suspend fun login(password: String, deviceLabel: String): String {
                        calls += "password"
                        return "token-from-password"
                    }
                },
            )
        }

        assertEquals(listOf("trusted", "password"), calls)
        assertEquals(ColdLaunchRestoreMethod.STORED_PASSWORD_LOGIN, result.method)
        assertEquals("token-from-password", result.token)
    }

    @Test
    fun `perform cold launch restore keeps trusted reconnect when it succeeds`() {
        val calls = mutableListOf<String>()
        val result = kotlinx.coroutines.runBlocking {
            performColdLaunchRestore(
                candidate = ColdLaunchRestoreCandidate(
                    server = server(
                        appPassword = "saved-password",
                        trustedHost = TrustedHostMetadata(
                            trustedClientId = "client-id",
                            trustedClientSecret = "client-secret",
                        ),
                    ),
                    method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
                ),
                client = object : ColdLaunchRestoreClient {
                    override suspend fun reconnectTrusted(
                        clientId: String,
                        clientSecret: String,
                        deviceLabel: String,
                    ): String {
                        calls += "trusted"
                        return "token-from-trusted-reconnect"
                    }

                    override suspend fun login(password: String, deviceLabel: String): String {
                        calls += "password"
                        return "should-not-run"
                    }
                },
            )
        }

        assertEquals(listOf("trusted"), calls)
        assertEquals(ColdLaunchRestoreMethod.TRUSTED_RECONNECT, result.method)
        assertEquals("token-from-trusted-reconnect", result.token)
    }

    @Test(expected = ConnectException::class)
    fun `perform cold launch restore does not fall back on network failure`() {
        kotlinx.coroutines.runBlocking {
            performColdLaunchRestore(
                candidate = ColdLaunchRestoreCandidate(
                    server = server(
                        appPassword = "saved-password",
                        trustedHost = TrustedHostMetadata(
                            trustedClientId = "client-id",
                            trustedClientSecret = "client-secret",
                        ),
                    ),
                    method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
                ),
                client = object : ColdLaunchRestoreClient {
                    override suspend fun reconnectTrusted(
                        clientId: String,
                        clientSecret: String,
                        deviceLabel: String,
                    ): String {
                        throw ConnectException()
                    }

                    override suspend fun login(password: String, deviceLabel: String): String {
                        return "should-not-run"
                    }
                },
            )
        }
    }

    @Test(expected = ReconnectRecoveryException::class)
    fun `perform cold launch restore does not fake a fallback when no saved password exists`() {
        kotlinx.coroutines.runBlocking {
            performColdLaunchRestore(
                candidate = ColdLaunchRestoreCandidate(
                    server = server(
                        trustedHost = TrustedHostMetadata(
                            trustedClientId = "client-id",
                            trustedClientSecret = "client-secret",
                        ),
                    ),
                    method = ColdLaunchRestoreMethod.TRUSTED_RECONNECT,
                ),
                client = object : ColdLaunchRestoreClient {
                    override suspend fun reconnectTrusted(
                        clientId: String,
                        clientSecret: String,
                        deviceLabel: String,
                    ): String {
                        throw ReconnectRecoveryException(
                            reason = "client_secret_mismatch",
                            recoveryAction = "re_pair",
                            message = "Trusted reconnect secret no longer matches this host",
                        )
                    }

                    override suspend fun login(password: String, deviceLabel: String): String {
                        return "should-not-run"
                    }
                },
            )
        }
    }
}
