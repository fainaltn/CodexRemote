import { z } from "zod";
import {
  MAX_ID_LENGTH,
  MAX_MODEL_LENGTH,
  MAX_PATH_LENGTH,
  MAX_REASONING_EFFORT_LENGTH,
} from "../limits.js";

// --- GET /api/hosts/:hostId/runtime/catalog ---

export const RuntimeInputModality = z.enum(["text", "image"]);
export type RuntimeInputModality = z.infer<typeof RuntimeInputModality>;

export const RuntimeReasoningEffort = z.string().max(
  MAX_REASONING_EFFORT_LENGTH,
);
export type RuntimeReasoningEffort = z.infer<typeof RuntimeReasoningEffort>;

export const RuntimeReasoningEffortOption = z.object({
  reasoningEffort: RuntimeReasoningEffort,
  description: z.string(),
});
export type RuntimeReasoningEffortOption = z.infer<
  typeof RuntimeReasoningEffortOption
>;

export const RuntimeModelUpgradeInfo = z.object({
  model: z.string().max(MAX_MODEL_LENGTH),
  upgradeCopy: z.string().nullable().optional(),
  modelLink: z.string().nullable().optional(),
  migrationMarkdown: z.string().nullable().optional(),
});
export type RuntimeModelUpgradeInfo = z.infer<typeof RuntimeModelUpgradeInfo>;

export const RuntimeModelAvailabilityNux = z.object({
  message: z.string(),
});
export type RuntimeModelAvailabilityNux = z.infer<
  typeof RuntimeModelAvailabilityNux
>;

export const RuntimeModelDescriptor = z.object({
  id: z.string().max(MAX_MODEL_LENGTH),
  model: z.string().max(MAX_MODEL_LENGTH),
  displayName: z.string(),
  description: z.string(),
  hidden: z.boolean(),
  isDefault: z.boolean(),
  defaultReasoningEffort: RuntimeReasoningEffort,
  supportedReasoningEfforts: z.array(RuntimeReasoningEffortOption),
  inputModalities: z.array(RuntimeInputModality).default(["text", "image"]),
  supportsPersonality: z.boolean().default(false),
  upgrade: z.string().max(MAX_MODEL_LENGTH).nullable().optional(),
  upgradeInfo: RuntimeModelUpgradeInfo.nullable().optional(),
  availabilityNux: RuntimeModelAvailabilityNux.nullable().optional(),
});
export type RuntimeModelDescriptor = z.infer<typeof RuntimeModelDescriptor>;

export const RuntimeCatalogResponse = z.object({
  models: z.array(RuntimeModelDescriptor),
  nextCursor: z.string().nullable().optional(),
  fetchedAt: z.string().datetime().optional(),
});
export type RuntimeCatalogResponse = z.infer<typeof RuntimeCatalogResponse>;

// --- GET /api/hosts/:hostId/runtime/usage ---

export const RuntimeCreditsSnapshot = z.object({
  balance: z.string().nullable().optional(),
  hasCredits: z.boolean(),
  unlimited: z.boolean(),
});
export type RuntimeCreditsSnapshot = z.infer<typeof RuntimeCreditsSnapshot>;

export const RuntimePlanType = z.enum([
  "free",
  "go",
  "plus",
  "pro",
  "team",
  "self_serve_business_usage_based",
  "business",
  "enterprise_cbp_usage_based",
  "enterprise",
  "edu",
  "unknown",
]);
export type RuntimePlanType = z.infer<typeof RuntimePlanType>;

export const RuntimeRateLimitWindow = z.object({
  usedPercent: z.number().int().nonnegative(),
  windowDurationMins: z.number().int().nonnegative().nullable().optional(),
  resetsAt: z.number().int().nullable().optional(),
});
export type RuntimeRateLimitWindow = z.infer<typeof RuntimeRateLimitWindow>;

export const RuntimeRateLimitSnapshot = z.object({
  limitId: z.string().nullable().optional(),
  limitName: z.string().nullable().optional(),
  primary: RuntimeRateLimitWindow.nullable().optional(),
  secondary: RuntimeRateLimitWindow.nullable().optional(),
  credits: RuntimeCreditsSnapshot.nullable().optional(),
  planType: RuntimePlanType.nullable().optional(),
});
export type RuntimeRateLimitSnapshot = z.infer<typeof RuntimeRateLimitSnapshot>;

