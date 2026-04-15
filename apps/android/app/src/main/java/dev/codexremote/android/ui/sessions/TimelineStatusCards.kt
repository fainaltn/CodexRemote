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
    liveRunStatus == null && isDraft && sending -> "等待创建"
    liveRunStatus == null && isDraft -> "待发送"
    liveRunStatus == null -> null
    liveRunStatus == "pending" -> if (sending) "等待调度" else "等待中"
    liveRunStatus == "running" && !hasVisibleOutput && liveStreamConnected -> "思考中"
    liveRunStatus == "running" && hasVisibleOutput && liveStreamConnected -> "流式输出"
    liveRunStatus == "running" && !liveStreamConnected -> "后台执行"
    liveRunStatus == "completed" -> "已完成"
    liveRunStatus == "failed" -> "失败"
    liveRunStatus == "stopped" -> "已停止"
    else -> statusLabel(liveRunStatus)
}

internal fun currentRunSummaryTitle(
    liveRunStatus: String?,
    hasVisibleOutput: Boolean,
    isDraft: Boolean,
    sending: Boolean,
): String = when {
    liveRunStatus == null && isDraft && sending -> "正在创建会话"
    liveRunStatus == null && isDraft -> "等待首条消息"
    liveRunStatus == null && hasVisibleOutput -> "本轮输出"
    liveRunStatus == null -> "当前运行"
    liveRunStatus == "running" -> "当前运行"
    liveRunStatus == "pending" -> "运行排队中"
    liveRunStatus == "completed" -> "本轮已结束"
    liveRunStatus == "failed" -> "本轮运行失败"
    liveRunStatus == "stopped" -> "本轮已停止"
    else -> "当前运行"
}

internal fun currentRunSummaryMessage(
    liveRunStatus: String?,
    hasVisibleOutput: Boolean,
    liveStreamConnected: Boolean,
    liveStreamStatus: String?,
    isDraft: Boolean,
): String = when {
    liveRunStatus == null && isDraft ->
        "输入首条消息后，这里会展示实时流、终端输出和本轮状态。"
    liveRunStatus == null && hasVisibleOutput ->
        "这次运行已经结束，下面保留本轮最终输出和上下文。"
    liveRunStatus == "pending" ->
        "消息已经进入队列，等待服务端开始处理。"
    liveRunStatus == "running" && !hasVisibleOutput && liveStreamConnected ->
        "AI 正在思考并整理首段输出，内容会随着流式结果持续追加。"
    liveRunStatus == "running" && hasVisibleOutput && liveStreamConnected ->
        "当前显示的是最新可见内容，后续输出会继续追加在这里。"
    liveRunStatus == "running" && !liveStreamConnected ->
        "实时流暂时不可用，界面会继续刷新补齐内容。"
    liveRunStatus == "completed" ->
        "本轮已经完成，下面保留最终输出和历史上下文。"
    liveRunStatus == "failed" ->
        "本轮未正常结束，下面保留最后一次可见输出以便排查。"
    liveRunStatus == "stopped" ->
        "本轮已经被停止，下面保留停止前的内容。"
    else -> liveStreamStatus ?: "这里会同步当前运行、思考过程和最终输出。"
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
    "failed" -> "运行失败"
    "stopped" -> "运行已停止"
    else -> statusLabel(status)
}

internal fun terminalRunMessage(
    status: String,
    error: String?,
): String = when (status) {
    "failed" -> error ?: "本次运行未完成，下面保留最后一次可见输出。"
    "stopped" -> error ?: "本次运行已被停止。"
    else -> error ?: "本次运行已结束。"
}
