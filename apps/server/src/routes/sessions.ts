import crypto from "node:crypto";
import { execFile } from "node:child_process";
import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import { promisify } from "node:util";
import {
  ListSessionsParams,
  SessionParams,
  CreateSessionRequest,
  UpdateSessionTitleRequest,
  ArchiveSessionsRequest,
  SessionDetailResponse,
  RepoActionRequest,
  RepoActionResponse,
  RepoLogResponse,
  RepoStatusResponse,
  type RepoStatus,
  type RepoActionRequest as RepoActionRequestBody,
  type RepoLogEntry,
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
const execFileAsync = promisify(execFile);

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

        const messages = (await adapter.getSessionMessages(parsed.data.sessionId))
          .map((message, index) => ({
            ...message,
            orderIndex: message.orderIndex ?? index,
            isStreaming: message.isStreaming ?? false,
          }));
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

    // --- GET /api/hosts/:hostId/sessions/:sessionId/repo-status ---
    app.get(
      "/api/hosts/:hostId/sessions/:sessionId/repo-status",
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

        const repoStatus = await getRepoStatusForCwd(detail.cwd);
        const body = RepoStatusResponse.parse({ repoStatus });
        return reply.send(body);
      },
    );

    app.get(
      "/api/hosts/:hostId/sessions/:sessionId/repo-log",
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

        try {
          const entries = await getRepoLogForCwd(detail.cwd);
          return reply.send(RepoLogResponse.parse({ entries }));
        } catch (error) {
          const message =
            error instanceof Error ? error.message : "Repo log failed";
          return reply.status(409).send({ error: message });
        }
      },
    );

    // --- POST /api/hosts/:hostId/sessions/:sessionId/repo-action ---
    app.post(
      "/api/hosts/:hostId/sessions/:sessionId/repo-action",
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

        const bodyParsed = RepoActionRequest.safeParse(request.body);
        if (!bodyParsed.success) {
          return reply.status(400).send({ error: "Invalid request body" });
        }

        try {
          const result = await performRepoAction(detail.cwd, bodyParsed.data);
          const body = RepoActionResponse.parse({
            ok: true,
            summary: result.summary,
            repoStatus: result.repoStatus,
          });
          return reply.send(body);
        } catch (error) {
          const message =
            error instanceof Error ? error.message : "Repo action failed";
          return reply.status(409).send({ error: message });
        }
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

async function getRepoStatusForCwd(cwd: string | null): Promise<RepoStatus> {
  const safeCwd = cwd?.trim();
  if (!safeCwd) {
    return nonRepoStatus(safeCwd ?? "");
  }

  const rootPath = await runGitCommand(safeCwd, ["rev-parse", "--show-toplevel"]);
  if (!rootPath) {
    return nonRepoStatus(safeCwd);
  }

  const porcelain = await runGitCommand(safeCwd, [
    "status",
    "--porcelain=v1",
    "--branch",
    "--untracked-files=all",
  ]);
  if (!porcelain) {
    return nonRepoStatus(safeCwd);
  }

  return parseRepoStatus(safeCwd, rootPath, porcelain);
}

async function runGitCommand(
  cwd: string,
  args: string[],
): Promise<string | null> {
  try {
    const { stdout } = await execFileAsync("git", args, {
      cwd,
      encoding: "utf8",
      maxBuffer: 1024 * 1024,
      timeout: 5_000,
    });
    return stdout.trimEnd();
  } catch {
    return null;
  }
}

function parseRepoStatus(
  cwd: string,
  rootPath: string,
  porcelain: string,
): RepoStatus {
  const lines = porcelain.split(/\r?\n/).filter((line) => line.length > 0);
  const branchLine = lines[0] ?? "##";
  const branchInfo = branchLine.startsWith("## ") ? branchLine.slice(3) : branchLine;

  const detached = branchInfo.startsWith("HEAD");
  const branch = detached
    ? null
    : branchInfo.split("...")[0].split(" [")[0].trim() || null;

  let aheadBy: number | null = null;
  let behindBy: number | null = null;
  if (!detached) {
    const aheadMatch = branchInfo.match(/ahead (\d+)/);
    const behindMatch = branchInfo.match(/behind (\d+)/);
    aheadBy = aheadMatch ? Number(aheadMatch[1]) : null;
    behindBy = behindMatch ? Number(behindMatch[1]) : null;
  }

  let dirtyCount = 0;
  let stagedCount = 0;
  let unstagedCount = 0;
  let untrackedCount = 0;
  for (const line of lines.slice(1)) {
    if (line.startsWith("??")) {
      untrackedCount += 1;
    } else {
      dirtyCount += 1;
      const indexStatus = line[0] ?? " ";
      const worktreeStatus = line[1] ?? " ";
      if (indexStatus !== " " && indexStatus !== "?") {
        stagedCount += 1;
      }
      if (worktreeStatus !== " " && worktreeStatus !== "?") {
        unstagedCount += 1;
      }
    }
  }

  return {
    isRepo: true,
    cwd,
    rootPath,
    branch,
    detached,
    aheadBy,
    behindBy,
    dirtyCount,
    stagedCount,
    unstagedCount,
    untrackedCount,
  };
}

function nonRepoStatus(cwd: string): RepoStatus {
  return {
    isRepo: false,
    cwd,
    rootPath: null,
    branch: null,
    detached: false,
    aheadBy: null,
    behindBy: null,
    dirtyCount: 0,
    stagedCount: 0,
    unstagedCount: 0,
    untrackedCount: 0,
  };
}

async function performRepoAction(
  cwd: string | null,
  action: RepoActionRequestBody,
): Promise<{ summary: string; repoStatus: RepoStatus }> {
  const safeCwd = cwd?.trim();
  if (!safeCwd) {
    throw new Error("会话没有可用的项目目录");
  }

  const currentStatus = await getRepoStatusForCwd(safeCwd);
  if (!currentStatus.isRepo) {
    throw new Error("当前会话目录不是 Git 仓库");
  }

  switch (action.action) {
    case "checkout": {
      await assertValidBranchName(safeCwd, action.branch);
      await runGitCommandStrict(safeCwd, ["checkout", action.branch]);
      break;
    }
    case "createBranch": {
      await assertValidBranchName(safeCwd, action.branch);
      await runGitCommandStrict(safeCwd, ["checkout", "-b", action.branch]);
      break;
    }
    case "commit": {
      if ((currentStatus.dirtyCount ?? 0) === 0 && (currentStatus.untrackedCount ?? 0) === 0) {
        throw new Error("当前没有可提交的改动");
      }
      await runGitCommandStrict(safeCwd, ["add", "-A"]);
      await runGitCommandStrict(safeCwd, ["commit", "-m", action.message]);
      break;
    }
    case "push": {
      if (currentStatus.detached || !currentStatus.branch) {
        throw new Error("Detached HEAD 状态下不能直接推送");
      }
      await runGitCommandStrict(safeCwd, ["push", "-u", "origin", currentStatus.branch]);
      break;
    }
    case "pull": {
      if (currentStatus.detached || !currentStatus.branch) {
        throw new Error("Detached HEAD 状态下不能直接拉取");
      }
      await runGitCommandStrict(safeCwd, ["pull", "--ff-only"]);
      break;
    }
    case "stash": {
      if ((currentStatus.dirtyCount ?? 0) === 0 && (currentStatus.untrackedCount ?? 0) === 0) {
        throw new Error("当前没有可暂存的改动");
      }
      const stashMessage = action.message?.trim() || `CodexRemote stash ${new Date().toISOString()}`;
      await runGitCommandStrict(safeCwd, [
        "stash",
        "push",
        "--include-untracked",
        "-m",
        stashMessage,
      ]);
      break;
    }
  }

  const repoStatus = await getRepoStatusForCwd(safeCwd);
  return {
    summary: repoActionSummary(action),
    repoStatus,
  };
}

async function assertValidBranchName(cwd: string, branch: string): Promise<void> {
  await runGitCommandStrict(cwd, ["check-ref-format", "--branch", branch]);
}

async function runGitCommandStrict(
  cwd: string,
  args: string[],
): Promise<string> {
  try {
    const { stdout } = await execFileAsync("git", args, {
      cwd,
      encoding: "utf8",
      maxBuffer: 1024 * 1024,
      timeout: 10_000,
    });
    return stdout.trim();
  } catch (error) {
    const stderr = (error as { stderr?: string }).stderr?.trim();
    const stdout = (error as { stdout?: string }).stdout?.trim();
    const message = stderr || stdout || (error instanceof Error ? error.message : "Git 命令执行失败");
    throw new Error(message);
  }
}

function repoActionSummary(action: RepoActionRequestBody): string {
  switch (action.action) {
    case "checkout":
      return `已切换到分支 ${action.branch}`;
    case "createBranch":
      return `已创建并切换到 ${action.branch}`;
    case "commit":
      return "已提交当前改动";
    case "push":
      return "已推送当前分支";
    case "pull":
      return "已拉取远端最新提交";
    case "stash":
      return "已暂存当前改动";
  }
  return "已执行仓库操作";
}

async function getRepoLogForCwd(cwd: string | null): Promise<RepoLogEntry[]> {
  const safeCwd = cwd?.trim();
  if (!safeCwd) {
    throw new Error("会话没有可用的项目目录");
  }

  const currentStatus = await getRepoStatusForCwd(safeCwd);
  if (!currentStatus.isRepo) {
    throw new Error("当前会话目录不是 Git 仓库");
  }

  const output = await runGitCommandStrict(safeCwd, [
    "log",
    "--max-count=20",
    "--date=iso-strict",
    "--pretty=format:%H%x09%h%x09%an%x09%aI%x09%s",
  ]);

  if (!output) return [];

  return output
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const [hash, shortHash, author, authoredAt, ...subjectParts] = line.split("\t");
      return {
        hash,
        shortHash,
        author,
        authoredAt,
        subject: subjectParts.join("\t"),
      } satisfies RepoLogEntry;
    });
}
