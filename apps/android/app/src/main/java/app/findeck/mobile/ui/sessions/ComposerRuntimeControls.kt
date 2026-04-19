package app.findeck.mobile.ui.sessions

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
import app.findeck.mobile.R
import app.findeck.mobile.data.model.RuntimeModelDescriptor

internal enum class RuntimeControlTarget {
    Model,
    ReasoningEffort,
    PermissionMode,
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
    RuntimeControlTarget.PermissionMode -> stringResource(R.string.session_controls_runtime_permission_title)
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
    RuntimeControlTarget.PermissionMode -> when (value?.trim()) {
        "on-request", "onRequest", "default", null -> sessionControlsString(
            R.string.session_controls_runtime_permission_on_request_label,
            "On-request",
        )
        else -> sessionControlsString(
            R.string.session_controls_runtime_permission_full_label,
            "Full access",
        )
    }
}

@Composable
private fun localizedModelDetail(model: RuntimeModelDescriptor): String {
    val key = "${model.id}|${model.model}|${model.displayName}".lowercase()
    return when {
        "gpt-5.4" in key && "mini" !in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_4)
        "gpt-5.4-mini" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_4_mini)
        "gpt-5.3-codex-spark" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_3_codex_spark)
        "gpt-5.3-codex" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_3_codex)
        "gpt-5.2-codex" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_2_codex)
        "gpt-5.1-codex-max" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_1_codex_max)
        "gpt-5.1-codex-mini" in key || "gpt-5-codex-mini" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_1_codex_mini)
        "gpt-5.2" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_gpt_5_2)
        "codex-max" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_flagship_codex)
        "spark" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_ultra_fast_codex)
        "mini" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_mini)
        "codex" in key ->
            stringResource(R.string.session_controls_runtime_model_detail_codex)
        else ->
            stringResource(R.string.session_controls_runtime_model_detail_generic)
    }
}

@Composable
private fun fallbackRuntimeModels(): List<RuntimeModelDescriptor> = listOf(
    RuntimeModelDescriptor(
        id = "gpt-5.4",
        model = "gpt-5.4",
        displayName = stringResource(R.string.session_controls_runtime_model_gpt_5_4_label),
        description = stringResource(R.string.session_controls_runtime_model_gpt_5_4_detail),
        hidden = false,
        isDefault = true,
        defaultReasoningEffort = "medium",
        supportedReasoningEfforts = emptyList(),
    ),
    RuntimeModelDescriptor(
        id = "o4-mini",
        model = "o4-mini",
        displayName = stringResource(R.string.session_controls_runtime_model_o4_mini_label),
        description = stringResource(R.string.session_controls_runtime_model_o4_mini_detail),
        hidden = false,
        isDefault = false,
        defaultReasoningEffort = "medium",
        supportedReasoningEfforts = emptyList(),
    ),
)

@Composable
internal fun runtimeControlOptions(
    target: RuntimeControlTarget,
    runtimeModels: List<RuntimeModelDescriptor> = emptyList(),
    selectedModel: String? = null,
): List<RuntimeControlOption> = when (target) {
    RuntimeControlTarget.Model -> listOf(
        RuntimeControlOption(
            value = null,
            label = stringResource(R.string.session_controls_runtime_auto_label),
            detail = stringResource(R.string.session_controls_runtime_model_auto_detail),
        ),
    ) + ((runtimeModels.ifEmpty { fallbackRuntimeModels() })
        .filter { !it.hidden || it.model == selectedModel || it.id == selectedModel }
        .distinctBy { it.model }
        .map { model ->
            RuntimeControlOption(
                value = model.model,
                label = model.displayName,
                detail = localizedModelDetail(model),
            )
        })
    RuntimeControlTarget.ReasoningEffort -> listOf(
        RuntimeControlOption(
            value = null,
            label = stringResource(R.string.session_controls_runtime_auto_label),
            detail = stringResource(R.string.session_controls_runtime_reasoning_auto_detail),
        ),
    ) + availableReasoningOptions(
        runtimeModels = runtimeModels.ifEmpty { fallbackRuntimeModels() },
        selectedModel = selectedModel,
    )
    RuntimeControlTarget.PermissionMode -> listOf(
        RuntimeControlOption(
            value = "on-request",
            label = stringResource(R.string.session_controls_runtime_permission_on_request_label),
            detail = stringResource(R.string.session_controls_runtime_permission_on_request_detail),
        ),
        RuntimeControlOption(
            value = "full-access",
            label = stringResource(R.string.session_controls_runtime_permission_full_label),
            detail = stringResource(R.string.session_controls_runtime_permission_full_detail),
        ),
    )
}

@Composable
private fun availableReasoningOptions(
    runtimeModels: List<RuntimeModelDescriptor>,
    selectedModel: String?,
): List<RuntimeControlOption> {
    val selectedDescriptor = runtimeModels.firstOrNull {
        it.model == selectedModel || it.id == selectedModel
    } ?: runtimeModels.firstOrNull { it.isDefault } ?: runtimeModels.firstOrNull()
    val reasoningValues = selectedDescriptor
        ?.supportedReasoningEfforts
        ?.takeIf { it.isNotEmpty() }
        ?.map { it.reasoningEffort to it.description }
        ?: listOf(
            "low" to stringResource(R.string.session_controls_runtime_reasoning_low_detail),
            "medium" to stringResource(R.string.session_controls_runtime_reasoning_medium_detail),
            "high" to stringResource(R.string.session_controls_runtime_reasoning_high_detail),
            "xhigh" to stringResource(R.string.session_controls_runtime_reasoning_xhigh_detail),
        )

    return reasoningValues
        .distinctBy { it.first }
        .map { (value, description) ->
            RuntimeControlOption(
                value = value,
                label = when (value) {
                    "low" -> stringResource(R.string.session_controls_runtime_reasoning_low_label)
                    "medium" -> stringResource(R.string.session_controls_runtime_reasoning_medium_label)
                    "high" -> stringResource(R.string.session_controls_runtime_reasoning_high_label)
                    "xhigh" -> stringResource(R.string.session_controls_runtime_reasoning_xhigh_label)
                    else -> value
                },
                detail = when (value) {
                    "low" -> stringResource(R.string.session_controls_runtime_reasoning_low_detail)
                    "medium" -> stringResource(R.string.session_controls_runtime_reasoning_medium_detail)
                    "high" -> stringResource(R.string.session_controls_runtime_reasoning_high_detail)
                    "xhigh" -> stringResource(R.string.session_controls_runtime_reasoning_xhigh_detail)
                    else -> description.ifBlank {
                        stringResource(R.string.session_controls_runtime_reasoning_generic_detail)
                    }
                },
            )
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeControlChips(
    modelLabel: String,
    reasoningLabel: String,
    permissionLabel: String,
    enabled: Boolean,
    onModelClick: () -> Unit,
    onReasoningClick: () -> Unit,
    onPermissionClick: () -> Unit,
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
        RuntimeControlChip(
            title = stringResource(R.string.session_controls_runtime_permission_chip_title),
            value = permissionLabel,
            detail = runtimeControlStateDetail(permissionLabel),
            enabled = enabled,
            onClick = onPermissionClick,
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
    runtimeModels: List<RuntimeModelDescriptor> = emptyList(),
    selectedModel: String? = null,
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
                RuntimeControlTarget.PermissionMode -> stringResource(R.string.session_controls_runtime_permission_sheet_summary)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            runtimeControlOptions(
                target = target,
                runtimeModels = runtimeModels,
                selectedModel = selectedModel,
            ).forEach { option ->
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
