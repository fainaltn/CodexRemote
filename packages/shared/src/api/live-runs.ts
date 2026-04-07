import { z } from "zod";
import { Run } from "../schemas/run.js";
import {
  MAX_PROMPT_LENGTH,
  MAX_MODEL_LENGTH,
  MAX_REASONING_EFFORT_LENGTH,
  MAX_ID_LENGTH,
} from "../limits.js";

// --- GET /api/hosts/:hostId/sessions/:sessionId/live ---

export const GetLiveRunResponse = Run.nullable();
export type GetLiveRunResponse = z.infer<typeof GetLiveRunResponse>;

// --- GET /api/hosts/:hostId/sessions/:sessionId/live/stream ---
// SSE endpoint — no request/response schema needed here.
// The stream emits Run-shaped events; consumers parse with the Run schema.

// --- POST /api/hosts/:hostId/sessions/:sessionId/live ---

export const StartLiveRunRequest = z.object({
  prompt: z.string().min(1).max(MAX_PROMPT_LENGTH),
  model: z.string().max(MAX_MODEL_LENGTH).optional(),
  reasoningEffort: z.string().max(MAX_REASONING_EFFORT_LENGTH).optional(),
});
export type StartLiveRunRequest = z.infer<typeof StartLiveRunRequest>;

export const StartLiveRunResponse = z.object({
  runId: z.string(),
});
export type StartLiveRunResponse = z.infer<typeof StartLiveRunResponse>;

// --- Route params (shared by all /live endpoints) ---

/** GET/POST /api/hosts/:hostId/sessions/:sessionId/live[/...] */
export const LiveRunParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
  sessionId: z.string().max(MAX_ID_LENGTH),
});
export type LiveRunParams = z.infer<typeof LiveRunParams>;

// --- POST /api/hosts/:hostId/sessions/:sessionId/live/stop ---

export const StopLiveRunResponse = z.object({
  ok: z.literal(true),
});
export type StopLiveRunResponse = z.infer<typeof StopLiveRunResponse>;
