package dev.codexremote.android.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.android.data.model.SessionMessage
import dev.codexremote.android.R

/**
 * A single history round in the conversation timeline.
 *
 * **Collapsed** - a compact, scan-friendly summary that surfaces the round state.
 * **Expanded**  - full message bubbles for the round with a short round header.
 */
@Composable
internal fun HistoryRoundItem(
    round: HistoryRound,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HistoryRoundSummary(
            round = round,
            expanded = expanded,
            onClick = onToggle,
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HistoryRoundExpandedHeader(round = round)

                round.primaryMessages.forEachIndexed { index, message ->
                    val isLastAssistant = index == round.primaryMessages.lastIndex &&
                        message.role == "assistant" &&
                        message.kind != "reasoning"

                    if (isLastAssistant && round.foldedMessages.isNotEmpty()) {
                        FoldedMessagesSection(foldedMessages = round.foldedMessages)
                    }

                    RenderHistoryMessage(message)
                }

                if (round.foldedMessages.isNotEmpty() &&
                    round.primaryMessages.none { it.role == "assistant" && it.kind != "reasoning" }
                ) {
                    FoldedMessagesSection(foldedMessages = round.foldedMessages)
                }
            }
        }
    }
}

// ── Summary row ───────────────────────────────────────────────────

@Composable
private fun HistoryRoundSummary(
    round: HistoryRound,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tone = round.tone()
    val palette = tonePalette(tone)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = round.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SummaryBadge(
                            text = round.stateLabel(),
                            containerColor = palette.container,
                            contentColor = palette.content,
                        )
                        SummaryBadge(
                            text = round.messageCountLabel(),
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.session_timeline_history_round_collapse)
                    } else {
                        stringResource(R.string.session_timeline_history_round_expand)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = round.previewLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = round.summaryMetaLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryRoundExpandedHeader(round: HistoryRound) {
    val tone = round.tone()
    val palette = tonePalette(tone)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, palette.border.copy(alpha = 0.75f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = round.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                SummaryBadge(
                    text = round.stateLabel(),
                    containerColor = palette.container,
                    contentColor = palette.content,
                )
            }

            Text(
                text = round.previewLabel(84).ifBlank {
                    stringResource(R.string.session_timeline_history_round_preview_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = round.summaryMetaLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SummaryBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class TonePalette(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun tonePalette(tone: HistoryRoundTone): TonePalette = when (tone) {
    HistoryRoundTone.Reasoning -> TonePalette(
        container = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        border = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
    )
    HistoryRoundTone.Folded -> TonePalette(
        container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        border = MaterialTheme.colorScheme.secondary.copy(alpha = 0.32f),
    )
    HistoryRoundTone.Completed -> TonePalette(
        container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
    )
    HistoryRoundTone.Active -> TonePalette(
        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        border = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
    )
}

// ── Render a single history message by role/kind ─────────────────

@Composable
private fun RenderHistoryMessage(message: SessionMessage) {
    when (message.role) {
        "user" -> UserMessageBubble(
            text = message.text,
            timestamp = message.createdAt,
            dimmed = true,
        )

        "assistant" -> {
            if (message.kind == "reasoning") {
                ReasoningBubble(text = message.text)
            } else {
                HistoryAssistantBubble(
                    text = message.text,
                    timestamp = message.createdAt,
                )
            }
        }

        else -> HistoryAssistantBubble(
            text = message.text,
            timestamp = message.createdAt,
        )
    }
}

// ── Folded messages with expand toggle ───────────────────────────

@Composable
private fun FoldedMessagesSection(
    foldedMessages: List<SessionMessage>,
) {
    var showFolded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showFolded = !showFolded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (showFolded) {
                    stringResource(
                        R.string.session_timeline_folded_messages_collapse,
                        foldedMessages.size,
                    )
                } else {
                    stringResource(
                        R.string.session_timeline_folded_messages_expand,
                        foldedMessages.size,
                    )
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (showFolded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    AnimatedVisibility(
        visible = showFolded,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.session_timeline_folded_messages_section_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            foldedMessages.forEach { message ->
                RenderHistoryMessage(message)
            }
        }
    }
}

// ── History assistant bubble (no typewriter, dimmed) ──────────────

@Composable
private fun HistoryAssistantBubble(
    text: String,
    timestamp: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RichBlockList(
            text = text,
            active = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 32.dp),
        )
        timestamp?.let {
            Text(
                text = formatDate(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Reasoning (thinking) bubble ──────────────────────────────────

@Composable
private fun ReasoningBubble(
    text: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.9f),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.session_timeline_reasoning_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
