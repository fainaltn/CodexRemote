@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.codexremote.android.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R

data class OutputFileReference(
    val label: String,
    val absolutePath: String,
)

private val markdownLocalFileRegex = Regex("""\[([^\]]+)\]\((/[^)\n]+)\)""")
private val absolutePathLineRegex = Regex("""(?m)^Absolute path:\s+(.+)$""")

private fun extractOutputFileReferences(output: String?): List<OutputFileReference> {
    val text = output.orEmpty()
    if (text.isBlank()) return emptyList()

    val references = mutableListOf<OutputFileReference>()
    markdownLocalFileRegex.findAll(text).forEach { match ->
        val label = match.groupValues[1].trim()
        val absolutePath = match.groupValues[2].trim()
        if (label.isNotBlank() && absolutePath.startsWith("/")) {
            references += OutputFileReference(label = label, absolutePath = absolutePath)
        }
    }
    absolutePathLineRegex.findAll(text).forEach { match ->
        val absolutePath = match.groupValues[1].trim()
        if (absolutePath.startsWith("/")) {
            references += OutputFileReference(
                label = absolutePath.substringAfterLast('/').ifBlank { absolutePath },
                absolutePath = absolutePath,
            )
        }
    }

    return references.distinctBy { it.absolutePath }
}

// ── User message bubble ───────────────────────────────────────────

/**
 * Renders a user's message in a right-aligned bubble.
 * Used both in history rounds and for the current-turn prompt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserMessageBubble(
    text: String,
    timestamp: String? = null,
    dimmed: Boolean = false,
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val displayText = remember(text) { sanitizePromptDisplay(text) }
    var showSheet by remember { mutableStateOf(false) }
    val copyLabel = stringResource(R.string.session_timeline_message_copy)
    val editAndResendLabel = stringResource(R.string.session_timeline_message_edit_and_resend)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showSheet = true },
                ),
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = if (dimmed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = displayText,
                    style = if (dimmed) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (dimmed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
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
    }

    if (showSheet) {
        val clipboardManager = LocalClipboardManager.current
            MessageActionSheet(
                onDismiss = { showSheet = false },
                actions = buildList {
                    add(
                        MessageAction(
                            label = copyLabel,
                            icon = Icons.Filled.ContentCopy,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(text))
                                showSheet = false
                            },
                    )
                )
                onCopy?.let { /* already handled above */ }
                onEdit?.let { editFn ->
                    add(
                        MessageAction(
                            label = editAndResendLabel,
                            icon = Icons.Filled.Edit,
                            onClick = {
                                showSheet = false
                                editFn()
                            },
                        )
                    )
                }
            },
        )
    }
}

// ── AI reply block ────────────────────────────────────────────────

