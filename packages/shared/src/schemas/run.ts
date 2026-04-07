import { z } from "zod";

// --- Enums ---

export const RunStatus = z.enum([
  "pending",
  "running",
  "completed",
  "failed",
  "stopped",
]);
export type RunStatus = z.infer<typeof RunStatus>;

// --- Run entity (§6.2) ---

export const Run = z.object({
  id: z.string(),
  sessionId: z.string(),
  status: RunStatus,
  prompt: z.string(),
  model: z.string().nullable(),
  reasoningEffort: z.string().nullable(),
  startedAt: z.string().datetime(),
  finishedAt: z.string().datetime().nullable(),
  lastOutput: z.string().nullable(),
  error: z.string().nullable(),
});
export type Run = z.infer<typeof Run>;
