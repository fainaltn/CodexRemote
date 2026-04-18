import type { CodexAdapter } from "./interface.js";
import type {
  CodexSessionSummary,
  CodexSessionDetail,
  StartRunOptions,
  RunHandle,
  NewRunHandle,
} from "./types.js";
import {
  archiveCodexAppServerThread,
  detectCodexAppServerThreadStart,
  invalidateSessionDiscoveryCache,
  scanSessionDirs,
  sessionDirExists,
  readSessionMeta,
  readSessionMessages,
  spawnCodexAppServerNewThread,
  spawnCodexAppServerResumeRun,
  spawnCodexRun,
  spawnCodexNewRun,
} from "./cli.js";
import { getDb } from "../db.js";

const PREFERRED_SESSION_READY_TIMEOUT_MS = 8_000;
const PREFERRED_SESSION_READY_POLL_MS = 150;
const SESSION_METADATA_CACHE_TTL_MS = 15 * 60_000;

/**
 * Local Codex adapter — talks directly to the Codex CLI and its
 * on-disk session state on the current machine.
 *
 * This is the only adapter needed for the single-host Phase 1.
 * A future `RemoteCodexAdapter` would implement the same interface
 * by proxying through the host-agent REST API.
 */
export class LocalCodexAdapter implements CodexAdapter {
  // ── Session queries ──────────────────────────────────────────────

  async listSessions(): Promise<CodexSessionSummary[]> {
    const raw = await scanSessionDirs();
    persistSessionMetadataCache(raw);
    return raw.map((entry) => ({
      codexSessionId: entry.id,
      cwd: entry.cwd,
      lastActivityAt: entry.lastActivityAt,
      title: entry.title,
      lastPreview: entry.lastPreview,
    }));
  }

  async getSessionDetail(
    codexSessionId: string,
  ): Promise<CodexSessionDetail | null> {
    const startedAt = Date.now();
    const cached = getCachedSessionMetadata(codexSessionId);
    if (cached) {
      console.info(
        `[perf][adapter][session-detail] session=${codexSessionId} source=sqlite-cache ms=${Date.now() - startedAt}`,
      );
      return cached;
    }

    // Unknown session → null (not an empty detail object)
    if (!(await sessionDirExists(codexSessionId))) {
      console.info(
        `[perf][adapter][session-detail] session=${codexSessionId} source=miss ms=${Date.now() - startedAt}`,
      );
      return null;
    }

    const meta = await readSessionMeta(codexSessionId);
    const detail = {
      codexSessionId,
      cwd: meta.cwd,
      lastActivityAt: meta.lastActivityAt,
      title: meta.title,
      lastPreview: meta.lastPreview,
    };
    persistSessionMetadataCache([
      {
        id: codexSessionId,
        cwd: meta.cwd,
        lastActivityAt: meta.lastActivityAt,
        title: meta.title,
        lastPreview: meta.lastPreview,
      },
    ]);
    console.info(
      `[perf][adapter][session-detail] session=${codexSessionId} source=filesystem ms=${Date.now() - startedAt}`,
    );
    return detail;
  }

  async getSessionMessages(
    codexSessionId: string,
    options?: {
      limit?: number;
      beforeOrderIndex?: number;
    },
  ) {
    if (!(await sessionDirExists(codexSessionId))) {
      return [];
    }
    return readSessionMessages(codexSessionId, options);
  }

  // ── Run lifecycle ────────────────────────────────────────────────