/**
 * The hero composable: shows the AI's response with a primary-color accent bar,
 * rich text blocks, a live progress indicator, and a micro-status footer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantReplyBlock(
    output: String?,
    isActive: Boolean,
    status: String? = null,
    model: String? = null,
    startedAt: String? = null,
    finishedAt: String? = null,
    error: String? = null,
    sending: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onReusePrompt: (() -> Unit)? = null,
    onDownloadFile: ((OutputFileReference) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val terminalStatus = status?.takeIf { it in terminalRunStatuses && it != "completed" }
    val hasSettled = !isActive && status in terminalRunStatuses
    val runStateLabel = currentRunStateLabel(
        liveRunStatus = status,
        hasVisibleOutput = !output.isNullOrBlank(),
        liveStreamConnected = isActive,
        sending = sending,
        isDraft = false,
    )
    val settledTitle = stringResource(R.string.session_timeline_reply_settled_title)
    val settledStateLabel = stringResource(R.string.session_timeline_reply_settled_state_label)
    val replyErrorTitle = stringResource(R.string.session_timeline_reply_error_title)
    val replyErrorFooter = stringResource(R.string.session_timeline_reply_error_footer)
    val replyErrorState = stringResource(R.string.session_timeline_reply_error_state_label)
    val reusePromptLabel = stringResource(R.string.session_timeline_reply_reuse_prompt)
    val retryLabel = stringResource(R.string.session_timeline_reply_retry)
    val copyReplyLabel = stringResource(R.string.session_timeline_reply_copy_reply)
    val retryRunLabel = stringResource(R.string.session_timeline_reply_retry_run)
    val fileRefs = remember(output) { extractOutputFileReferences(output) }
    val accentColor = when {
        terminalStatus != null -> MaterialTheme.colorScheme.error
        hasSettled -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val renderOutput = terminalStatus == null || !output.isNullOrBlank()
    var showSheet by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val accentWidth by animateDpAsState(
        targetValue = if (hasSettled) 5.dp else 3.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "assistant-accent-width",
    )
    val accentAlpha by animateFloatAsState(
        targetValue = if (hasSettled) 1f else if (isActive) 0.92f else 0.78f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "assistant-accent-alpha",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (hasSettled) 1f else 0.98f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "assistant-content-alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    if (!output.isNullOrBlank()) showSheet = true
                },
            )
            .animateContentSize(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ),
    ) {
        // Accent bar + content
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent bar visible only while active
            Box(
                modifier = Modifier
                    .width(accentWidth)
                    .heightIn(min = 48.dp)
                    .drawBehind {
                        if (isActive || hasSettled) {
                            drawRect(accentColor.copy(alpha = accentAlpha))
                        }
                    }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                AnimatedVisibility(
                    visible = hasSettled,
                    enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                        animationSpec = tween(220),
                        initialScale = 0.98f,
                    ),
                    exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                        animationSpec = tween(150),
                        targetScale = 0.98f,
                    ),
                ) {
                    TimelineNoticeCard(
                        title = when {
                            terminalStatus != null -> terminalRunTitle(terminalStatus)
                            else -> settledTitle
                        },
                        message = when {
                            terminalStatus != null -> terminalRunMessage(terminalStatus, error)
                            !output.isNullOrBlank() -> stringResource(R.string.session_timeline_reply_settled_message_with_output)
                            else -> stringResource(R.string.session_timeline_reply_settled_message_empty)
                        },
                        tone = when {
                            terminalStatus != null -> TimelineNoticeTone.Error
                            else -> TimelineNoticeTone.Neutral
                        },
                        stateLabel = when {
                            terminalStatus != null -> statusLabel(terminalStatus)
                            else -> settledStateLabel
                        },
                        stateTone = when {
                            terminalStatus != null -> TimelineNoticeTone.Error
                            else -> TimelineNoticeTone.Neutral
                        },
                    )
                }

                if (renderOutput) {
                    ReplySectionCard(
                        output = output.orEmpty(),
                        active = isActive,
                        sectionLabel = null,
                        onDownloadFile = onDownloadFile,
                    )
                }

                if (terminalStatus == null) {
                    // Error inline
                    error?.let { errorMsg ->
                        TimelineNoticeCard(
                            title = replyErrorTitle,
                            message = errorMsg,
                            footer = replyErrorFooter,
                            tone = TimelineNoticeTone.Error,
                            stateLabel = replyErrorState,
                        ) {
                            // Intentionally left empty: the message itself carries the feedback.
                        }
                    }
                }

                // Micro status
                MicroStatusRow(
                    model = model,
                    elapsed = startedAt?.let { formatRunElapsed(it, finishedAt) },
                    isActive = isActive,
                    stateLabel = runStateLabel,
                )

                // Action buttons (only when run is done and has content)
                if (!isActive && onRetry != null && onReusePrompt != null) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onReusePrompt, enabled = !sending) {
                            Text(reusePromptLabel, style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = onRetry,
                            enabled = !sending,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(retryLabel, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (showSheet) {
            MessageActionSheet(
                onDismiss = { showSheet = false },
                actions = buildList {
                    add(
                        MessageAction(
                            label = copyReplyLabel,
                            icon = Icons.Filled.ContentCopy,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(output.orEmpty()))
                                showSheet = false
                            },
                        )
                    )
                    onRetry?.let { retryFn ->
                        add(
                            MessageAction(
                                label = retryRunLabel,
                                icon = Icons.Filled.Replay,
                                onClick = {
                                    showSheet = false
                                    retryFn()
                                },
                            )
                        )
                    }
                },
            )
        }
    }
}

@Composable
internal fun AssistantMessageSnapshotCard(
    output: String,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            label?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RichBlockList(
                text = output,
                active = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun SettledAssistantMessageCard(
    message: dev.codexremote.android.data.model.SessionMessage,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (collapsed) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsed) {
                Text(
                    text = message.text.replace(Regex("\\s+"), " ").trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                RichBlockList(
                    text = message.text,
                    active = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun CurrentTurnCollapsedHeader(
    messageCount: Int,
    expanded: Boolean,
    processedDuration: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.session_timeline_current_turn_collapsed_title, messageCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                processedDuration?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.session_timeline_current_turn_collapsed_subtitle, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) {
                    stringResource(R.string.session_timeline_current_turn_collapse)
                } else {
                    stringResource(R.string.session_timeline_current_turn_expand)
                },
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReplySectionCard(
    output: String,
    active: Boolean,
    sectionLabel: String?,
    onDownloadFile: ((OutputFileReference) -> Unit)? = null,
) {
    val fileRefs = remember(output) { extractOutputFileReferences(output) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (active) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!sectionLabel.isNullOrBlank()) {
                Text(
                    text = sectionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                StreamingTextRenderer(
                    targetText = output,
                    active = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                RichBlockList(
                    text = output,
                    active = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (fileRefs.isNotEmpty() && onDownloadFile != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fileRefs.forEach { ref ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            TextButton(onClick = { onDownloadFile(ref) }) {
                                Text(
                                    text = ref.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Incremental block parse cache ─────────────────────────────────

/**
 * Maintains stable (finalized) blocks separately from the active tail block.
 * When text appends within the last block, only [tailBlock] changes while
 * [stableBlocks] keeps the same list reference — allowing Compose to skip
 * recomposition of already-rendered blocks.
 */
