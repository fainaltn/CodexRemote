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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.android.R

internal enum class RuntimeControlTarget {
    Model,
    ReasoningEffort,
}

internal data class RuntimeControlOption(
    val value: String?,
    val label: String,
    val detail: String,
)

@Composable
internal fun runtimeControlTitle(target: RuntimeControlTarget): String = when (target) {
    RuntimeControlTarget.Model -> stringResource(R.string.session_controls_runtime_model_title)
    RuntimeControlTarget.ReasoningEffort -> stringResource(R.string.session_controls_runtime_reasoning_title)
}

internal fun runtimeControlLabel(target: RuntimeControlTarget, value: String?): String = when (target) {
    RuntimeControlTarget.Model -> value?.takeIf { it.isNotBlank() } ?: sessionControlsString(
        R.string.session_controls_runtime_auto_label,
        fallbackAutoLabel(),
    )
    RuntimeControlTarget.ReasoningEffort -> value?.takeIf { it.isNotBlank() } ?: sessionControlsString(
        R.string.session_controls_runtime_auto_label,
        fallbackAutoLabel(),
    )
}

@Composable
internal fun runtimeControlOptions(target: RuntimeControlTarget): List<RuntimeControlOption> = when (target) {
    RuntimeControlTarget.Model -> listOf(
        RuntimeControlOption(
            value = null,
            label = stringResource(R.string.session_controls_runtime_auto_label),
            detail = stringResource(R.string.session_controls_runtime_model_auto_detail),
        ),
        RuntimeControlOption(
            value = "gpt-5.4",
            label = stringResource(R.string.session_controls_runtime_model_gpt_5_4_label),
            detail = stringResource(R.string.session_controls_runtime_model_gpt_5_4_detail),
        ),
        RuntimeControlOption(
            value = "o4-mini",
            label = stringResource(R.string.session_controls_runtime_model_o4_mini_label),
            detail = stringResource(R.string.session_controls_runtime_model_o4_mini_detail),
        ),
    )
    RuntimeControlTarget.ReasoningEffort -> listOf(
        RuntimeControlOption(
            value = null,
            label = stringResource(R.string.session_controls_runtime_auto_label),
            detail = stringResource(R.string.session_controls_runtime_reasoning_auto_detail),
        ),
        RuntimeControlOption(
            value = "low",
            label = stringResource(R.string.session_controls_runtime_reasoning_low_label),
            detail = stringResource(R.string.session_controls_runtime_reasoning_low_detail),
        ),
        RuntimeControlOption(
            value = "medium",
            label = stringResource(R.string.session_controls_runtime_reasoning_medium_label),
            detail = stringResource(R.string.session_controls_runtime_reasoning_medium_detail),
        ),
        RuntimeControlOption(
            value = "high",
            label = stringResource(R.string.session_controls_runtime_reasoning_high_label),
            detail = stringResource(R.string.session_controls_runtime_reasoning_high_detail),
        ),
    )
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
            title = stringResource(R.string.session_controls_runtime_model_chip_title),
            value = modelLabel,
            detail = runtimeControlStateDetail(modelLabel),
            enabled = enabled,
            onClick = onModelClick,
        )
        RuntimeControlChip(
            title = stringResource(R.string.session_controls_runtime_reasoning_chip_title),
            value = reasoningLabel,
            detail = runtimeControlStateDetail(reasoningLabel),
            enabled = enabled,
            onClick = onReasoningClick,
        )
    }
}

@Composable
private fun RuntimeControlChip(
    title: String,
    value: String,
    detail: String,
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
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
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
            text = stringResource(R.string.session_controls_runtime_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when (target) {
                RuntimeControlTarget.Model -> stringResource(R.string.session_controls_runtime_model_sheet_summary)
                RuntimeControlTarget.ReasoningEffort -> stringResource(R.string.session_controls_runtime_reasoning_sheet_summary)
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
            text = stringResource(R.string.session_controls_runtime_next_send_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun runtimeControlStateDetail(value: String): String =
    if (value == stringResource(R.string.session_controls_runtime_auto_label)) {
        stringResource(R.string.session_controls_runtime_inherited_detail)
    } else {
        stringResource(R.string.session_controls_runtime_explicit_detail)
    }

private fun sessionControlsString(resId: Int, fallback: String, vararg args: Any): String {
    val application = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThread.getDeclaredMethod("currentApplication")
        currentApplication.isAccessible = true
        currentApplication.invoke(null) as? android.app.Application
    }.getOrNull()
    val resources = application?.resources ?: return fallback
    return if (args.isEmpty()) {
        resources.getString(resId)
    } else {
        resources.getString(resId, *args)
    }
}

private fun fallbackAutoLabel(): String =
    if (java.util.Locale.getDefault().language.startsWith("zh")) "自动" else "Auto"
