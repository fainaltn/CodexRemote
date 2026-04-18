package dev.codexremote.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PendingPermissionFileSystem(
    val read: List<String>? = null,
    val write: List<String>? = null,
)

@Serializable
data class PendingPermissionNetwork(
    val enabled: Boolean? = null,
)

@Serializable
data class PendingPermissionProfile(
    val fileSystem: PendingPermissionFileSystem? = null,
    val network: PendingPermissionNetwork? = null,
)

@Serializable
data class PendingNetworkApprovalContext(
    val host: String,
    val protocol: String,
)

@Serializable
data class PendingApproval(
    val id: String,
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val kind: String,
    val scope: String,
    val createdAt: String,
    val reason: String? = null,
    val status: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val approvalId: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val commandActions: List<String>? = null,
    val networkApprovalContext: PendingNetworkApprovalContext? = null,
    val networkHost: String? = null,
    val networkProtocol: String? = null,
    val grantRoot: String? = null,
    val permissions: PendingPermissionProfile? = null,
    val rpcRequestIdValue: JsonElement? = null,
    val rpcRequestId: String? = null,
    val rpcMethod: String? = null,
)

@Serializable
data class ListPendingApprovalsResponse(
    val approvals: List<PendingApproval>,
)

@Serializable
data class PendingApprovalSseEvent(
    val approval: PendingApproval,
    val eventId: Long? = null,
)

@Serializable
data class PendingApprovalDecisionRequest(
    val kind: String,
    val decision: String? = null,
    val permissions: PendingPermissionProfile? = null,
    val scope: String? = null,
)

@Serializable
data class PendingApprovalDecisionResponse(
    val ok: Boolean,
)
