package dev.codexremote.android.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class RuntimeControlTarget {
    Model,
    ReasoningEffort,
}

internal data class RuntimeControlOption(
    val value: String?,
    val label: String,
    val detail: String,
)

internal val modelRuntimeOptions = listOf(
    RuntimeControlOption(
        value = null,
        label = "自动",
        detail = "沿用服务端默认模型",
    ),
    RuntimeControlOption(
        value = "gpt-5.4",
        label = "gpt-5.4",
        detail = "通用平衡档",
    ),
    RuntimeControlOption(
        value = "o4-mini",
        label = "o4-mini",
        detail = "更轻量、更快",
    ),
)

internal val reasoningRuntimeOptions = listOf(
    RuntimeControlOption(
        value = null,
        label = "自动",
        detail = "沿用服务端默认推理强度",
    ),
    RuntimeControlOption(
        value = "low",
        label = "低",
        detail = "更快响应",
    ),
    RuntimeControlOption(
        value = "medium",
        label = "中",
        detail = "默认平衡档",
    ),
    RuntimeControlOption(
        value = "high",
        label = "高",
        detail = "更强推理",
    ),
)

internal fun runtimeControlTitle(target: RuntimeControlTarget): String = when (target) {
    RuntimeControlTarget.Model -> "选择模型"
    RuntimeControlTarget.ReasoningEffort -> "选择思考强度"
}

internal fun runtimeControlLabel(target: RuntimeControlTarget, value: String?): String = when (target) {
    RuntimeControlTarget.Model -> value?.takeIf { it.isNotBlank() } ?: "自动"
    RuntimeControlTarget.ReasoningEffort -> value?.takeIf { it.isNotBlank() } ?: "自动"
}

internal fun runtimeControlOptions(target: RuntimeControlTarget): List<RuntimeControlOption> = when (target) {
    RuntimeControlTarget.Model -> modelRuntimeOptions
    RuntimeControlTarget.ReasoningEffort -> reasoningRuntimeOptions
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeControlChips(
    modelLabel: String,
    reasoningLabel: String,
    enabled: Boolean,
    onModelClick: () -> Unit,
    onReasoningClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RuntimeControlChip(
            title = "模型",
            value = modelLabel,
            enabled = enabled,
            onClick = onModelClick,
        )
        RuntimeControlChip(
            title = "思考",
            value = reasoningLabel,
            enabled = enabled,
            onClick = onReasoningClick,
        )
    }
}

@Composable
private fun RuntimeControlChip(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = true,
        onClick = onClick,
        enabled = enabled,
        label = {
            Column(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeControlSheetContent(
    target: RuntimeControlTarget,
    currentValue: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = runtimeControlTitle(target),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = when (target) {
                RuntimeControlTarget.Model -> "选一个更适合当前任务的模型。"
                RuntimeControlTarget.ReasoningEffort -> "选一个更适合当前任务的思考强度。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            runtimeControlOptions(target).forEach { option ->
                val selected = currentValue == option.value
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.value) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Text(
            text = "选择会作用于下一次发送。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