export const RuntimeUsageResponse = z.object({
  rateLimits: RuntimeRateLimitSnapshot.nullable(),
  rateLimitsByLimitId: z
    .record(RuntimeRateLimitSnapshot)
    .nullable()
    .optional(),
  fetchedAt: z.string().datetime().optional(),
});
export type RuntimeUsageResponse = z.infer<typeof RuntimeUsageResponse>;

// --- Route params ---

export const RuntimeParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
});
export type RuntimeParams = z.infer<typeof RuntimeParams>;

// --- Shared runtime policy metadata (used by future runtime-control APIs) ---

export const RuntimeSandboxMode = z.enum([
  "read-only",
  "workspace-write",
  "danger-full-access",
]);
export type RuntimeSandboxMode = z.infer<typeof RuntimeSandboxMode>;

export const RuntimeApprovalPolicy = z.enum([
  "untrusted",
  "on-request",
  "on-failure",
  "never",
]);
export type RuntimeApprovalPolicy = z.infer<typeof RuntimeApprovalPolicy>;

// --- Shared approval payloads (used by future approval bridge APIs) ---

export const PendingApprovalKind = z.enum([
  "command",
  "fileChange",
  "permissions",
]);
export type PendingApprovalKind = z.infer<typeof PendingApprovalKind>;

export const PendingApprovalScope = z.enum(["turn", "session"]);
export type PendingApprovalScope = z.infer<typeof PendingApprovalScope>;

export const PendingNetworkApprovalContext = z.object({
  host: z.string(),
  protocol: z.enum(["http", "https", "socks5Tcp", "socks5Udp"]),
});
export type PendingNetworkApprovalContext = z.infer<
  typeof PendingNetworkApprovalContext
>;

export const PendingPermissionProfile = z.object({
  fileSystem: z
    .object({
      read: z.array(z.string().max(MAX_PATH_LENGTH)).nullable().optional(),
      write: z.array(z.string().max(MAX_PATH_LENGTH)).nullable().optional(),
    })
    .nullable()
    .optional(),
  network: z
    .object({
      enabled: z.boolean().nullable().optional(),
    })
    .nullable()
    .optional(),
});
export type PendingPermissionProfile = z.infer<typeof PendingPermissionProfile>;

const PendingApprovalBase = z.object({
  id: z.string().max(MAX_ID_LENGTH),
  threadId: z.string().max(MAX_ID_LENGTH),
  turnId: z.string().max(MAX_ID_LENGTH),
  itemId: z.string().max(MAX_ID_LENGTH),
  reason: z.string().nullable().optional(),
  createdAt: z.string().datetime(),
});

export const PendingCommandApproval = PendingApprovalBase.extend({
  kind: z.literal("command"),
  approvalId: z.string().max(MAX_ID_LENGTH).nullable().optional(),
  command: z.string().nullable().optional(),
  cwd: z.string().max(MAX_PATH_LENGTH).nullable().optional(),
  commandActions: z.array(z.string()).nullable().optional(),
  networkApprovalContext: PendingNetworkApprovalContext.nullable().optional(),
});
export type PendingCommandApproval = z.infer<typeof PendingCommandApproval>;

export const PendingFileChangeApproval = PendingApprovalBase.extend({
  kind: z.literal("fileChange"),
  grantRoot: z.string().max(MAX_PATH_LENGTH).nullable().optional(),
});
export type PendingFileChangeApproval = z.infer<
  typeof PendingFileChangeApproval
>;

export const PendingPermissionsApproval = PendingApprovalBase.extend({
  kind: z.literal("permissions"),
  permissions: PendingPermissionProfile,
});
export type PendingPermissionsApproval = z.infer<
  typeof PendingPermissionsApproval
>;

export const PendingApproval = z.discriminatedUnion("kind", [
  PendingCommandApproval,
  PendingFileChangeApproval,
  PendingPermissionsApproval,
]);
export type PendingApproval = z.infer<typeof PendingApproval>;
