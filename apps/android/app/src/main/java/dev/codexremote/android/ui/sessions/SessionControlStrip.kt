package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (liveRun == null && liveStreamStatus.isNullOrBlank() && queuedPromptCount == 0 && !isRefreshing) return

    val statusText = when {
        isRefreshing && liveRun == null -> "正在同步界面"
        liveRun != null -> statusLabel(liveRun.status)
        liveStreamConnected -> "实时连接已就绪"
        else -> "等待下一次运行"
    }
    val detailItems = buildList {
        liveRun?.model?.takeIf { it.isNotBlank() }?.let { add(it) }
        liveRun?.reasoningEffort?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (queuedPromptCount > 0) add("队列 $queuedPromptCount")
        if (isRefreshing) add("同步中")
    }
    val elapsedText = liveRun?.let { formatRunElapsed(it.startedAt, it.finishedAt) }
    val containerColor = when {
        liveRun?.status in activeRunStatuses -> MaterialTheme.colorScheme.primaryContainer
        !liveStreamConnected && !liveStreamStatus.isNullOrBlank() -> MaterialTheme.colorScheme.secondaryContainer
        isRefreshing -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        liveRun?.status in activeRunStatuses -> MaterialTheme.colorScheme.onPrimaryContainer
        !liveStreamConnected && !liveStreamStatus.isNullOrBlank() -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val signalColor = when {
        liveRun?.status == "failed" -> MaterialTheme.colorScheme.error
        liveRun?.status == "stopped" -> MaterialTheme.colorScheme.secondary
        liveRun?.status in activeRunStatuses -> MaterialTheme.colorScheme.primary
        isRefreshing -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = null,
                    tint = signalColor,
                    modifier = Modifier.padding(top = 1.dp)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
                elapsedText?.let {
                    StripChip(text = it, contentColor = contentColor)
                }
                if (!liveStreamStatus.isNullOrBlank() && !liveStreamConnected) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = contentColor.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = "降级",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                        )
                    }
                }
            }
            if (detailItems.isNotEmpty() || (!liveStreamStatus.isNullOrBlank() && !liveStreamConnected)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    detailItems.forEach { item ->
                        StripChip(text = item, contentColor = contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun StripChip(
    text: String,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = contentColor.copy(alpha = 0.1f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
