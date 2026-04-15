package dev.codexremote.android.ui.sessions

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R

internal data class ComposerAttachmentItem(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localOnly: Boolean,
)

internal data class QueuedPromptItem(
    val id: String,
    val preview: String,
    val runtimeSummary: String,
    val attachmentCount: Int,
    val model: String?,
    val reasoningEffort: String?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposerBar(
    prompt: String,
    uploading: Boolean,
    sending: Boolean,
    stopping: Boolean,
    isRunning: Boolean,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    selectedModel: String?,
    selectedReasoningEffort: String?,
    attachments: List<ComposerAttachmentItem>,
    queuedPrompts: List<QueuedPromptItem>,
    onPromptChange: (String) -> Unit,
    onUploadClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onCameraClick: () -> Unit,
    onRemoveAttachment: (ComposerAttachmentItem) -> Unit,
    onRestoreQueuedPrompt: (QueuedPromptItem) -> Unit,
    onModelClick: () -> Unit,
    onReasoningEffortClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onSend: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
    sendContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val statusLabel = when {
        stopping -> stringResource(R.string.composer_status_stopping)
        sending -> stringResource(R.string.composer_status_sending)
        isRunning -> stringResource(R.string.composer_status_running)
        queuedPrompts.isNotEmpty() -> stringResource(R.string.composer_status_queued)
        else -> stringResource(R.string.composer_status_ready)
    }
    val statusColor = when {
        stopping -> MaterialTheme.colorScheme.error
        sending || isRunning -> MaterialTheme.colorScheme.primary
        queuedPrompts.isNotEmpty() -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .animateContentSize(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (attachments.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        attachments.forEach { attachment ->
                            SummaryPill(
                                text = attachment.originalName,
                                onClick = { onRemoveAttachment(attachment) },
                            )
                        }
                    }
                }

                if (queuedPrompts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        queuedPrompts.forEach { queuedPrompt ->
                            SummaryPill(
                                text = queuedPrompt.preview,
                                subtitle = queuedPrompt.runtimeSummary,
                                onClick = { onRestoreQueuedPrompt(queuedPrompt) },
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            BasicTextField(
                                value = prompt,
                                onValueChange = onPromptChange,
                                enabled = !sending && !stopping,
                                minLines = 1,
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 38.dp)
                                    .padding(end = 72.dp),
                                decorationBox = { innerTextField ->
                                    if (prompt.isBlank()) {
                                        Text(
                                            text = stringResource(
                                                if (isRunning) {
                                                    R.string.composer_placeholder_running
                                                } else {
                                                    R.string.composer_placeholder_idle
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                },
                            )

                            StatusDot(
                                color = statusColor,
                                label = statusLabel,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompactIconButton(
                                icon = Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.composer_upload_attachment),
                                enabled = !uploading && !sending,
                                onClick = onUploadClick,
                            )
                            CompactIconButton(
                                icon = Icons.Filled.PhotoLibrary,
                                contentDescription = stringResource(R.string.composer_gallery_attachment),
                                enabled = !uploading && !sending,
                                onClick = onGalleryClick,
                            )
                            CompactIconButton(
                                icon = Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.composer_camera_attachment),
                                enabled = !uploading && !sending,
                                onClick = onCameraClick,
                            )
                            CompactIconButton(
                                icon = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.session_detail_voice_content_description),
                                enabled = !sending && !stopping,
                                onClick = onVoiceClick,
                            )
                            CompactIconButton(
                                icon = Icons.Filled.Memory,
                                contentDescription = stringResource(
                                    R.string.composer_model_desc,
                                    runtimeControlLabel(RuntimeControlTarget.Model, selectedModel),
                                ),
                                enabled = !sending && !stopping,
                                onClick = onModelClick,
                            )
                            CompactIconButton(
                                icon = Icons.Filled.Tune,
                                contentDescription = stringResource(
                                    R.string.composer_reasoning_desc,
                                    runtimeControlLabel(RuntimeControlTarget.ReasoningEffort, selectedReasoningEffort),
                                ),
                                enabled = !sending && !stopping,
                                onClick = onReasoningEffortClick,
                            )
                            if (isRunning) {
                                CompactActionButton(
                                    onClick = onQueue,
                                    enabled = prompt.trim().isNotEmpty() && !uploading && !sending,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    contentDescription = stringResource(R.string.composer_queue_add_desc),
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }

                                CompactActionButton(
                                    onClick = onStop,
                                    enabled = !stopping,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    contentDescription = stringResource(R.string.composer_stop_run_desc),
                                ) {
                                    if (stopping) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Stop,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            } else {
                                CompactActionButton(
                                    onClick = onSend,
                                    enabled = prompt.trim().isNotEmpty() && !sending && !uploading,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    contentDescription = sendContentDescription,
                                ) {
                                    if (sending) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.ArrowUpward,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (uploading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.65f),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
            )
        }
    }
}

@Composable
private fun CompactActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.55f),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(34.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Box(contentAlignment = Alignment.Center) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(
    text: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactInfoChip(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.5f),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusDot(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Circle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
