package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexremote.android.data.model.Run

/**
 * The conversation timeline — a chat-like LazyColumn that displays:
 * 1. Collapsed history rounds (tap to expand)
 * 2. The current-turn user message
 * 3. The AI reply block (hero area)
 * 4. Action rows / error states
 *
 * This composable owns the LazyColumn layout but NOT the scroll
 * state or auto-follow logic (those live in the parent).
 */
@Composable
internal fun ConversationTimeline(
    listState: LazyListState,
    historyRounds: List<HistoryRound>,
    expandedRounds: Set<String>,
    onToggleRound: (String) -> Unit,
    liveRun: Run?,
    latestUserPrompt: String?,
    latestAssistantReply: String?,
    cleanedOutput: String?,
    sending: Boolean,
    isDraft: Boolean,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    onRetry: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = liveRun?.status in activeRunStatuses

    val currentUserText = when {
        liveRun == null -> latestUserPrompt
        isActive -> sanitizePromptDisplay(liveRun.prompt)
        else -> latestUserPrompt ?: sanitizePromptDisplay(liveRun.prompt)
    }

    val replyOutput = when {
        liveRun == null -> latestAssistantReply ?: cleanedOutput
        isActive -> cleanedOutput
        else -> latestAssistantReply ?: cleanedOutput
    }

    val showWaitingPlaceholder = liveRun == null && replyOutput.isNullOrBlank()
    val showAssistantReply = isDraft ||
        liveRun != null ||
        !replyOutput.isNullOrBlank() ||
        showWaitingPlaceholder
    val showStreamDegradedCard = isActive && !liveStreamConnected && !liveStreamStatus.isNullOrBlank()

    // Only show historical rounds in the history section;
    // the current (non-historical) round is rendered separately below.
    val historicalRounds = historyRounds.filter { it.isHistorical }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ① History rounds (only past rounds, not the current turn)
            if (historicalRounds.isNotEmpty()) {
                items(
                    historicalRounds,
                    key = { it.id },
                    contentType = { "history-round" },
                ) { round ->
                    HistoryRoundItem(
                        round = round,
                        expanded = expandedRounds.contains(round.id),
                        onToggle = { onToggleRound(round.id) },
                    )
                }

                // Separator between history and current turn
                item(key = "history-divider") {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }

            // ② Current-turn user message
            if (!currentUserText.isNullOrBlank()) {
                item(key = "current-user") {
                    UserMessageBubble(text = currentUserText)
                }
            }

            // ③ Connection degradation notice (only when SSE lost during active run)
            if (showStreamDegradedCard) {
                item(key = "connection-notice") {
                    TimelineNoticeCard(
                        title = "实时流已降级",
                        message = liveStreamStatus.orEmpty(),
                        footer = "会话仍在继续，界面会自动刷新补齐内容。",
                        tone = TimelineNoticeTone.Warning,
                    )
                }
            }

            // ④ AI reply (hero block)
            if (showAssistantReply) {
                item(key = "assistant-reply") {
                    if (showWaitingPlaceholder) {
                        WaitingReplyPlaceholder(draft = isDraft)
                    } else {
                        AssistantReplyBlock(
                            output = replyOutput,
                            isActive = isActive,
                            status = liveRun?.status,
                            model = liveRun?.model,
                            startedAt = liveRun?.startedAt,
                            finishedAt = liveRun?.finishedAt,
                            error = liveRun?.error,
                            sending = sending,
                            onRetry = if (!isActive && !liveRun?.prompt.isNullOrBlank()) {
                                { onRetry(liveRun!!.prompt) }
                            } else {
                                null
                            },
                            onReusePrompt = if (!isActive && !liveRun?.prompt.isNullOrBlank()) {
                                { onReusePrompt(liveRun!!.prompt) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            // ⑤ Bottom anchor
            item(key = "bottom-anchor") {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
