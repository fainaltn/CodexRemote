package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

enum class TimelineNoticeTone {
    Neutral,
    Warning,
    Error,
}

@Composable
fun TimelineNoticeCard(
    title: String,
    message: String,
    tone: TimelineNoticeTone,
    modifier: Modifier = Modifier,
    footer: String? = null,
    stateLabel: String? = null,
    stateTone: TimelineNoticeTone = tone,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val containerColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondaryContainer
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val accentColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.primary
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondary
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .width(4.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor,
                        )
                        stateLabel?.takeIf { it.isNotBlank() }?.let {
                            NoticeStateBadge(
                                text = it,
                                containerColor = when (stateTone) {
                                    TimelineNoticeTone.Neutral -> contentColor.copy(alpha = 0.10f)
                                    TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                                    TimelineNoticeTone.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                                },
                                contentColor = when (stateTone) {
                                    TimelineNoticeTone.Neutral -> contentColor
                                    TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
                                    TimelineNoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
                                },
                            )
                        }
                    }
                    footer?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.74f),
                        )
                    }
                }
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )

            content()
        }
    }
}

@Composable
internal fun TimelineSectionHeader(
    title: String,
    subtitle: String,
    stateLabel: String? = null,
    tone: TimelineNoticeTone = TimelineNoticeTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.60f)
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.70f)
    }
    val contentColor = when (tone) {
        TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.onSurface
        TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
        TimelineNoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when (tone) {
                            TimelineNoticeTone.Neutral -> MaterialTheme.colorScheme.primary
                            TimelineNoticeTone.Warning -> MaterialTheme.colorScheme.secondary
                            TimelineNoticeTone.Error -> MaterialTheme.colorScheme.error
                        },
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.72f),
                )
            }
            stateLabel?.takeIf { it.isNotBlank() }?.let {
                NoticeStateBadge(
                    text = it,
                    containerColor = contentColor.copy(alpha = 0.10f),
                    contentColor = contentColor,
                )
            }
        }
    }
}

@Composable
private fun NoticeStateBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
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
        )
    }
}

internal fun currentRunStateLabel(
    liveRunStatus: String?,
    hasVisibleOutput: Boolean,
    liveStreamConnected: Boolean,
    sending: Boolean,
    isDraft: Boolean,
): String? = when {
    liveRunStatus == null && isDraft && sending -> localizedSessionText("等待创建", "Creating")
    liveRunStatus == null && isDraft -> localizedSessionText("待发送", "Queued")
    liveRunStatus == null -> null
    liveRunStatus == "pending" -> if (sending) localizedSessionText("等待调度", "Scheduling") else localizedSessionText("等待中", "Waiting")
    liveRunStatus == "running" && !hasVisibleOutput && liveStreamConnected -> localizedSessionText("思考中", "Thinking")
    liveRunStatus == "running" && hasVisibleOutput && liveStreamConnected -> localizedSessionText("流式输出", "Streaming output")
    liveRunStatus == "running" && !liveStreamConnected -> localizedSessionText("恢复同步", "Recovering sync")
    liveRunStatus == "completed" -> localizedSessionText("已完成", "Completed")
    liveRunStatus == "failed" -> localizedSessionText("失败", "Failed")
    liveRunStatus == "stopped" -> localizedSessionText("已停止", "Stopped")
    else -> statusLabel(liveRunStatus)
}

internal fun currentRunSummaryTitle(
    liveRunStatus: String?,
    hasVisibleOutput: Boolean,
    isDraft: Boolean,
    sending: Boolean,
): String = when {
    liveRunStatus == null && isDraft && sending -> localizedSessionText("正在创建会话", "Creating session")
    liveRunStatus == null && isDraft -> localizedSessionText("等待首条消息", "Waiting for the first message")
    liveRunStatus == null && hasVisibleOutput -> localizedSessionText("本轮输出", "This run's output")
    liveRunStatus == null -> localizedSessionText("当前运行", "Current run")
    liveRunStatus == "running" -> localizedSessionText("当前运行", "Current run")
    liveRunStatus == "pending" -> localizedSessionText("运行排队中", "Run queued")
    liveRunStatus == "completed" -> localizedSessionText("本轮已结束", "This run has finished")
    liveRunStatus == "failed" -> localizedSessionText("本轮运行失败", "This run failed")
    liveRunStatus == "stopped" -> localizedSessionText("本轮已停止", "This run was stopped")
    else -> localizedSessionText("当前运行", "Current run")
}

