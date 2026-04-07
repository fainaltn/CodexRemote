import { z } from "zod";

// --- GET /api/health ---

export const HealthResponse = z.object({
  status: z.enum(["ok", "degraded"]),
  checks: z
    .record(z.unknown())
    .optional(),
});
export type HealthResponse = z.infer<typeof HealthResponse>;

// --- GET /api/system/info ---

export const SystemInfoResponse = z.object({
  version: z.string(),
  hostId: z.string(),
  uptime: z.number().nonnegative(),
});
export type SystemInfoResponse = z.infer<typeof SystemInfoResponse>;
