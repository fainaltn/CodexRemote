package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexremote.android.data.model.RepoStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepoStatusSurface(
    repoStatus: RepoStatus?,
    actionsEnabled: Boolean,
    actionBusy: Boolean,
    actionSummary: String?,
    onCreateBranch: () -> Unit,
    onCheckoutBranch: () -> Unit,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    onDismissSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!repoStatusVisible(repoStatus)) return

    val dirtyLabel = repoDirtyLabel(repoStatus)
    val isClean = repoStatus?.isRepo == true &&
        (repoStatus.dirtyCount ?: 0) == 0 &&
        (repoStatus.untrackedCount ?: 0) == 0
    val containerColor = when {
        isClean -> MaterialTheme.colorScheme.surfaceContainerHigh
        !dirtyLabel.isNullOrBlank() ->
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isClean -> MaterialTheme.colorScheme.onSurface
        !dirtyLabel.isNullOrBlank() ->
            MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val accentColor = when {
        isClean -> MaterialTheme.colorScheme.primary
        !dirtyLabel.isNullOrBlank() ->
            MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "仓库状态",
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                RepoStateBadge(
                    text = when {
                        isClean -> "干净"
                        !dirtyLabel.isNullOrBlank() -> "有变更"
                        else -> "已连接"
                    },
                    containerColor = accentColor.copy(alpha = 0.14f),
                    contentColor = contentColor,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repoBranchLabel(repoStatus)?.let { branch ->
                    RepoStateBadge(
                        text = "分支 · $branch",
                        containerColor = contentColor.copy(alpha = 0.08f),
                        contentColor = contentColor,
                    )
                }
                repoRootLabel(repoStatus)?.let { root ->
                    RepoStateBadge(
                        text = "根目录 · $root",
                        containerColor = contentColor.copy(alpha = 0.08f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.aheadBy?.takeIf { it > 0 }?.let { aheadBy ->
                    RepoStateBadge(
                        text = "领先 $aheadBy",
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.behindBy?.takeIf { it > 0 }?.let { behindBy ->
                    RepoStateBadge(
                        text = "落后 $behindBy",
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.stagedCount?.takeIf { it > 0 }?.let { stagedCount ->
                    RepoStateBadge(
                        text = "已暂存 $stagedCount",
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.unstagedCount?.takeIf { it > 0 }?.let { unstagedCount ->
                    RepoStateBadge(
                        text = "未暂存 $unstagedCount",
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.untrackedCount?.takeIf { it > 0 }?.let { untrackedCount ->
                    RepoStateBadge(
                        text = "未跟踪 $untrackedCount",
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
            }

            dirtyLabel?.let { dirtyState ->
                Text(
                    text = dirtyState,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.84f),
                    maxLines = 2,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RepoActionChip(
                    label = "新分支",
                    enabled = actionsEnabled && !actionBusy,
                    onClick = onCreateBranch,
                )
                RepoActionChip(
                    label = "切换分支",
                    enabled = actionsEnabled && !actionBusy,
                    onClick = onCheckoutBranch,
                )
                RepoActionChip(
                    label = "提交",
                    enabled = actionsEnabled && !actionBusy && !isClean,
                    onClick = onCommit,
                )
                RepoActionChip(
                    label = "推送",
                    enabled = actionsEnabled &&
                        !actionBusy &&
                        !repoBranchLabel(repoStatus).isNullOrBlank() &&
                        repoStatus?.detached != true,
                    onClick = onPush,
                )
            }

            if (actionBusy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 2.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在执行仓库操作…",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.84f),
                    )
                }
            }

            actionSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismissSummary),
                    shape = RoundedCornerShape(12.dp),
                    color = accentColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = summary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun RepoStateBadge(
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun RepoActionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
