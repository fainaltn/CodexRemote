import { z } from "zod";
import { Session } from "../schemas/session.js";
import { SessionMessage } from "../schemas/session-message.js";
import {
  MAX_PROMPT_LENGTH,
  MAX_ID_LENGTH,
  MAX_PATH_LENGTH,
  MAX_SESSION_TITLE_LENGTH,
} from "../limits.js";

// --- GET /api/hosts/:hostId/sessions ---

export const ListSessionsResponse = z.object({
  sessions: z.array(Session),
});
export type ListSessionsResponse = z.infer<typeof ListSessionsResponse>;

// --- GET /api/hosts/:hostId/sessions/:sessionId ---

export const SessionDetailResponse = z.object({
  session: Session,
  messages: z.array(SessionMessage),
});
export type SessionDetailResponse = z.infer<typeof SessionDetailResponse>;
export const GetSessionResponse = SessionDetailResponse;
export type GetSessionResponse = SessionDetailResponse;

// --- POST /api/hosts/:hostId/sessions/:sessionId/message ---

export const SendMessageRequest = z.object({
  prompt: z.string().min(1).max(MAX_PROMPT_LENGTH),
});
export type SendMessageRequest = z.infer<typeof SendMessageRequest>;

export const SendMessageResponse = z.object({
  runId: z.string(),
});
export type SendMessageResponse = z.infer<typeof SendMessageResponse>;

// --- POST /api/hosts/:hostId/sessions ---

export const CreateSessionRequest = z.object({
  cwd: z.string().min(1).max(MAX_PATH_LENGTH),
  prompt: z.string().max(MAX_PROMPT_LENGTH).optional(),
});
export type CreateSessionRequest = z.infer<typeof CreateSessionRequest>;

export const CreateSessionResponse = z.object({
  sessionId: z.string(),
  runId: z.string(),
});
export type CreateSessionResponse = z.infer<typeof CreateSessionResponse>;

export const UpdateSessionTitleRequest = z.object({
  title: z.string().trim().min(1).max(MAX_SESSION_TITLE_LENGTH),
});
export type UpdateSessionTitleRequest = z.infer<typeof UpdateSessionTitleRequest>;

export const UpdateSessionTitleResponse = z.object({
  ok: z.literal(true),
});
export type UpdateSessionTitleResponse = z.infer<typeof UpdateSessionTitleResponse>;

export const ArchiveSessionsRequest = z.object({
  sessionIds: z.array(z.string().max(MAX_ID_LENGTH)).min(1).max(100),
});
export type ArchiveSessionsRequest = z.infer<typeof ArchiveSessionsRequest>;

export const ArchiveSessionsResponse = z.object({
  ok: z.literal(true),
  archivedCount: z.number().int().nonnegative(),
});
export type ArchiveSessionsResponse = z.infer<typeof ArchiveSessionsResponse>;

// --- Route params ---

/** GET /api/hosts/:hostId/sessions */
export const ListSessionsParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
});
export type ListSessionsParams = z.infer<typeof ListSessionsParams>;

/** GET /api/hosts/:hostId/sessions/:sessionId, POST .../message */
export const SessionParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
  sessionId: z.string().max(MAX_ID_LENGTH),
});
export type SessionParams = z.infer<typeof SessionParams>;
