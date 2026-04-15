package dev.codexremote.android.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R
import kotlinx.coroutines.delay

@Composable
fun RichBlockList(
    text: String?,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    emptyActiveMessage: String? = null,
    emptyIdleMessage: String? = null,
    showCursor: Boolean = false,
    cursorAlpha: Float = 0f,
) {
    val blocks = remember(text) { parseTextToBlocks(text) }
    val activeMessage = emptyActiveMessage ?: stringResource(R.string.session_timeline_empty_active_message)
    val idleMessage = emptyIdleMessage ?: stringResource(R.string.session_timeline_empty_idle_message)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (blocks.isEmpty()) {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append(if (active) activeMessage else idleMessage)
                    if (showCursor) {
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha)
                            )
                        ) {
                            append("▍")
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            AnimatedBlock(id = block.id) {
                when (block) {
                    is RichTextBlock.Paragraph -> ParagraphBlock(block, if (isLast && showCursor) cursorAlpha else 0f)
                    is RichTextBlock.CodeBlock -> RichCodeBlock(block = block, cursorAlpha = if (isLast && showCursor) cursorAlpha else 0f)
                    is RichTextBlock.ListBlock -> RichListBlock(block, if (isLast && showCursor) cursorAlpha else 0f)
                }
            }
        }
    }
}

@Composable
internal fun AnimatedBlock(
    id: String,
    content: @Composable () -> Unit,
) {
    val visibleState = remember(id) {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
internal fun ParagraphBlock(block: RichTextBlock.Paragraph, cursorAlpha: Float) {
    SelectionContainer {
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                append(block.text)
                if (cursorAlpha > 0f) {
                    withStyle(
                        SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))
                    ) {
                        append("▍")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun RichListBlock(block: RichTextBlock.ListBlock, cursorAlpha: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (block.ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                SelectionContainer {
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append(item)
                            if (index == block.items.lastIndex && cursorAlpha > 0f) {
                                withStyle(
                                    SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))
                                ) {
                                    append("▍")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f, fill = true),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RichCodeBlock(
    block: RichTextBlock.CodeBlock,
    cursorAlpha: Float,
) {
    val clipboardManager = LocalClipboardManager.current
    val codeLanguageLabel = stringResource(R.string.session_timeline_code_language_text)
    val copiedLabel = stringResource(R.string.session_timeline_code_copied)
    val copyLabel = stringResource(R.string.session_timeline_message_copy)
    val outputContinuesLabel = stringResource(R.string.session_timeline_output_continues)
    var copied by remember(block.id) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = block.language?.takeIf { it.isNotBlank() } ?: codeLanguageLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    enabled = false,
                    shape = RoundedCornerShape(999.dp),
                    border = null,
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(block.code))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (copied) copiedLabel else copyLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                SelectionContainer {
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append(block.code.ifBlank { " " })
                            if (cursorAlpha > 0f) {
                                withStyle(
                                    SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))
                                ) {
                                    append("▍")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .heightIn(min = 24.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (block.isOpenEnded) {
                Text(
                    text = outputContinuesLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
