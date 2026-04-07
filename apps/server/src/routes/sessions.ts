import crypto from "node:crypto";
import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import {
  ListSessionsParams,
  SessionParams,
  CreateSessionRequest,
  UpdateSessionTitleRequest,
  ArchiveSessionsRequest,
  SessionDetailResponse,
  type ListSessionsResponse,
  type CreateSessionResponse,
  type UpdateSessionTitleResponse,
  type ArchiveSessionsResponse,
  type Session,
} from "@codexremote/shared";
import type { CodexAdapter } from "../codex/index.js";
import { LOCAL_HOST_ID } from "../constants.js";
import { getDb } from "../db.js";
import type { RunManager } from "../runs/manager.js";
import { ensureSessionRow } from "../sessions/ensure.js";

const EMPTY_SESSION_BOOTSTRAP_PROMPT =
  "Do not respond. Create the session and exit immediately without any assistant-visible text.";
const SESSION_READY_TIMEOUT_MS = 5_000;
const SESSION_READY_POLL_MS = 150;
const SESSION_SHELL_EXIT_TIMEOUT_MS = 5_000;

/**
 * Session route group — list and detail for Phase 1 single-host.
 *
 * The adapter is injected via the factory so routes never reach for
 * globals or singletons.  This also makes it straightforward to swap
 * in a remote adapter in Phase 2.
 */
