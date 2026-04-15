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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
                        label = "复制",
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
                            label = "编辑并重发",
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
 * rich text blocks, typewriter cursor, and micro-status footer.
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
                            else -> "本轮已收束"
                        },
                        message = when {
                            terminalStatus != null -> terminalRunMessage(terminalStatus, error)
                            !output.isNullOrBlank() -> "流式输出已经沉淀为最终结果，当前内容可继续浏览、复制或直接重试。"
                            else -> "本轮已经结束，当前没有额外的文本输出。"
                        },
                        tone = when {
                            terminalStatus != null -> TimelineNoticeTone.Error
                            else -> TimelineNoticeTone.Neutral
                        },
                        stateLabel = when {
                            terminalStatus != null -> statusLabel(terminalStatus)
                            else -> "已同步"
                        },
                        stateTone = when {
                            terminalStatus != null -> TimelineNoticeTone.Error
                            else -> TimelineNoticeTone.Neutral
                        },
                    )
                }

                // Streaming text with typewriter
                if (renderOutput) {
                    StreamingTextRenderer(
                        targetText = output.orEmpty(),
                        active = isActive,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (terminalStatus == null) {
                    // Error inline
                    error?.let { errorMsg ->
                        TimelineNoticeCard(
                            title = "本轮运行有问题",
                            message = errorMsg,
                            footer = "你仍然可以重试本轮或回填提示词。",
                            tone = TimelineNoticeTone.Error,
                            stateLabel = "错误",
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
                            Text("回填提示词", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = onRetry,
                            enabled = !sending,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("重试", style = MaterialTheme.typography.labelMedium)
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
                            label = "复制回复",
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
                                label = "重试本轮",
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

// ── Streaming text renderer (typewriter + incremental blocks) ─────

@Composable
internal fun StreamingTextRenderer(
    targetText: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    var renderedText by remember { mutableStateOf(targetText) }
    val cache = remember { BlockParseCache() }
    val transition = rememberInfiniteTransition(label = "stream-cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stream-cursor-alpha",
    )

    LaunchedEffect(targetText, active) {
        if (!active) {
            renderedText = targetText
            return@LaunchedEffect
        }

        if (!targetText.startsWith(renderedText)) {
            renderedText = targetText
            return@LaunchedEffect
        }

        var cursor = renderedText.length
        while (cursor < targetText.length) {
            val step = typewriterStepSize(targetText.length - cursor)
            val nextCursor = (cursor + step).coerceAtMost(targetText.length)
            renderedText = targetText.substring(0, nextCursor)
            val pauseChar = targetText.getOrNull(nextCursor - 1) ?: ' '
            cursor = nextCursor
            delay(typewriterDelayMs(pauseChar))
        }
    }

    // Incremental parse: stable blocks keep the same reference when unchanged
    val (stableBlocks, tailBlock) = remember(renderedText) {
        cache.computeAndReturn(renderedText)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (stableBlocks.isEmpty() && tailBlock == null) {
            if (active) {
                ShimmerBlock(modifier = Modifier.fillMaxWidth())
            } else {
                TimelineNoticeCard(
                    title = "没有可见输出",
                    message = "这次运行没有返回文本内容，但它仍然会保留在当前回合中。",
                    footer = "你仍然可以重试本轮或回填提示词。",
                    tone = TimelineNoticeTone.Neutral,
                    stateLabel = "空输出",
                )
            }
        } else {
            // Stable blocks — only recompose when a new block is finalized
            for (block in stableBlocks) {
                key(block.id) {
                    AnimatedBlock(id = block.id) {
                        RenderSingleBlock(block, cursorAlpha = 0f)
                    }
                }
            }
            // Tail block — recomposes on every typewriter tick
            tailBlock?.let { block ->
                key(block.id) {
                    AnimatedBlock(id = block.id) {
                        RenderSingleBlock(
                            block = block,
                            cursorAlpha = if (active) cursorAlpha else 0f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderSingleBlock(block: RichTextBlock, cursorAlpha: Float) {
    when (block) {
        is RichTextBlock.Paragraph -> ParagraphBlock(block, cursorAlpha)
        is RichTextBlock.CodeBlock -> RichCodeBlock(block = block, cursorAlpha = cursorAlpha)
        is RichTextBlock.ListBlock -> RichListBlock(block, cursorAlpha)
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimelineNoticeCard(
            title = if (draft) "等待首条消息" else "等待新的回复",
            message = if (draft) {
                "输入首条消息后，这里会开始显示当前会话的运行过程。"
            } else {
                "发送一条消息后，回复和运行状态会在这里展开。"
            },
            footer = if (draft) {
                "首条消息会创建会话并立即进入处理流程。"
            } else {
                "当前没有活跃运行时，这里会保持安静而不突兀。"
            },
            tone = TimelineNoticeTone.Neutral,
            stateLabel = if (draft) "待发送" else "空闲",
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
