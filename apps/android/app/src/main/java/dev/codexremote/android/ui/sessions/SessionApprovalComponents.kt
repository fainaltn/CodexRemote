@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R

enum class SessionApprovalScope {
    Turn,
    Session,
}

enum class SessionApprovalDecision {
    AcceptTurn,
    AcceptSession,
    Decline,
}

data class SessionApprovalUiItem(
    val id: String,
    val title: String,
    val detail: String? = null,
    val kind: String? = null,
    val scope: SessionApprovalScope = SessionApprovalScope.Turn,
    val createdAt: String? = null,
)

@Composable
internal fun SessionApprovalPreviewCard(
    approvals: List<SessionApprovalUiItem>,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty()) return

    val count = approvals.size
    TimelineNoticeCard(
        modifier = modifier,
        title = stringResource(R.string.session_detail_approval_title),
        message = stringResource(R.string.session_detail_approval_message, count),
        footer = stringResource(R.string.session_detail_approval_footer),
        tone = TimelineNoticeTone.Warning,
        stateLabel = stringResource(R.string.session_detail_approval_state, count),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                approvals.take(2).forEach { approval ->
                    ApprovalPreviewRow(approval = approval)
                }
                if (count > 2) {
                    Text(
                        text = stringResource(R.string.session_detail_approval_more, count - 2),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onOpenDetails) {
                        Text(stringResource(R.string.session_detail_approval_view_details))
                    }
                }
            }
        },
    )
}

@Composable
private fun ApprovalPreviewRow(
    approval: SessionApprovalUiItem,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = approval.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                approval.createdAt?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = formatDate(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            approval.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Text(
                text = stringResource(R.string.session_detail_approval_detail_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ApprovalMetaChip(text = approvalKindLabel(approval.kind))
                ApprovalMetaChip(text = approvalScopeLabel(approval.scope))
            }
        }
    }
}

@Composable
private fun ApprovalMetaChip(
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun approvalKindLabel(kind: String?): String = when (kind?.trim()?.lowercase()) {
    "commandexecution", "command_execution", "command" ->
        stringResource(R.string.session_detail_approval_kind_command)
    "filechange", "file_change", "file" ->
        stringResource(R.string.session_detail_approval_kind_file)
    "permissions", "permission" ->
        stringResource(R.string.session_detail_approval_kind_permissions)
    else -> stringResource(R.string.session_detail_approval_kind_generic)
}

@Composable
private fun approvalScopeLabel(scope: SessionApprovalScope): String = when (scope) {
    SessionApprovalScope.Turn -> stringResource(R.string.session_detail_approval_scope_turn)
    SessionApprovalScope.Session -> stringResource(R.string.session_detail_approval_scope_session)
}

@Composable
private fun approvalDecisionLabel(decision: SessionApprovalDecision): String = when (decision) {
    SessionApprovalDecision.AcceptTurn -> stringResource(R.string.session_detail_approval_allow_turn)
    SessionApprovalDecision.AcceptSession -> stringResource(R.string.session_detail_approval_allow_session)
    SessionApprovalDecision.Decline -> stringResource(R.string.session_detail_approval_decline)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionApprovalSheet(
    approvals: List<SessionApprovalUiItem>,
    onDismiss: () -> Unit,
    onDecision: (approvalId: String, decision: SessionApprovalDecision) -> Unit,
) {
    if (approvals.isEmpty()) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.session_detail_approval_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.session_detail_approval_sheet_subtitle,
                            approvals.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.session_detail_close))
                }
            }

            approvals.forEach { approval ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = approval.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            ApprovalMetaChip(text = approvalScopeLabel(approval.scope))
                        }

                        approval.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } ?: Text(
                            text = stringResource(R.string.session_detail_approval_detail_fallback),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ApprovalMetaChip(text = approvalKindLabel(approval.kind))
                            approval.createdAt?.takeIf { it.isNotBlank() }?.let {
                                ApprovalMetaChip(
                                    text = stringResource(
                                        R.string.session_detail_approval_created,
                                        formatDate(it),
                                    ),
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = {
                                onDecision(approval.id, SessionApprovalDecision.AcceptTurn)
                            }) {
                                Text(approvalDecisionLabel(SessionApprovalDecision.AcceptTurn))
                            }
                            OutlinedButton(onClick = {
                                onDecision(approval.id, SessionApprovalDecision.AcceptSession)
                            }) {
                                Text(approvalDecisionLabel(SessionApprovalDecision.AcceptSession))
                            }
                            TextButton(onClick = {
                                onDecision(approval.id, SessionApprovalDecision.Decline)
                            }) {
                                Text(approvalDecisionLabel(SessionApprovalDecision.Decline))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
