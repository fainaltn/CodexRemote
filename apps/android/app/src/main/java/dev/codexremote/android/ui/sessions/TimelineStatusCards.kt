package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal enum class TimelineNoticeTone {
    Neutral,
    Warning,
    Error,
}

@Composable
internal fun TimelineNoticeCard(
    title: String,
    message: String,
    tone: TimelineNoticeTone,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val containerColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondaryContainer
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val accentColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.primary
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondary
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .width(4.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                    )
                    footer?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.74f),
                        )
                    }
                }
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )

            content()
        }
    }
}

internal fun terminalRunTitle(status: String): String = when (status) {
    "failed" -> "运行失败"
    "stopped" -> "运行已停止"
    else -> statusLabel(status)
}

internal fun terminalRunMessage(
    status: String,
    error: String?,
): String = when (status) {
    "failed" -> error ?: "本次运行未完成，下面保留最后一次可见输出。"
    "stopped" -> error ?: "本次运行已被停止。"
    else -> error ?: "本次运行已结束。"
}
