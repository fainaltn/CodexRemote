package dev.codexremote.android.ui.sessions

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ── User message bubble ───────────────────────────────────────────

/**
 * Renders a user's message in a right-aligned bubble.
 * Used both in history rounds and for the current-turn prompt.
 */
@Composable
internal fun UserMessageBubble(
    text: String,
    timestamp: String? = null,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val displayText = remember(text) { sanitizePromptDisplay(text) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = if (dimmed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
}

// ── AI reply block ────────────────────────────────────────────────

/**
 * The hero composable: shows the AI's response with a primary-color accent bar,
 * rich text blocks, typewriter cursor, and micro-status footer.
 */
@Composable
internal fun AssistantReplyBlock(
    output: String?,
    isActive: Boolean,
    model: String? = null,
    startedAt: String? = null,
    finishedAt: String? = null,
    error: String? = null,
    sending: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onReusePrompt: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ),
    ) {
        // Accent bar + content
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent bar visible only while active
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 48.dp)
                    .drawBehind {
                        if (isActive) {
                            drawRect(accentColor)
                        }
                    }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Streaming text with typewriter
                StreamingTextRenderer(
                    targetText = output.orEmpty(),
                    active = isActive,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Error inline
                error?.let { errorMsg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = errorMsg,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                // Micro status
                MicroStatusRow(
                    model = model,
                    elapsed = startedAt?.let { formatRunElapsed(it, finishedAt) },
                    isActive = isActive,
                )

                // Action buttons (only when run is done and has content)
                if (!isActive && onRetry != null && onReusePrompt != null) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
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
                Text(
                    text = "这次运行没有可显示的文本输出",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun ShimmerBlock(
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
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (draft) {
                "输入首条消息后会立即开始处理"
            } else {
                "发送一条消息来继续对话"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!draft) {
            ShimmerBlock(lines = 2)
        }
    }
}