internal class BlockParseCache {
    var stableBlocks: List<RichTextBlock> = emptyList()
        private set
    var tailBlock: RichTextBlock? = null
        private set
    private var lastText: String = ""

    fun computeAndReturn(text: String): Pair<List<RichTextBlock>, RichTextBlock?> {
        if (text != lastText) {
            val allBlocks = parseTextToBlocks(text)
            val newStable = if (allBlocks.size > 1) allBlocks.dropLast(1) else emptyList()
            val newTail = allBlocks.lastOrNull()
            if (newStable != stableBlocks) {
                stableBlocks = newStable
            }
            tailBlock = newTail
            lastText = text
        }
        return stableBlocks to tailBlock
    }
}

// ── Streaming text renderer (block-based + live indicator) ─────────

@Composable
internal fun StreamingTextRenderer(
    targetText: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val noVisibleOutputTitle = stringResource(R.string.session_timeline_reply_no_visible_output_title)
    val noVisibleOutputFooter = stringResource(R.string.session_timeline_reply_no_visible_output_footer)
    val noVisibleOutputState = stringResource(R.string.session_timeline_reply_no_visible_output_state_label)
    val cache = remember { BlockParseCache() }
    var displayedText by remember { mutableStateOf(if (active) "" else targetText) }
    val cursorTransition = rememberInfiniteTransition(label = "type-cursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "type-cursor-alpha",
    )

    androidx.compose.runtime.LaunchedEffect(targetText, active) {
        if (!active) {
            displayedText = targetText
            return@LaunchedEffect
        }

        displayedText = calmTypewriterBaseline(
            targetText = targetText,
            currentDisplayedText = displayedText,
        )
        while (displayedText.length < targetText.length) {
            val remaining = targetText.length - displayedText.length
            val nextCount = typewriterStepSize(remaining).coerceAtLeast(1)
            val nextEnd = (displayedText.length + nextCount).coerceAtMost(targetText.length)
            val nextSlice = targetText.substring(displayedText.length, nextEnd)
            displayedText = targetText.substring(0, nextEnd)
            val delayChar = nextSlice.lastOrNull() ?: break
            kotlinx.coroutines.delay(typewriterDelayMs(delayChar))
        }
    }

    // Incremental parse: stable blocks keep the same reference when unchanged.
    val (stableBlocks, tailBlock) = remember(displayedText) {
        cache.computeAndReturn(displayedText)
    }
    val hasVisibleBlocks = stableBlocks.isNotEmpty() || tailBlock != null

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasVisibleBlocks) {
            if (active) {
                ThinkingPlaceholderCard()
            } else {
                TimelineNoticeCard(
                    title = noVisibleOutputTitle,
                    message = stringResource(R.string.session_timeline_reply_no_visible_output_message),
                    footer = noVisibleOutputFooter,
                    tone = TimelineNoticeTone.Neutral,
                    stateLabel = noVisibleOutputState,
                )
            }
        } else {
            for (block in stableBlocks) {
                key(block.id) {
                    AnimatedBlock(id = block.id) {
                        RenderSingleBlock(block, cursorAlpha = 0f)
                    }
                }
            }
            tailBlock?.let { block ->
                key(block.id) {
                    AnimatedBlock(id = block.id) {
                        RenderSingleBlock(
                            block = block,
                            cursorAlpha = if (active && displayedText.length < targetText.length) cursorAlpha else 0f,
                        )
                    }
                }
            }
        }

        if (active) {
            StreamingActivityChip(hasVisibleOutput = hasVisibleBlocks)
        }
    }
}

