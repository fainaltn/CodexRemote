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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R
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
    pendingTurnPrompt: String?,
    retainedLiveOutput: String?,
    sending: Boolean,
    isDraft: Boolean,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    onRetry: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveRunStatus = liveRun?.status
    val isActive = liveRunStatus in activeRunStatuses
    val pendingUserText = pendingTurnPrompt?.trim()?.ifBlank { null }
    val latestTimelineUser = latestUserPrompt?.trim()?.ifBlank { null }
    val latestTimelineUserDisplay = latestTimelineUser?.let(::sanitizePromptDisplay)
    val continuousLiveOutput = cleanedOutput ?: retainedLiveOutput

    val currentUserText = when {
        liveRun != null && isActive -> sanitizePromptDisplay(liveRun.prompt)
        liveRun != null -> pendingUserText ?: latestTimelineUserDisplay ?: sanitizePromptDisplay(liveRun.prompt)
        !pendingUserText.isNullOrBlank() -> pendingUserText
        else -> latestTimelineUserDisplay
    }

    val currentTurnStoredReply = if (
        !currentUserText.isNullOrBlank() &&
        latestTimelineUserDisplay == currentUserText
    ) {
        latestAssistantReply
    } else {
        null
    }

    val replyOutput = when {
        isActive -> continuousLiveOutput
        liveRun != null || !pendingUserText.isNullOrBlank() -> currentTurnStoredReply ?: continuousLiveOutput
        else -> latestAssistantReply ?: continuousLiveOutput
    }

    val showWaitingPlaceholder = replyOutput.isNullOrBlank() &&
        (isDraft || liveRun != null || !currentUserText.isNullOrBlank())
    val showAssistantReply = isDraft ||
        liveRun != null ||
        !pendingUserText.isNullOrBlank() ||
        !replyOutput.isNullOrBlank() ||
        showWaitingPlaceholder
    val showStreamDegradedCard = isActive && !liveStreamConnected && !liveStreamStatus.isNullOrBlank()
    val runStateLabel = currentRunStateLabel(
        liveRunStatus = liveRunStatus,
        hasVisibleOutput = !replyOutput.isNullOrBlank(),
        liveStreamConnected = liveStreamConnected,
        sending = sending,
        isDraft = isDraft,
    )

    // Only show historical rounds in the history section;
    // the current (non-historical) round is rendered separately below.
    val historicalRounds = historyRounds.filter { it.isHistorical }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "history-header") {
                TimelineSectionHeader(
                    title = stringResource(R.string.session_timeline_history_title),
                    subtitle = if (historicalRounds.isNotEmpty()) {
                        stringResource(R.string.session_timeline_history_subtitle_with_rounds)
                    } else {
                        stringResource(R.string.session_timeline_history_subtitle_empty)
                    },
                    stateLabel = if (historicalRounds.isNotEmpty()) {
                        pluralStringResource(
                            R.plurals.session_timeline_history_round_count,
                            historicalRounds.size,
                            historicalRounds.size,
                        )
                    } else {
                        stringResource(R.string.session_timeline_history_empty_state_label)
                    },
                )
            }

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
            } else {
                item(key = "history-empty") {
                    TimelineNoticeCard(
                        title = stringResource(R.string.session_timeline_history_empty_title),
                        message = stringResource(R.string.session_timeline_history_empty_message),
                        footer = stringResource(R.string.session_timeline_history_empty_footer),
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = stringResource(R.string.session_timeline_history_empty_state_label),
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
                        title = stringResource(R.string.session_timeline_stream_degraded_title),
                        message = liveStreamStatus.orEmpty(),
                        footer = stringResource(R.string.session_timeline_stream_degraded_footer),
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
