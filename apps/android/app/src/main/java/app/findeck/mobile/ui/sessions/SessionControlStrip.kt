package app.findeck.mobile.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.findeck.mobile.R
import app.findeck.mobile.data.model.Run

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionControlStrip(
    liveRun: Run?,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    selectedModel: String?,
    selectedReasoningEffort: String?,
    queuedPromptCount: Int,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (liveRun == null && liveStreamStatus.isNullOrBlank() && queuedPromptCount == 0 && !isRefreshing) return

    val statusText = when {
        isRefreshing && liveRun == null -> stringResource(R.string.session_control_refreshing)
        liveRun != null -> statusLabel(liveRun.status)
        liveStreamConnected -> stringResource(R.string.session_control_stream_ready)
        else -> stringResource(R.string.session_control_waiting_next)
    }
    val effectiveModel = liveRun?.model?.takeIf { it.isNotBlank() } ?: selectedModel
    val effectiveReasoning = liveRun?.reasoningEffort?.takeIf { it.isNotBlank() } ?: selectedReasoningEffort
    val runtimeItems = listOf(
        stringResource(
            R.string.session_control_model_format,
            runtimeControlLabel(RuntimeControlTarget.Model, effectiveModel),
        ),
        stringResource(
            R.string.session_control_reasoning_format,
            runtimeControlLabel(RuntimeControlTarget.ReasoningEffort, effectiveReasoning),
        ),
    )
    val secondaryItems = buildList {
        if (queuedPromptCount > 0) add(stringResource(R.string.session_control_queue_count, queuedPromptCount))
        if (isRefreshing) add(stringResource(R.string.session_control_syncing))
        if (!liveStreamStatus.isNullOrBlank() && !liveStreamConnected) {
            add(stringResource(R.string.session_control_degraded))
        }
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
                Spacer(modifier = Modifier.weight(1f))
                elapsedText?.let {
                    StripChip(text = it, contentColor = contentColor)
                }
                runtimeItems.forEach { item ->
                    StripChip(text = item, contentColor = contentColor)
                }
            }
            if (secondaryItems.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    secondaryItems.forEach { item ->
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
