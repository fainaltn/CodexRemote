package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexremote.android.data.model.Run

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionControlStrip(
    liveRun: Run?,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    queuedPromptCount: Int,
    modifier: Modifier = Modifier,
) {
    if (liveRun == null && liveStreamStatus.isNullOrBlank() && queuedPromptCount == 0) return

    val statusText = when {
        liveRun != null -> statusLabel(liveRun.status)
        liveStreamConnected -> "实时连接已就绪"
        else -> "等待下一次运行"
    }
    val detailItems = buildList {
        liveRun?.model?.takeIf { it.isNotBlank() }?.let { add(it) }
        liveRun?.reasoningEffort?.takeIf { it.isNotBlank() }?.let { add("思考 $it") }
        liveRun?.let { add(formatRunElapsed(it.startedAt, it.finishedAt)) }
        if (queuedPromptCount > 0) add("待发送 $queuedPromptCount")
    }
    val containerColor = when {
        liveRun?.status in activeRunStatuses -> MaterialTheme.colorScheme.primaryContainer
        !liveStreamConnected && !liveStreamStatus.isNullOrBlank() -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        liveRun?.status in activeRunStatuses -> MaterialTheme.colorScheme.onPrimaryContainer
        !liveStreamConnected && !liveStreamStatus.isNullOrBlank() -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
            if (!liveStreamStatus.isNullOrBlank()) {
                Text(
                    text = liveStreamStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.82f),
                )
            }
            if (detailItems.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    detailItems.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = contentColor.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = item,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }
    }
}
