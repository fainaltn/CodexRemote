package dev.codexremote.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeReasoningEffortOption(
    val reasoningEffort: String,
    val description: String,
)

@Serializable
data class RuntimeModelUpgradeInfo(
    val model: String,
    val upgradeCopy: String? = null,
    val modelLink: String? = null,
    val migrationMarkdown: String? = null,
)

@Serializable
data class RuntimeModelAvailabilityNux(
    val message: String,
)

@Serializable
data class RuntimeModelDescriptor(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val isDefault: Boolean,
    val defaultReasoningEffort: String,
    val supportedReasoningEfforts: List<RuntimeReasoningEffortOption>,
    val inputModalities: List<String> = listOf("text", "image"),
    val supportsPersonality: Boolean = false,
    val upgrade: String? = null,
    val upgradeInfo: RuntimeModelUpgradeInfo? = null,
    val availabilityNux: RuntimeModelAvailabilityNux? = null,
)

@Serializable
data class RuntimeCatalogResponse(
    val models: List<RuntimeModelDescriptor>,
    val nextCursor: String? = null,
    val fetchedAt: String? = null,
)

@Serializable
data class RuntimeCreditsSnapshot(
    val balance: String? = null,
    val hasCredits: Boolean,
    val unlimited: Boolean,
)

@Serializable
data class RuntimeRateLimitWindow(
    val usedPercent: Int,
    val windowDurationMins: Int? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class RuntimeRateLimitSnapshot(
    val limitId: String? = null,
    val limitName: String? = null,
    val primary: RuntimeRateLimitWindow? = null,
    val secondary: RuntimeRateLimitWindow? = null,
    val credits: RuntimeCreditsSnapshot? = null,
    val planType: String? = null,
)

@Serializable
data class RuntimeUsageResponse(
    val rateLimits: RuntimeRateLimitSnapshot? = null,
    val rateLimitsByLimitId: Map<String, RuntimeRateLimitSnapshot>? = null,
    val fetchedAt: String? = null,
)
