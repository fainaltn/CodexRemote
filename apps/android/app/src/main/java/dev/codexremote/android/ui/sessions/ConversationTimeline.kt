package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R
import dev.codexremote.android.data.model.Run

private const val HISTORY_RENDER_WINDOW_INITIAL_ROUNDS = 3
private const val HISTORY_RENDER_WINDOW_EXPAND_STEP = 8

private fun isRecoveringStreamStatus(liveStreamStatus: String?): Boolean {
    val status = liveStreamStatus?.lowercase().orEmpty()
    return status.contains("recover") ||
        status.contains("fallback") ||
        status.contains("reconnect") ||
        status.contains("sync") ||
        status.contains("恢复") ||
        status.contains("重连") ||
        status.contains("同步") ||
        status.contains("刷新")
}

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
    hasMoreHistory: Boolean,
    loadingOlderHistory: Boolean,
    onLoadOlderHistory: () -> Unit,
    onRetry: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
    onDownloadFile: (OutputFileReference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveRunStatus = liveRun?.status
    val isActive = liveRunStatus in activeRunStatuses
    val projection = remember(
        historyRounds,
        liveRun,
        latestUserPrompt,
        latestAssistantReply,
        cleanedOutput,
        pendingTurnPrompt,
        retainedLiveOutput,
        isDraft,
    ) {
        buildCurrentTurnProjection(
            messages = historyRounds.flatMap { it.messages },
            liveRun = liveRun,
            pendingTurnPrompt = pendingTurnPrompt,
            cleanedOutput = cleanedOutput,
            retainedLiveOutput = retainedLiveOutput,
            isDraft = isDraft,
        )
    }
    var currentTurnExpanded by remember(
        projection.collapsedAssistantMessages.size,
        projection.visibleSettledAssistantMessages.lastOrNull()?.id,
        projection.userPrompt,
    ) { mutableStateOf(false) }
    val showAssistantReply = projection.showWaitingPlaceholder ||
        projection.visibleSettledAssistantMessages.isNotEmpty() ||
        !projection.streamingTailText.isNullOrBlank() ||
        !projection.finalReplyFallbackText.isNullOrBlank() ||
        projection.showThinkingState ||
        isDraft ||
        liveRun != null
    val showStreamRecoveryCard = isActive && !liveStreamConnected && !liveStreamStatus.isNullOrBlank()
    val isStreamRecovering = isRecoveringStreamStatus(liveStreamStatus)
    val runStateLabel = currentRunStateLabel(
        liveRunStatus = liveRunStatus,
        hasVisibleOutput = projection.visibleSettledAssistantMessages.isNotEmpty() ||
            !projection.streamingTailText.isNullOrBlank() ||
            !projection.finalReplyFallbackText.isNullOrBlank(),
        liveStreamConnected = liveStreamConnected,
        sending = sending,
        isDraft = isDraft,
    )

    // Only show historical rounds in the history section;
    // the current (non-historical) round is rendered separately below.
    val historicalRounds = historyRounds.filter { it.isHistorical }
    val historyWindowKey = historicalRounds.lastOrNull()?.id ?: "history-window"
    var revealedHistoricalRoundCount by rememberSaveable(historyWindowKey) {
        mutableStateOf(HISTORY_RENDER_WINDOW_INITIAL_ROUNDS)
    }
    val visibleHistoricalRoundCount = historicalRounds.size.coerceAtMost(
        revealedHistoricalRoundCount.coerceAtLeast(HISTORY_RENDER_WINDOW_INITIAL_ROUNDS),
    )
    val hiddenHistoricalRoundCount = (historicalRounds.size - visibleHistoricalRoundCount).coerceAtLeast(0)
    val visibleHistoricalRounds = if (hiddenHistoricalRoundCount > 0) {
        historicalRounds.takeLast(visibleHistoricalRoundCount)
    } else {
        historicalRounds
    }
    val historyRevealBatchCount = hiddenHistoricalRoundCount.coerceAtMost(HISTORY_RENDER_WINDOW_EXPAND_STEP)
    val historySubtitle = when {
        historicalRounds.isEmpty() -> stringResource(R.string.session_timeline_history_subtitle_empty)
        hiddenHistoricalRoundCount > 0 -> stringResource(R.string.session_timeline_history_windowed_subtitle)
        hasMoreHistory -> stringResource(R.string.session_timeline_history_partial_subtitle)
        else -> stringResource(R.string.session_timeline_history_subtitle_with_rounds)
    }
    val historyStateLabel = when {
        historicalRounds.isEmpty() -> stringResource(R.string.session_timeline_history_empty_state_label)
        hasMoreHistory -> stringResource(R.string.session_timeline_history_partial_state_label)
        else -> stringResource(R.string.session_timeline_history_complete_state_label)
    }
    val historyTone = if (hasMoreHistory) TimelineNoticeTone.Warning else TimelineNoticeTone.Neutral
    val historySummaryLabel = when {
        historicalRounds.isEmpty() -> historyStateLabel
        hiddenHistoricalRoundCount > 0 -> {
            "${pluralStringResource(
                R.plurals.session_timeline_history_round_count,
                historicalRounds.size,
                historicalRounds.size,
            )} · ${stringResource(
                R.string.session_timeline_history_windowed_state_label,
                visibleHistoricalRoundCount,
                historicalRounds.size,
            )}"
        }
        else -> {
            "${pluralStringResource(
                R.plurals.session_timeline_history_round_count,
                historicalRounds.size,
                historicalRounds.size,
            )} · $historyStateLabel"
        }
    }

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
                    subtitle = historySubtitle,
                    stateLabel = historySummaryLabel,
                    tone = historyTone,
                )
            }

            if (hasMoreHistory) {
                item(key = "history-load-older") {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TextButton(
                            onClick = onLoadOlderHistory,
                            enabled = !loadingOlderHistory,
                        ) {
                            Text(
                                if (loadingOlderHistory) {
                                    stringResource(R.string.session_timeline_history_load_older_loading)
                                } else {
                                    stringResource(R.string.session_timeline_history_load_older)
                                },
                            )
                        }
                    }
                }
            }

            if (hiddenHistoricalRoundCount > 0) {
                item(key = "history-windowed") {
                    TimelineNoticeCard(
                        title = stringResource(R.string.session_timeline_history_windowed_title),
                        message = stringResource(
                            R.string.session_timeline_history_windowed_message,
                            hiddenHistoricalRoundCount,
                        ),
                        footer = stringResource(R.string.session_timeline_history_windowed_footer),
                        tone = TimelineNoticeTone.Neutral,
                        stateLabel = stringResource(
                            R.string.session_timeline_history_windowed_state_label,
                            visibleHistoricalRoundCount,
                            historicalRounds.size,
                        ),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            TextButton(
                                onClick = {
                                    revealedHistoricalRoundCount += HISTORY_RENDER_WINDOW_EXPAND_STEP
                                },
                            ) {
                                Text(
                                    stringResource(
                                        R.string.session_timeline_history_windowed_expand,
                                        historyRevealBatchCount,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (visibleHistoricalRounds.isNotEmpty()) {
                items(
                    visibleHistoricalRounds,
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
            if (!projection.userPrompt.isNullOrBlank()) {
                item(key = "current-user") {
                    UserMessageBubble(text = projection.userPrompt)
                }
            }

            // ③ Connection degradation notice (only when SSE lost during active run)
            if (showStreamRecoveryCard) {
                item(key = "connection-notice") {
                    TimelineNoticeCard(
                        title = if (isStreamRecovering) {
                            stringResource(R.string.session_timeline_stream_recovering_title)
                        } else {
                            stringResource(R.string.session_timeline_stream_degraded_title)
                        },
                        message = liveStreamStatus.orEmpty(),
                        footer = if (isStreamRecovering) {
                            stringResource(R.string.session_timeline_stream_recovering_footer)
                        } else {
                            stringResource(R.string.session_timeline_stream_degraded_footer)
                        },
                        tone = if (isStreamRecovering) TimelineNoticeTone.Neutral else TimelineNoticeTone.Warning,
                        stateLabel = if (isStreamRecovering) {
                            stringResource(R.string.session_timeline_stream_recovering_state_label)
                        } else {
                            stringResource(R.string.session_timeline_stream_degraded_state_label)
                        },
                        stateTone = if (isStreamRecovering) TimelineNoticeTone.Neutral else TimelineNoticeTone.Warning,
                    )
                }
            }

            // ④ AI reply (hero block)
            if (showAssistantReply) {
                item(key = "assistant-reply") {
                    if (projection.showWaitingPlaceholder) {
                        WaitingReplyPlaceholder(draft = isDraft)
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (projection.collapsedAssistantMessages.isNotEmpty()) {
                                CurrentTurnCollapsedHeader(
                                    messageCount = projection.collapsedAssistantMessages.size,
                                    expanded = currentTurnExpanded,
                                    processedDuration = liveRun?.startedAt?.let {
                                        formatRunElapsed(it, liveRun.finishedAt)
                                    },
                                    onToggle = { currentTurnExpanded = !currentTurnExpanded },
                                )
                            }

                            if (currentTurnExpanded) {
                                projection.collapsedAssistantMessages.forEach { message ->
                                    SettledAssistantMessageCard(
                                        message = message,
                                        collapsed = true,
                                    )
                                }
                            }

                            projection.visibleSettledAssistantMessages.forEach { message ->
                                SettledAssistantMessageCard(
                                    message = message,
                                    collapsed = false,
                                )
                            }

                            if (!projection.streamingTailText.isNullOrBlank()) {
                                AssistantReplyBlock(
                                    output = projection.streamingTailText,
                                    isActive = true,
                                    status = liveRun?.status,
                                    model = liveRun?.model,
                                    startedAt = liveRun?.startedAt,
                                    finishedAt = liveRun?.finishedAt,
                                    error = liveRun?.error,
                                    sending = sending,
                                    onRetry = null,
                                    onReusePrompt = null,
                                    onDownloadFile = onDownloadFile,
                                )
                            }

                            if (projection.showThinkingState) {
                                ThinkingPlaceholderCard(
                                    compact = projection.visibleSettledAssistantMessages.isNotEmpty(),
                                )
                            }

                            if (projection.visibleSettledAssistantMessages.isEmpty() &&
                                projection.streamingTailText.isNullOrBlank() &&
                                !projection.finalReplyFallbackText.isNullOrBlank()
                            ) {
                                AssistantReplyBlock(
                                    output = projection.finalReplyFallbackText,
                                    isActive = false,
                                    status = liveRun?.status,
                                    model = liveRun?.model,
                                    startedAt = liveRun?.startedAt,
                                    finishedAt = liveRun?.finishedAt,
                                    error = liveRun?.error,
                                    sending = sending,
                                    onRetry = if (!liveRun?.prompt.isNullOrBlank()) {
                                        { onRetry(liveRun!!.prompt) }
                                    } else {
                                        null
                                    },
                                    onReusePrompt = if (!liveRun?.prompt.isNullOrBlank()) {
                                        { onReusePrompt(liveRun!!.prompt) }
                                    } else {
                                        null
                                    },
                                    onDownloadFile = onDownloadFile,
                                )
                            }
                        }
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
