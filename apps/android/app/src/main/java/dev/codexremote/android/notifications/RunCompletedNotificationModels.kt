package dev.codexremote.android.notifications

import android.content.Context
import android.content.Intent
import dev.codexremote.android.navigation.Screen

data class RunCompletedNotificationPayload(
    val serverId: String,
    val hostId: String,
    val sessionId: String,
    val runId: String? = null,
    val serverLabel: String? = null,
    val hostLabel: String? = null,
    val sessionLabel: String? = null,
    val terminalStatus: String? = null,
) {
    val notificationKey: String
        get() = listOf(serverId, hostId, sessionId, runId.orEmpty()).joinToString("|")

    val notificationId: Int
        get() = notificationKey.hashCode()

    val notificationTag: String
        get() = "run-completed:$notificationKey"

    fun toSessionDetailRoute(): String =
        Screen.SessionDetail.createRoute(serverId, hostId, sessionId)

    fun isValid(): Boolean =
        serverId.isNotBlank() && hostId.isNotBlank() && sessionId.isNotBlank()
}

object RunCompletedNotificationContract {
    const val ACTION_OPEN_SESSION_DETAIL =
        "dev.codexremote.android.notifications.action.OPEN_SESSION_DETAIL"

    const val EXTRA_SERVER_ID = "dev.codexremote.android.notifications.extra.SERVER_ID"
    const val EXTRA_HOST_ID = "dev.codexremote.android.notifications.extra.HOST_ID"
    const val EXTRA_SESSION_ID = "dev.codexremote.android.notifications.extra.SESSION_ID"
    const val EXTRA_RUN_ID = "dev.codexremote.android.notifications.extra.RUN_ID"
    const val EXTRA_SERVER_LABEL = "dev.codexremote.android.notifications.extra.SERVER_LABEL"
    const val EXTRA_HOST_LABEL = "dev.codexremote.android.notifications.extra.HOST_LABEL"
    const val EXTRA_SESSION_LABEL = "dev.codexremote.android.notifications.extra.SESSION_LABEL"
    const val EXTRA_TERMINAL_STATUS = "dev.codexremote.android.notifications.extra.TERMINAL_STATUS"

    fun createOpenSessionDetailIntent(
        context: Context,
        payload: RunCompletedNotificationPayload,
    ): Intent =
        Intent(context, dev.codexremote.android.MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION_DETAIL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SERVER_ID, payload.serverId)
            putExtra(EXTRA_HOST_ID, payload.hostId)
            putExtra(EXTRA_SESSION_ID, payload.sessionId)
            payload.runId?.let { putExtra(EXTRA_RUN_ID, it) }
            payload.serverLabel?.let { putExtra(EXTRA_SERVER_LABEL, it) }
            payload.hostLabel?.let { putExtra(EXTRA_HOST_LABEL, it) }
            payload.sessionLabel?.let { putExtra(EXTRA_SESSION_LABEL, it) }
            payload.terminalStatus?.let { putExtra(EXTRA_TERMINAL_STATUS, it) }
        }

    fun parse(intent: Intent?): RunCompletedNotificationPayload? {
        if (intent?.action != ACTION_OPEN_SESSION_DETAIL) return null

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
        val hostId = intent.getStringExtra(EXTRA_HOST_ID).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (serverId.isBlank() || hostId.isBlank() || sessionId.isBlank()) return null

        return RunCompletedNotificationPayload(
            serverId = serverId,
            hostId = hostId,
            sessionId = sessionId,
            runId = intent.getStringExtra(EXTRA_RUN_ID),
            serverLabel = intent.getStringExtra(EXTRA_SERVER_LABEL),
            hostLabel = intent.getStringExtra(EXTRA_HOST_LABEL),
            sessionLabel = intent.getStringExtra(EXTRA_SESSION_LABEL),
            terminalStatus = intent.getStringExtra(EXTRA_TERMINAL_STATUS),
        )
    }
}
