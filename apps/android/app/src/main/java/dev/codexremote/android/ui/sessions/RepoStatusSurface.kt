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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.android.data.model.RepoStatus
import dev.codexremote.android.R

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
    onPull: () -> Unit,
    onStash: () -> Unit,
    onShowLog: () -> Unit,
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
                    text = stringResource(R.string.session_controls_repo_status_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                RepoStateBadge(
                    text = when {
                        isClean -> stringResource(R.string.session_controls_repo_status_clean)
                        !dirtyLabel.isNullOrBlank() -> stringResource(R.string.session_controls_repo_status_dirty)
                        else -> stringResource(R.string.session_controls_repo_status_connected)
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
                        text = stringResource(
                            R.string.session_controls_repo_branch_format,
                            branch,
                        ),
                        containerColor = contentColor.copy(alpha = 0.08f),
                        contentColor = contentColor,
                    )
                }
                repoRootLabel(repoStatus)?.let { root ->
                    RepoStateBadge(
                        text = stringResource(
                            R.string.session_controls_repo_root_format,
                            root,
                        ),
                        containerColor = contentColor.copy(alpha = 0.08f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.aheadBy?.takeIf { it > 0 }?.let { aheadBy ->
                    RepoStateBadge(
                        text = stringResource(
                            R.string.session_controls_repo_ahead_format,
                            aheadBy,
                        ),
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.behindBy?.takeIf { it > 0 }?.let { behindBy ->
                    RepoStateBadge(
                        text = stringResource(
                            R.string.session_controls_repo_behind_format,
                            behindBy,
                        ),
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.stagedCount?.takeIf { it > 0 }?.let { stagedCount ->
                    RepoStateBadge(
                        text = stringResource(
                            R.string.session_controls_repo_staged_format,
                            stagedCount,
                        ),
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.unstagedCount?.takeIf { it > 0 }?.let { unstagedCount ->
                    RepoStateBadge(
                        text = stringResource(
                            R.string.session_controls_repo_unstaged_format,
                            unstagedCount,
                        ),
                        containerColor = accentColor.copy(alpha = 0.14f),
                        contentColor = contentColor,
                    )
                }
                repoStatus?.untrackedCount?.takeIf { it > 0 }?.let { untrackedCount ->
                    RepoStateBadge(
                        text = stringResource(
                            R.string.session_controls_repo_untracked_format,
                            untrackedCount,
                        ),
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
                    label = stringResource(R.string.session_controls_repo_action_new_branch),
                    enabled = actionsEnabled && !actionBusy,
                    onClick = onCreateBranch,
                )
                RepoActionChip(
                    label = stringResource(R.string.session_controls_repo_action_checkout_branch),
                    enabled = actionsEnabled && !actionBusy,
                    onClick = onCheckoutBranch,
                )
                RepoActionChip(
                    label = stringResource(R.string.session_controls_repo_action_commit),
                    enabled = actionsEnabled && !actionBusy && !isClean,
                    onClick = onCommit,
                )
                RepoActionChip(
                    label = stringResource(R.string.session_controls_repo_action_push),
                    enabled = actionsEnabled &&
                        !actionBusy &&
                        !repoBranchLabel(repoStatus).isNullOrBlank() &&
                        repoStatus?.detached != true,
                    onClick = onPush,
                )
                RepoActionChip(
                    label = stringResource(R.string.session_controls_repo_action_pull),
                    enabled = actionsEnabled &&
                        !actionBusy &&
                        !repoBranchLabel(repoStatus).isNullOrBlank() &&
                        repoStatus?.detached != true,
                    onClick = onPull,
                )
                RepoActionChip(
                    label = stringResource(R.string.session_controls_repo_action_stash),
                    enabled = actionsEnabled && !actionBusy && !isClean,
                    onClick = onStash,
                )
                RepoActionChip(
                    label = stringResource(R.string.session_controls_repo_action_log),
                    enabled = !actionBusy,
                    onClick = onShowLog,
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
                        text = stringResource(R.string.session_controls_repo_action_busy),
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
