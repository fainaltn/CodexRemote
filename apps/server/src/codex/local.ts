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
  scanSessionDirs,
  sessionDirExists,
  readSessionMeta,
  readSessionMessages,
  spawnCodexAppServerNewThread,
  spawnCodexAppServerResumeRun,
  spawnCodexRun,
  spawnCodexNewRun,
} from "./cli.js";

const PREFERRED_SESSION_READY_TIMEOUT_MS = 8_000;
const PREFERRED_SESSION_READY_POLL_MS = 150;

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
    // Unknown session → null (not an empty detail object)
    if (!(await sessionDirExists(codexSessionId))) {
      return null;
    }

    const meta = await readSessionMeta(codexSessionId);
    return {
      codexSessionId,
      cwd: meta.cwd,
      lastActivityAt: meta.lastActivityAt,
      title: meta.title,
      lastPreview: meta.lastPreview,
    };
  }

  async getSessionMessages(codexSessionId: string) {
    if (!(await sessionDirExists(codexSessionId))) {
      return [];
    }
    return readSessionMessages(codexSessionId);
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
      { model: options.model, reasoningEffort: options.reasoningEffort },
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