  async startRun(
    codexSessionId: string,
    options: StartRunOptions,
  ): Promise<RunHandle> {
    const capability = await detectCodexAppServerThreadStart();
    if (capability.available) {
      try {
        const detail = await this.getSessionDetail(codexSessionId);
        const handle = await spawnCodexAppServerResumeRun(
          codexSessionId,
          options.prompt,
          {
            cwd: detail?.cwd ?? null,
            model: options.model,
            reasoningEffort: options.reasoningEffort,
            permissionMode: options.permissionMode,
          },
        );
        console.info(
          `[LocalCodexAdapter] startRun: preferred app-server resume succeeded sessionId=${codexSessionId}`,
        );
        return handle;
      } catch (error) {
        const reason =
          error instanceof Error
            ? error.message
            : "unknown preferred resume strategy error";
        console.warn(
          `[LocalCodexAdapter] startRun: preferred app-server resume failed for sessionId=${codexSessionId}, falling back to codex exec: ${reason}`,
        );
      }
    } else {
      console.warn(
        `[LocalCodexAdapter] startRun: preferred app-server resume unavailable for sessionId=${codexSessionId}, falling back to codex exec: ${capability.reason}`,
      );
    }

    const { child, readOutput, totalOutputBytes } = spawnCodexRun(
      codexSessionId,
      options.prompt,
      {
        model: options.model,
        reasoningEffort: options.reasoningEffort,
        permissionMode: options.permissionMode,
      },
    );

    const pid = child.pid;
    if (pid === undefined) {
      throw new Error("Failed to spawn codex process — no PID assigned");
    }

    return {
      pid,
      readOutput,
      totalOutputBytes,
      stop: () => {
        // Already reaped — nothing to wait for.
        if (child.exitCode !== null || child.signalCode !== null) {
          return Promise.resolve();
        }

        return new Promise<void>((resolve) => {
          // Safety timeout so the caller never hangs if the close
          // event was already emitted before we attached this listener.
          const safety = setTimeout(() => resolve(), 5_000);

          child.on("close", () => {
            clearTimeout(safety);
            resolve();
          });

          child.kill("SIGTERM");
        });
      },
      onExit: (cb) => {
        child.on("close", (code) => cb(code));
      },
    };
  }

  async startNewRun(
    cwd: string,
    options: StartRunOptions,
  ): Promise<NewRunHandle> {
    invalidateSessionDiscoveryCache();
    const startupMode = options.startupMode ?? "create-and-run";
    let spawned:
      | Awaited<ReturnType<typeof spawnCodexNewRun>>
      | Awaited<ReturnType<typeof spawnCodexAppServerNewThread>>
      | undefined;

    if (startupMode === "create-only") {
      console.info(
        `[LocalCodexAdapter] startNewRun create-only: checking preferred app-server thread/start strategy cwd=${cwd}`,
      );
      const capability = await detectCodexAppServerThreadStart();
      if (capability.available) {
        try {
          spawned = await spawnCodexAppServerNewThread(cwd, {
            model: options.model,
            prompt: options.prompt,
            permissionMode: options.permissionMode,
          });
          const preferredReadable = await waitForSessionReadable(
            spawned.sessionId,
          );
          if (!preferredReadable) {
            console.warn(
              `[LocalCodexAdapter] startNewRun create-only: preferred strategy returned sessionId=${spawned.sessionId} but session metadata was not readable in time, falling back to codex exec`,
            );
            spawned = undefined;
          }
        } catch (error) {
          const reason =
            error instanceof Error
              ? error.message
              : "unknown preferred strategy error";
          console.warn(
            `[LocalCodexAdapter] startNewRun create-only: preferred strategy failed, falling back to codex exec: ${reason}`,
          );
        }
      } else {
        console.warn(
          `[LocalCodexAdapter] startNewRun create-only: preferred strategy unavailable, falling back to codex exec: ${capability.reason}`,
        );
      }

      if (spawned) {
        console.info(
          `[LocalCodexAdapter] startNewRun create-only: preferred strategy succeeded sessionId=${spawned.sessionId}`,
        );
      }
    }

    if (!spawned) {
      spawned = await spawnCodexNewRun(cwd, options.prompt, {
        model: options.model,
        reasoningEffort: options.reasoningEffort,
        permissionMode: options.permissionMode,
      });
      if (startupMode === "create-only") {
        console.info(
          `[LocalCodexAdapter] startNewRun create-only: fallback codex exec succeeded sessionId=${spawned.sessionId}`,
        );
      }
    }

    const pid = spawned.child.pid;
    if (pid === undefined) {
      throw new Error("Failed to spawn codex process — no PID assigned");
    }

    return {
      sessionId: spawned.sessionId,
      handle: {
        pid,
        readOutput: spawned.readOutput,
        totalOutputBytes: spawned.totalOutputBytes,
        stop: () => {
          if (
            spawned.child.exitCode !== null ||
            spawned.child.signalCode !== null
          ) {
            return Promise.resolve();
          }

          return new Promise<void>((resolve) => {
            const safety = setTimeout(() => resolve(), 5_000);

            spawned.child.on("close", () => {
              clearTimeout(safety);
              resolve();
            });

            spawned.child.kill("SIGTERM");
          });
        },
        onExit: (cb) => {
          spawned.child.on("close", (code) => cb(code));
        },
      },
    };
  }

