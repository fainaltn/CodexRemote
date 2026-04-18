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

export const SessionSummaryResponse = z.object({
  session: Session,
});
export type SessionSummaryResponse = z.infer<typeof SessionSummaryResponse>;
export const GetSessionSummaryResponse = SessionSummaryResponse;
export type GetSessionSummaryResponse = SessionSummaryResponse;

export const SessionMessagesQuery = z.object({
  limit: z.coerce.number().int().positive().max(200).optional(),
  beforeOrderIndex: z.coerce.number().int().nonnegative().optional(),
});
export type SessionMessagesQuery = z.infer<typeof SessionMessagesQuery>;

export const SessionMessagesResponse = z.object({
  messages: z.array(SessionMessage),
  limit: z.number().int().positive(),
  hasMore: z.boolean(),
  nextBeforeOrderIndex: z.number().int().nonnegative().nullable(),
});
export type SessionMessagesResponse = z.infer<typeof SessionMessagesResponse>;
export const GetSessionMessagesResponse = SessionMessagesResponse;
export type GetSessionMessagesResponse = SessionMessagesResponse;

// --- GET /api/hosts/:hostId/sessions/:sessionId/repo-status ---

export const RepoStatus = z.discriminatedUnion("isRepo", [
  z.object({
    isRepo: z.literal(true),
    cwd: z.string(),
    rootPath: z.string(),
    branch: z.string().nullable(),
    detached: z.boolean(),
    aheadBy: z.number().int().nonnegative().nullable(),
    behindBy: z.number().int().nonnegative().nullable(),
    dirtyCount: z.number().int().nonnegative(),
    stagedCount: z.number().int().nonnegative(),
    unstagedCount: z.number().int().nonnegative(),
    untrackedCount: z.number().int().nonnegative(),
  }),
  z.object({
    isRepo: z.literal(false),
    cwd: z.string(),
    rootPath: z.null(),
    branch: z.null(),
    detached: z.literal(false),
    aheadBy: z.null(),
    behindBy: z.null(),
    dirtyCount: z.literal(0),
    stagedCount: z.literal(0),
    unstagedCount: z.literal(0),
    untrackedCount: z.literal(0),
  }),
]);
export type RepoStatus = z.infer<typeof RepoStatus>;

export const RepoStatusResponse = z.object({
  repoStatus: RepoStatus,
});
export type RepoStatusResponse = z.infer<typeof RepoStatusResponse>;

// --- POST /api/hosts/:hostId/sessions/:sessionId/repo-action ---

export const RepoActionRequest = z.discriminatedUnion("action", [
  z.object({
    action: z.literal("checkout"),
    branch: z.string().trim().min(1).max(MAX_PATH_LENGTH),
  }),
  z.object({
    action: z.literal("createBranch"),
    branch: z.string().trim().min(1).max(MAX_PATH_LENGTH),
  }),
  z.object({
    action: z.literal("commit"),
    message: z.string().trim().min(1).max(MAX_PROMPT_LENGTH),
  }),
  z.object({
    action: z.literal("push"),
  }),
  z.object({
    action: z.literal("pull"),
  }),
  z.object({
    action: z.literal("stash"),
    message: z.string().trim().min(1).max(MAX_PROMPT_LENGTH).optional(),
  }),
]);
export type RepoActionRequest = z.infer<typeof RepoActionRequest>;

export const RepoActionResponse = z.object({
  ok: z.literal(true),
  summary: z.string(),
  repoStatus: RepoStatus,
});
export type RepoActionResponse = z.infer<typeof RepoActionResponse>;

export const RepoLogEntry = z.object({
  hash: z.string(),
  shortHash: z.string(),
  subject: z.string(),
  author: z.string(),
  authoredAt: z.string(),
});
export type RepoLogEntry = z.infer<typeof RepoLogEntry>;

export const RepoLogResponse = z.object({
  entries: z.array(RepoLogEntry),
});
export type RepoLogResponse = z.infer<typeof RepoLogResponse>;

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

export const UnarchiveSessionsRequest = z.object({
  sessionIds: z.array(z.string().max(MAX_ID_LENGTH)).min(1).max(100),
});
export type UnarchiveSessionsRequest = z.infer<typeof UnarchiveSessionsRequest>;

export const UnarchiveSessionsResponse = z.object({
  ok: z.literal(true),
  unarchivedCount: z.number().int().nonnegative(),
});
export type UnarchiveSessionsResponse = z.infer<typeof UnarchiveSessionsResponse>;

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