export function sessionRoutes(adapter: CodexAdapter, runManager?: RunManager) {
  return async function register(app: FastifyInstance): Promise<void> {
    // --- GET /api/hosts/:hostId/sessions ---
    app.get(
      "/api/hosts/:hostId/sessions",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const parsed = ListSessionsParams.safeParse(request.params);
        if (!parsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (parsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${parsed.data.hostId}' not found` });
        }

        const summaries = await adapter.listSessions();
        const stored = getStoredSessionRows(
          summaries.map((session) => session.codexSessionId),
        );
        const now = new Date().toISOString();

        const sessions: Session[] = summaries.map((s) => ({
          id: s.codexSessionId,
          hostId: LOCAL_HOST_ID,
          provider: "codex" as const,
          codexSessionId: s.codexSessionId,
          title:
            getPreferredTitle(stored.get(s.codexSessionId)?.title, s.codexSessionId) ??
            s.title ??
            s.codexSessionId,
          cwd: stored.get(s.codexSessionId)?.cwd ?? s.cwd,
          createdAt: stored.get(s.codexSessionId)?.createdAt ?? s.lastActivityAt ?? now,
          updatedAt: s.lastActivityAt ?? now,
          lastPreview: s.lastPreview ?? null,
          archivedAt: stored.get(s.codexSessionId)?.archivedAt ?? null,
        })).filter((session) => !session.archivedAt)
          .filter((session) => isDisplayableSession(session));

        const body: ListSessionsResponse = { sessions };
        return reply.send(body);
      },
    );

    // --- GET /api/hosts/:hostId/sessions/:sessionId ---
    app.get(
      "/api/hosts/:hostId/sessions/:sessionId",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const parsed = SessionParams.safeParse(request.params);
        if (!parsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (parsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${parsed.data.hostId}' not found` });
        }

        const detail = await adapter.getSessionDetail(parsed.data.sessionId);
        if (!detail) {
          return reply.status(404).send({
            error: `Session '${parsed.data.sessionId}' not found`,
          });
        }

        const messages = await adapter.getSessionMessages(parsed.data.sessionId);
        const stored = getStoredSessionRow(parsed.data.sessionId);

        const now = new Date().toISOString();
        const body = {
          session: {
            id: detail.codexSessionId,
            hostId: LOCAL_HOST_ID,
            provider: "codex" as const,
            codexSessionId: detail.codexSessionId,
            title:
              getPreferredTitle(stored?.title, detail.codexSessionId) ??
              detail.title ??
              detail.codexSessionId,
            cwd: stored?.cwd ?? detail.cwd,
            createdAt: stored?.createdAt ?? detail.lastActivityAt ?? now,
            updatedAt: detail.lastActivityAt ?? now,
            lastPreview: detail.lastPreview,
            archivedAt: stored?.archivedAt ?? null,
          },
          messages,
        };

        return reply.send(SessionDetailResponse.parse(body));
      },
    );

    app.post(
      "/api/hosts/:hostId/sessions",
      async (request: FastifyRequest, reply: FastifyReply) => {
        if (!runManager) {
          return reply.status(501).send({ error: "Session creation is not available" });
        }

        const params = ListSessionsParams.safeParse(request.params);
        if (!params.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (params.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${params.data.hostId}' not found` });
        }

        const bodyParsed = CreateSessionRequest.safeParse(request.body);
        if (!bodyParsed.success) {
          return reply.status(400).send({ error: "Invalid request body" });
        }

        try {
          const prompt = bodyParsed.data.prompt?.trim();
          let sessionId: string;
          let runId: string;

          if (prompt) {
            const run = await runManager.startNewSessionRun(bodyParsed.data.cwd, {
              prompt,
            });
            sessionId = run.sessionId;
            runId = run.id;
            await waitForSessionReady(adapter, sessionId);
          } else {
            const started = await adapter.startNewRun(bodyParsed.data.cwd, {
              prompt: EMPTY_SESSION_BOOTSTRAP_PROMPT,
              startupMode: "create-only",
            });
            sessionId = started.sessionId;
            runId = crypto.randomUUID();
            await waitForSessionReady(adapter, sessionId);
            await waitForHandleExit(started.handle, SESSION_SHELL_EXIT_TIMEOUT_MS);
          }

          const body: CreateSessionResponse = {
            sessionId,
            runId,
          };
          return reply.status(201).send(body);
        } catch (err) {
          const message =
            err instanceof Error ? err.message : "Failed to create session";
          return reply.status(500).send({ error: message });
        }
      },
    );

    app.patch(
      "/api/hosts/:hostId/sessions/:sessionId/title",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const parsed = SessionParams.safeParse(request.params);
        if (!parsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (parsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${parsed.data.hostId}' not found` });
        }

        const detail = await adapter.getSessionDetail(parsed.data.sessionId);
        if (!detail) {
          return reply.status(404).send({
            error: `Session '${parsed.data.sessionId}' not found`,
          });
        }

        const bodyParsed = UpdateSessionTitleRequest.safeParse(request.body);
        if (!bodyParsed.success) {
          return reply.status(400).send({ error: "Invalid request body" });
        }

        ensureSessionRow(LOCAL_HOST_ID, parsed.data.sessionId);
        getDb()
          .prepare(
            `UPDATE sessions
                SET title = ?, cwd = COALESCE(?, cwd), updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
              WHERE id = ?`,
          )
          .run(bodyParsed.data.title, detail.cwd ?? null, parsed.data.sessionId);

        const body: UpdateSessionTitleResponse = { ok: true };
        return reply.send(body);
      },
    );

    app.post(
      "/api/hosts/:hostId/sessions/archive",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = ListSessionsParams.safeParse(request.params);
        if (!params.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (params.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${params.data.hostId}' not found` });
        }

        const bodyParsed = ArchiveSessionsRequest.safeParse(request.body);
        if (!bodyParsed.success) {
          return reply.status(400).send({ error: "Invalid request body" });
        }

        let archivedCount = 0;
        for (const sessionId of bodyParsed.data.sessionIds) {
          const detail = await adapter.getSessionDetail(sessionId);
          if (!detail) continue;
          await adapter.archiveSession(sessionId);
          ensureSessionRow(LOCAL_HOST_ID, sessionId);
          getDb()
            .prepare(
              `UPDATE sessions
                  SET title = COALESCE(title, ?),
                      cwd = COALESCE(?, cwd),
                      archived_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                      updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                WHERE id = ?`,
            )
            .run(detail.title ?? sessionId, detail.cwd ?? null, sessionId);
          archivedCount += 1;
        }

        const body: ArchiveSessionsResponse = { ok: true, archivedCount };
        return reply.send(body);
      },
    );
  };
}

function isDisplayableSession(session: Session): boolean {
  const title = session.title.trim();
  const looksPlaceholder =
    title === "未命名会话" || title === session.id;
  const hasPreview = !!session.lastPreview?.trim();
  return hasPreview || !looksPlaceholder;
}

async function waitForSessionReady(
  adapter: CodexAdapter,
  sessionId: string,
): Promise<void> {
  const deadline = Date.now() + SESSION_READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const detail = await adapter.getSessionDetail(sessionId);
    if (detail) {
      return;
    }
    await delay(SESSION_READY_POLL_MS);
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHandleExit(
  handle: { onExit: (cb: (code: number | null) => void) => void },
  timeoutMs: number,
): Promise<void> {
  await Promise.race([
    new Promise<void>((resolve) => {
      handle.onExit(() => resolve());
    }),
    delay(timeoutMs),
  ]);
}

interface StoredSessionRow {
  id: string;
  title: string | null;
  cwd: string | null;
  createdAt: string | null;
  archivedAt: string | null;
}

function getStoredSessionRows(sessionIds: string[]): Map<string, StoredSessionRow> {
  if (sessionIds.length === 0) return new Map();
  const placeholders = sessionIds.map(() => "?").join(", ");
  const rows = getDb()
    .prepare(
      `SELECT id, title, cwd, created_at as createdAt
             , archived_at as archivedAt
         FROM sessions
        WHERE id IN (${placeholders})`,
    )
    .all(...sessionIds) as StoredSessionRow[];
  return new Map(rows.map((row) => [row.id, row]));
}

function getStoredSessionRow(sessionId: string): StoredSessionRow | null {
  return (
    (getDb()
      .prepare(
        `SELECT id, title, cwd, created_at as createdAt
               , archived_at as archivedAt
           FROM sessions
          WHERE id = ?`,
      )
      .get(sessionId) as StoredSessionRow | undefined) ?? null
  );
}

function getPreferredTitle(
  storedTitle: string | null | undefined,
  sessionId: string,
): string | null {
  if (!storedTitle) return null;
  return storedTitle === sessionId ? null : storedTitle;
}