  async archiveSession(codexSessionId: string): Promise<void> {
    invalidateSessionDiscoveryCache();
    const capability = await detectCodexAppServerThreadStart();
    if (!capability.available) {
      console.warn(
        `[LocalCodexAdapter] archiveSession: preferred app-server strategy unavailable for sessionId=${codexSessionId}: ${capability.reason}`,
      );
      return;
    }

    try {
      await archiveCodexAppServerThread(codexSessionId);
      console.info(
        `[LocalCodexAdapter] archiveSession: preferred strategy succeeded sessionId=${codexSessionId}`,
      );
    } catch (error) {
      const reason =
        error instanceof Error ? error.message : "unknown archive strategy error";
      console.warn(
        `[LocalCodexAdapter] archiveSession: preferred strategy failed for sessionId=${codexSessionId}: ${reason}`,
      );
    }
  }

  /**
   * Stop a Codex process by PID.
   *
   * ⚠️  PLACEHOLDER — sends SIGTERM. A graceful shutdown protocol
   * may replace this once Codex CLI conventions are confirmed.
   */
  async stopRun(pid: number): Promise<void> {
    try {
      process.kill(pid, "SIGTERM");
    } catch {
      // Process may have already exited — that's acceptable.
    }
  }
}

function getCachedSessionMetadata(
  sessionId: string,
): CodexSessionDetail | null {
  const cutoff = new Date(Date.now() - SESSION_METADATA_CACHE_TTL_MS).toISOString();
  const row = getDb()
    .prepare(
      `SELECT session_id as sessionId,
              title,
              cwd,
              last_activity_at as lastActivityAt,
              last_preview as lastPreview
         FROM session_metadata_cache
        WHERE session_id = ?
          AND synced_at >= ?`,
    )
    .get(sessionId, cutoff) as {
    sessionId: string;
    title: string | null;
    cwd: string | null;
    lastActivityAt: string | null;
    lastPreview: string | null;
  } | undefined;

  if (!row) return null;
  return {
    codexSessionId: row.sessionId,
    cwd: row.cwd,
    lastActivityAt: row.lastActivityAt,
    title: row.title,
    lastPreview: row.lastPreview,
  };
}

export function hasCachedSessionMetadata(sessionId: string): boolean {
  const cutoff = new Date(Date.now() - SESSION_METADATA_CACHE_TTL_MS).toISOString();
  const row = getDb()
    .prepare(
      `SELECT 1
         FROM session_metadata_cache
        WHERE session_id = ?
          AND synced_at >= ?
        LIMIT 1`,
    )
    .get(sessionId, cutoff) as { 1: number } | undefined;
  return !!row;
}

function persistSessionMetadataCache(
  entries: Array<{
    id: string;
    cwd: string | null;
    lastActivityAt: string | null;
    title: string | null;
    lastPreview: string | null;
  }>,
): void {
  if (entries.length === 0) return;

  const db = getDb();
  const statement = db.prepare(
    `INSERT INTO session_metadata_cache (
        session_id,
        title,
        cwd,
        last_activity_at,
        last_preview,
        synced_at
      ) VALUES (?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
      ON CONFLICT(session_id) DO UPDATE SET
        title = excluded.title,
        cwd = excluded.cwd,
        last_activity_at = excluded.last_activity_at,
        last_preview = excluded.last_preview,
        synced_at = excluded.synced_at`,
  );

  db.transaction((rows: typeof entries) => {
    for (const entry of rows) {
      statement.run(
        entry.id,
        entry.title,
        entry.cwd,
        entry.lastActivityAt,
        entry.lastPreview,
      );
    }
  })(entries);
}

async function waitForSessionReadable(sessionId: string): Promise<boolean> {
  const deadline = Date.now() + PREFERRED_SESSION_READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (await sessionDirExists(sessionId)) {
      return true;
    }
    await new Promise((resolve) =>
      setTimeout(resolve, PREFERRED_SESSION_READY_POLL_MS),
    );
  }
  return false;
}