@Composable
private fun RenderSingleBlock(block: RichTextBlock, cursorAlpha: Float) {
    when (block) {
        is RichTextBlock.Paragraph -> ParagraphBlock(block, cursorAlpha)
        is RichTextBlock.CodeBlock -> RichCodeBlock(block = block, cursorAlpha = cursorAlpha)
        is RichTextBlock.ListBlock -> RichListBlock(block, cursorAlpha)
        is RichTextBlock.MemoryCitation -> MemoryCitationBlock(block)
    }
}

@Composable
internal fun ThinkingPlaceholderCard(
    compact: Boolean = false,
) {
    if (compact) {
        StreamingActivityChip(
            hasVisibleOutput = true,
            textOverride = localizedSessionText(
                "继续处理中，下一段内容会接在后面。",
                "Still working. The next chunk will append below.",
            ),
        )
        return
    }

    TimelineNoticeCard(
        title = localizedSessionText("思考中", "Thinking"),
        message = localizedSessionText(
            "这一轮先保持思考状态，拿到可见内容后会继续在下面追加，不会中断当前区域。",
            "This run stays in a thinking state first, then appends visible output below without replacing this area.",
        ),
        footer = localizedSessionText("正在等待首段内容", "Waiting for the first visible chunk"),
        tone = TimelineNoticeTone.Neutral,
        stateLabel = localizedSessionText("实时中", "Live"),
    ) {
        ShimmerBlock(lines = 2)
        Spacer(modifier = Modifier.height(4.dp))
        StreamingActivityChip(hasVisibleOutput = false)
    }
}

@Composable
private fun StreamingActivityChip(
    hasVisibleOutput: Boolean,
    textOverride: String? = null,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "stream-activity")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.24f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stream-activity-alpha",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                        RoundedCornerShape(999.dp),
                    ),
            )
            Text(
                text = textOverride ?: if (hasVisibleOutput) {
                    localizedSessionText("继续生成中，后续内容会追加在这里。", "Still thinking, more content will append here.")
                } else {
                    localizedSessionText("正在组织首段内容，请保持当前卡片可见。", "Preparing the first visible chunk. Keep this card in place.")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Shimmer skeleton ──────────────────────────────────────────────

/**
 * Animated shimmer lines that simulate content loading.
 * Used inside [StreamingTextRenderer] while waiting for the first output
 * and in [WaitingReplyPlaceholder] for idle state.
 */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    lines: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            surfaceVariant.copy(alpha = 0.2f),
            surfaceVariant.copy(alpha = 0.6f),
            surfaceVariant.copy(alpha = 0.2f),
        ),
        start = Offset(progress * 1000f - 300f, 0f),
        end = Offset(progress * 1000f, 0f),
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(lines) { index ->
            val fraction = when (index) {
                0 -> 0.92f
                lines - 1 -> 0.48f
                else -> 0.7f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .background(shimmerBrush, RoundedCornerShape(4.dp))
            )
        }
    }
}

// ── Empty / waiting state ─────────────────────────────────────────

@Composable
internal fun WaitingReplyPlaceholder(
    draft: Boolean,
    modifier: Modifier = Modifier,
) {
    val waitingDraftTitle = stringResource(R.string.session_timeline_reply_waiting_draft_title)
    val waitingDraftMessage = stringResource(R.string.session_timeline_reply_waiting_draft_message)
    val waitingDraftFooter = stringResource(R.string.session_timeline_reply_waiting_draft_footer)
    val waitingDraftState = stringResource(R.string.session_timeline_reply_waiting_draft_state_label)
    val waitingTitle = stringResource(R.string.session_timeline_reply_waiting_title)
    val waitingMessage = stringResource(R.string.session_timeline_reply_waiting_message)
    val waitingFooter = stringResource(R.string.session_timeline_reply_waiting_footer)
    val waitingState = stringResource(R.string.session_timeline_reply_waiting_state_label)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimelineNoticeCard(
            title = if (draft) waitingDraftTitle else waitingTitle,
            message = if (draft) waitingDraftMessage else waitingMessage,
            footer = if (draft) waitingDraftFooter else waitingFooter,
            tone = TimelineNoticeTone.Neutral,
            stateLabel = if (draft) waitingDraftState else waitingState,
            content = {
                ShimmerBlock(lines = if (draft) 2 else 3)
            },
        )
    }
}

// ── Long-press action sheet ───────────────────────────────────────

internal data class MessageAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet(
    onDismiss: () -> Unit,
    actions: List<MessageAction>,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            actions.forEach { action ->
                Surface(
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
