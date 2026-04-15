package dev.codexremote.android.ui.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    onRemoveAttachment: (ComposerAttachmentItem) -> Unit,
    onRestoreQueuedPrompt: (QueuedPromptItem) -> Unit,
    onModelClick: () -> Unit,
    onReasoningEffortClick: () -> Unit,
    onSend: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
    sendContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val summaryItems = buildList {
        if (isRunning) add("运行中")
        if (!liveStreamStatus.isNullOrBlank()) {
            add(
                when {
                    liveStreamConnected -> liveStreamStatus
                    else -> "已切换自动刷新"
                },
            )
        }
        if (queuedPrompts.isNotEmpty()) add("待发送 ${queuedPrompts.size}")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ),
            ) {
                // Attachment chips row (inside the surface)
                if (summaryItems.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        summaryItems.forEach { summary ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = summary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                RuntimeControlChips(
                    modelLabel = runtimeControlLabel(
                        RuntimeControlTarget.Model,
                        selectedModel,
                    ),
                    reasoningLabel = runtimeControlLabel(
                        RuntimeControlTarget.ReasoningEffort,
                        selectedReasoningEffort,
                    ),
                    enabled = !sending && !stopping,
                    onModelClick = onModelClick,
                    onReasoningClick = onReasoningEffortClick,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )

                if (attachments.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        attachments.forEach { attachment ->
                            InputChip(
                                selected = false,
                                onClick = { onRemoveAttachment(attachment) },
                                label = {
                                    Text(
                                        text = attachment.originalName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除",
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            )
                        }
                    }
                }

                if (queuedPrompts.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        queuedPrompts.forEach { queuedPrompt ->
                            InputChip(
                                selected = false,
                                onClick = { onRestoreQueuedPrompt(queuedPrompt) },
                                label = {
                                    Text(
                                        text = queuedPrompt.preview,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // Attach button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        IconButton(
                            onClick = onUploadClick,
                            enabled = !uploading && !sending,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = "上传附件",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // Text input
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        BasicTextField(
                            value = prompt,
                            onValueChange = onPromptChange,
                            enabled = !sending && !stopping,
                            minLines = 1,
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            decorationBox = { innerTextField ->
                                if (prompt.isBlank()) {
                                    Text(
                                        text = when {
                                            isRunning -> "继续输入，完成后自动发送..."
                                            else -> "描述你想做的事情..."
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            },
                        )
                    }

                    // Send / Stop button
                    AnimatedContent(
                        targetState = isRunning,
                        label = "composer-action",
                    ) { running ->
                        if (running) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = onStop,
                                    enabled = !stopping,
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                                ) {
                                    if (stopping) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Stop,
                                            contentDescription = "停止运行",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }

                                Button(
                                    onClick = onQueue,
                                    enabled = prompt.trim().isNotEmpty() && !uploading && !sending,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    modifier = Modifier.height(40.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = "加入待发送队列",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "排队",
                                        modifier = Modifier.padding(start = 4.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (prompt.trim().isNotEmpty() && !sending && !uploading) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                },
                            ) {
                                IconButton(
                                    onClick = onSend,
                                    enabled = prompt.trim().isNotEmpty() && !sending && !uploading,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    if (sending) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.ArrowUpward,
                                            contentDescription = sendContentDescription,
                                            tint = MaterialTheme.colorScheme.onPrimary,
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

        if (isRunning && prompt.isBlank()) {
            Text(
                text = "当前轮次仍在运行，你可以先写下一条，它会在本轮结束后自动发送。",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
