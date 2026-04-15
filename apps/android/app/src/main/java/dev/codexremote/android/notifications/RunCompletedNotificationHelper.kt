package dev.codexremote.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.codexremote.android.R

class RunCompletedNotificationHelper(context: Context) {
    private val appContext = context.applicationContext

    fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_run_completed_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.notification_channel_run_completed_description)
            }
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    fun buildContentIntent(payload: RunCompletedNotificationPayload): PendingIntent {
        val intent = RunCompletedNotificationContract.createOpenSessionDetailIntent(appContext, payload)
        val requestCode = payload.notificationId
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, requestCode, intent, flags)
    }

    fun buildNotification(payload: RunCompletedNotificationPayload): Notification {
        val sessionLabel = displayLabel(payload.sessionLabel, payload.sessionId)
        val serverLabel = displayLabel(payload.serverLabel, payload.serverId)
        val hostLabel = displayLabel(payload.hostLabel, payload.hostId)
        val (title, body, priority, category) = notificationPresentation(
            payload = payload,
            sessionLabel = sessionLabel,
            serverLabel = serverLabel,
            hostLabel = hostLabel,
        )

        return NotificationCompat.Builder(appContext, ensureChannel())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(category)
            .setPriority(priority)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildContentIntent(payload))
            .build()
    }

    fun postRunNotification(
        payload: RunCompletedNotificationPayload,
        appInBackground: Boolean = true,
    ): Boolean {
        if (!appInBackground || !payload.isValid() || !canPostNotifications()) return false

        val notification = buildNotification(payload)
        NotificationManagerCompat.from(appContext).notify(
            payload.notificationTag,
            payload.notificationId,
            notification,
        )
        return true
    }

    fun postRunCompleted(
        payload: RunCompletedNotificationPayload,
        appInBackground: Boolean = true,
    ): Boolean = postRunNotification(
        payload = payload.copy(tier = payload.tier),
        appInBackground = appInBackground,
    )

    companion object {
        const val CHANNEL_ID = "run_completed"
    }

    private fun displayLabel(label: String?, fallback: String): String =
        label?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    private fun notificationPresentation(
        payload: RunCompletedNotificationPayload,
        sessionLabel: String,
        serverLabel: String,
        hostLabel: String,
    ): NotificationPresentation = when (payload.tier) {
        RunNotificationTier.COMPLETED -> NotificationPresentation(
            title = appContext.getString(R.string.notification_run_completed_title),
            body = appContext.getString(
                R.string.notification_run_completed_body,
                sessionLabel,
                serverLabel,
                hostLabel,
            ),
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_STATUS,
        )
        RunNotificationTier.FAILED -> NotificationPresentation(
            title = appContext.getString(R.string.notification_run_failed_title),
            body = appContext.getString(
                R.string.notification_run_failed_body,
                sessionLabel,
                serverLabel,
                hostLabel,
            ),
            priority = NotificationCompat.PRIORITY_HIGH,
            category = NotificationCompat.CATEGORY_ERROR,
        )
        RunNotificationTier.NEEDS_ATTENTION -> NotificationPresentation(
            title = appContext.getString(R.string.notification_run_attention_title),
            body = appContext.getString(
                R.string.notification_run_attention_body,
                sessionLabel,
                serverLabel,
                hostLabel,
            ),
            priority = NotificationCompat.PRIORITY_HIGH,
            category = NotificationCompat.CATEGORY_STATUS,
        )
        RunNotificationTier.RECOVERED -> NotificationPresentation(
            title = appContext.getString(R.string.notification_run_recovered_title),
            body = appContext.getString(
                R.string.notification_run_recovered_body,
                sessionLabel,
                serverLabel,
                hostLabel,
            ),
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_STATUS,
        )
    }

    private data class NotificationPresentation(
        val title: String,
        val body: String,
        val priority: Int,
        val category: String,
    )
}
