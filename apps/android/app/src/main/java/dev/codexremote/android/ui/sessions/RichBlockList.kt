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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
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
                    is RichTextBlock.MemoryCitation -> MemoryCitationBlock(block)
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
    LinkifiedBodyText(
        text = block.text,
        modifier = Modifier.fillMaxWidth(),
        cursorAlpha = cursorAlpha,
        textStyle = MaterialTheme.typography.bodyLarge,
    )
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
                LinkifiedBodyText(
                    text = item,
                    modifier = Modifier.weight(1f, fill = true),
                    cursorAlpha = if (index == block.items.lastIndex) cursorAlpha else 0f,
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
internal fun MemoryCitationBlock(block: RichTextBlock.MemoryCitation) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    val summaryLabel = remember(block.entries.size) {
        buildString {
            append(block.entries.size)
            append(" memory citation")
            if (block.entries.size != 1) {
                append("s")
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (expanded) "⌄" else "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = summaryLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (expanded) {
                block.entries.forEach { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = formatMemoryCitationEntryLabel(entry),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            entry.note?.let { note ->
                                SelectionContainer {
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMemoryCitationEntryLabel(entry: MemoryCitationEntry): String {
    val start = entry.lineStart
    val end = entry.lineEnd
    return when {
        start != null && end != null -> "${entry.file}  lines $start-$end"
        start != null -> "${entry.file}  line $start"
        else -> entry.file
    }
}

@Composable
private fun LinkifiedBodyText(
    text: String,
    modifier: Modifier = Modifier,
    cursorAlpha: Float = 0f,
    textStyle: TextStyle,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val cursorColor = MaterialTheme.colorScheme.primary
    val annotatedText = remember(text, cursorAlpha) {
        buildLinkifiedAnnotatedText(
            text = text,
            cursorAlpha = cursorAlpha,
            linkColor = linkColor,
            cursorColor = cursorColor,
        )
    }
    val hasLinks = remember(annotatedText) {
        annotatedText.getStringAnnotations(
            tag = SESSION_LINK_ANNOTATION_TAG,
            start = 0,
            end = annotatedText.length,
        ).isNotEmpty()
    }

    if (!hasLinks) {
        SelectionContainer {
            Text(
                text = annotatedText,
                modifier = modifier,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sessionLinkHandler = LocalSessionFileLinkHandler.current
    var pendingRequest by remember { mutableStateOf<SessionFileLinkRequest?>(null) }
    var resolvedLink by remember { mutableStateOf<SessionFileLinkResolution?>(null) }

    LaunchedEffect(pendingRequest) {
        val request = pendingRequest ?: return@LaunchedEffect
        resolvedLink = resolveSessionFileLink(context, request)
        pendingRequest = null
    }

    ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            val end = (offset + 1).coerceAtMost(annotatedText.length)
            annotatedText.getStringAnnotations(
                tag = SESSION_LINK_ANNOTATION_TAG,
                start = offset,
                end = end,
            ).firstOrNull()?.let { annotation ->
                val displayLabel = annotatedText.text.substring(annotation.start, annotation.end)
                val request = SessionFileLinkRequest(
                    target = annotation.item,
                    displayLabel = displayLabel,
                )
                if (sessionLinkHandler != null && isLikelyHostAbsolutePath(request.target)) {
                    sessionLinkHandler(request)
                } else {
                    pendingRequest = request
                }
            }
        },
    )

    resolvedLink?.let { resolution ->
        SessionFileLinkActionSheet(
            resolution = resolution,
            onDismiss = { resolvedLink = null },
            onOpen = {
                openSessionFileLink(context, resolution)
                resolvedLink = null
            },
            onShare = {
                shareSessionFileLink(context, resolution)
                resolvedLink = null
            },
            onCopy = {
                clipboardManager.setText(AnnotatedString(resolution.target))
                resolvedLink = null
            },
        )
    }
}

private data class InlineLinkMatch(
    val start: Int,
    val end: Int,
    val displayText: String,
    val target: String,
)

private val markdownLinkRegex = Regex("""\[(.+?)]\((.+?)\)""")
private val labeledPathRegex = Regex("""(?i)(absolute path|path|file|stored path|source path)\s*[:：]\s*((?:[A-Za-z]:[\\/]|/)[^\s<>\]]+)""")
private val rawUrlRegex = Regex("""(?<!\w)(https?://[^\s<>\])]+)""")
private val fileUriRegex = Regex("""(?<!\w)(file://[^\s<>\])]+|content://[^\s<>\])]+)""")
private val absolutePathRegex = Regex("""(?<!\w)((?:[A-Za-z]:[\\/]|/)[^\s<>\]]+\.[A-Za-z0-9]{1,12})(?!\w)""")

private fun buildLinkifiedAnnotatedText(
    text: String,
    cursorAlpha: Float,
    linkColor: Color,
    cursorColor: Color,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var index = 0

    while (index < text.length) {
        val nextMatch = nextInlineLinkMatch(text, index)
        if (nextMatch == null) {
            builder.append(text.substring(index))
            break
        }

        if (nextMatch.start > index) {
            builder.append(text.substring(index, nextMatch.start))
        }

        val start = builder.length
        builder.withStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium,
            ),
        ) {
            append(nextMatch.displayText)
        }
        val end = builder.length
        builder.addStringAnnotation(
            tag = SESSION_LINK_ANNOTATION_TAG,
            annotation = nextMatch.target,
            start = start,
            end = end,
        )
        index = nextMatch.end
    }

    if (cursorAlpha > 0f) {
        builder.withStyle(
            SpanStyle(color = cursorColor.copy(alpha = cursorAlpha)),
        ) {
            append("▍")
        }
    }

    return builder.toAnnotatedString()
}

private fun nextInlineLinkMatch(
    text: String,
    fromIndex: Int,
): InlineLinkMatch? {
    val candidates = buildList {
        markdownLinkRegex.find(text, fromIndex)?.let { match ->
            add(
                InlineLinkMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    displayText = match.groupValues.getOrNull(1).orEmpty(),
                    target = match.groupValues.getOrNull(2).orEmpty(),
                )
            )
        }

        labeledPathRegex.find(text, fromIndex)?.let { match ->
            add(
                InlineLinkMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    displayText = match.groupValues.getOrNull(2).orEmpty(),
                    target = match.groupValues.getOrNull(2).orEmpty(),
                )
            )
        }

        fileUriRegex.find(text, fromIndex)?.let { match ->
            add(
                InlineLinkMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    displayText = match.value,
                    target = match.value,
                )
            )
        }

        rawUrlRegex.find(text, fromIndex)?.let { match ->
            add(
                InlineLinkMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    displayText = match.value,
                    target = match.value,
                )
            )
        }

        absolutePathRegex.find(text, fromIndex)?.let { match ->
            add(
                InlineLinkMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    displayText = match.value,
                    target = match.value,
                )
            )
        }
    }

    return candidates.minWithOrNull(
        compareBy<InlineLinkMatch> { it.start }
            .thenBy { priorityForCandidate(it, text, fromIndex) },
    )
}

private fun priorityForCandidate(
    candidate: InlineLinkMatch,
    text: String,
    fromIndex: Int,
): Int {
    val slice = text.substring(candidate.start, candidate.end)
    return when {
        markdownLinkRegex.matches(slice) -> 0
        labeledPathRegex.matches(slice) -> 1
        fileUriRegex.matches(slice) -> 2
        rawUrlRegex.matches(slice) -> 3
        else -> 4
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