internal fun currentRunSummaryMessage(
    liveRunStatus: String?,
    hasVisibleOutput: Boolean,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    isDraft: Boolean,
): String = when {
    liveRunStatus == null && isDraft ->
        localizedSessionText(
            "输入首条消息后，这里会展示实时流、终端输出和本轮状态。",
            "After the first message, this area will show the live stream, terminal output, and run status.",
        )
    liveRunStatus == null && hasVisibleOutput ->
        localizedSessionText(
            "这次运行已经结束，下面保留本轮最终输出和上下文。",
            "This run has finished, and the final output and context are kept below.",
        )
    liveRunStatus == "pending" ->
        localizedSessionText(
            "消息已经进入队列，等待服务端开始处理。",
            "The message is queued and waiting for the server to start processing it.",
        )
    liveRunStatus == "running" && !hasVisibleOutput && liveStreamConnected ->
        localizedSessionText(
            "AI 正在思考并整理首段输出，内容会随着流式结果持续追加。",
            "AI is thinking and shaping the first output chunk. More content will keep streaming in.",
        )
    liveRunStatus == "running" && hasVisibleOutput && liveStreamConnected ->
        localizedSessionText(
            "当前显示的是最新可见内容，后续输出会继续追加在这里。",
            "This is the latest visible content, and more output will continue to append here.",
        )
    liveRunStatus == "running" && !liveStreamConnected ->
        localizedSessionText(
            "实时连接暂时断开，界面会继续恢复并自动补齐内容。",
            "The live connection is temporarily interrupted, and the UI will keep recovering and auto-filling content.",
        )
    liveRunStatus == "completed" ->
        localizedSessionText(
            "本轮已经完成，下面保留最终输出和历史上下文。",
            "This run is complete, and the final output plus history context stay below.",
        )
    liveRunStatus == "failed" ->
        localizedSessionText(
            "本轮未正常结束，下面保留最后一次可见输出以便排查。",
            "This run did not finish normally, so the last visible output is kept for troubleshooting.",
        )
    liveRunStatus == "stopped" ->
        localizedSessionText(
            "本轮已经被停止，下面保留停止前的内容。",
            "This run was stopped, and the content before it stopped is kept below.",
        )
    else -> liveStreamStatus ?: localizedSessionText(
        "这里会同步当前运行、思考过程和最终输出。",
        "This area syncs the current run, thinking process, and final output.",
    )
}

internal fun currentRunSummaryFooter(
    liveRunStatus: String?,
    model: String?,
    startedAt: String?,
    finishedAt: String?,
) : String? {
    val parts = buildList {
        model?.takeIf { it.isNotBlank() }?.let { add(it) }
        startedAt?.let { add(formatRunElapsed(it, finishedAt)) }
        liveRunStatus?.takeIf { it.isNotBlank() }?.let { add(statusLabel(it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun terminalRunTitle(status: String): String = when (status) {
    "failed" -> localizedSessionText("运行失败", "Run failed")
    "stopped" -> localizedSessionText("运行已停止", "Run stopped")
    else -> statusLabel(status)
}

internal fun terminalRunMessage(
    status: String,
    error: String?,
): String = when (status) {
    "failed" -> error ?: localizedSessionText("本次运行未完成，下面保留最后一次可见输出。", "This run did not finish, and the last visible output is kept below.")
    "stopped" -> error ?: localizedSessionText("本次运行已被停止。", "This run was stopped.")
    else -> error ?: localizedSessionText("本次运行已结束。", "This run has ended.")
}
