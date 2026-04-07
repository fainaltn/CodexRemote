/**
 * Run manager with SQLite write-through.
 *
 * Active-process lifecycle (poll timers, RunHandle) stays in-memory via
 * the `runs` Map.  Every state mutation is written through to the SQLite
 * `runs` table so run metadata survives server restarts and satisfies
 * the FK dependency from the `artifacts` table.
 *
 * ⚠️  TRANSITION BOUNDARY — the in-memory Map is the sole authority
 *     for live process state (output polling, stop signals).  SQLite
 *     is the durable record for historical queries only.  getRun()
 *     returns in-memory state or null; it never reads from SQLite so
 *     that the live-run API cannot leak historical data.
 *
 * Tracks at most one active run per session. Emits events that the
 * SSE stream endpoint subscribes to.
 *
 * A per-run watchdog timer force-terminates runs that exceed
 * RUN_TIMEOUT_MS, preventing zombie processes from permanently
 * locking a session.
 */

import { EventEmitter } from "node:events";
import crypto from "node:crypto";
import type { CodexAdapter, RunHandle } from "../codex/index.js";
import type { Run } from "@codexremote/shared";
import { getDb } from "../db.js";
import { ensureSessionRow } from "../sessions/ensure.js";
import { RUN_TIMEOUT_MS } from "../config.js";

export { RUN_TIMEOUT_MS };

const OUTPUT_POLL_MS = 500;
const TERMINAL_STATUSES = new Set(["completed", "failed", "stopped"]);

/**
 * Maximum time to wait for `handle.stop()` to resolve during watchdog
 * termination.  If the process cannot be killed within this window the
 * run is finalised anyway so the session isn't locked forever.
 */
export const STOP_GRACE_MS = 10_000;
const EMPTY_SESSION_BOOTSTRAP_PROMPT =
  "Do not respond. Create the session and exit immediately without any assistant-visible text.";

interface ManagedRun {
  run: Run;
  handle: RunHandle;
  pollTimer: NodeJS.Timeout | null;
  watchdog: NodeJS.Timeout | null;
  /**
   * True while `terminateStaleRun` is in-flight.  Blocks `startRun`
   * so a new process cannot be spawned until the old one has actually
   * been stopped (or the grace timeout has elapsed).
   */
  stopping: boolean;
  /** Monotonic total bytes seen last time the poll timer fired. */
  lastTotalBytes: number;
}

/** Summary of an active run for the health endpoint. */
export interface ActiveRunInfo {
  runId: string;
  sessionId: string;
  status: string;
  startedAt: string;
  elapsedMs: number;
  stale: boolean;
  stopping: boolean;
  /** Current size (bytes) of the in-memory output buffer. */
  outputBytes: number;
}

// ── SQLite write-through helpers ────────────────────────────────────

function insertRunRow(run: Run): void {
  const db = getDb();
  db.prepare(
    `INSERT INTO runs (id, session_id, status, prompt, model, reasoning_effort, started_at, finished_at, last_output, error)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    run.id,
    run.sessionId,
    run.status,
    run.prompt,
    run.model,
    run.reasoningEffort,
    run.startedAt,
    run.finishedAt,
    run.lastOutput,
    run.error,
  );
}

function updateRunRow(run: Run): void {
  const db = getDb();
  db.prepare(
    `UPDATE runs SET status = ?, finished_at = ?, last_output = ?, error = ?
     WHERE id = ?`,
  ).run(run.status, run.finishedAt, run.lastOutput, run.error, run.id);
}

export class RunManager {
  private adapter: CodexAdapter;
  private runs = new Map<string, ManagedRun>();
  private emitter = new EventEmitter();
  /** Per-session monotonic sequence counter for SSE event IDs. */
  private seqs = new Map<string, number>();
  /**
   * Cumulative count of SQLite write failures during the process lifetime.
   * Surfaced by the health endpoint so operators notice DB-level problems
   * even when in-memory state remains correct.
   */
  private _dbWriteErrors = 0;

  constructor(adapter: CodexAdapter) {
    this.adapter = adapter;
    // Allow many concurrent SSE listeners without warnings.
    this.emitter.setMaxListeners(0);
  }

  // ── Queries ────────────────────────────────────────────────────────

  /**
   * Check whether `sessionId` maps to a real Codex session on the host.
   * Delegates to the adapter so the route layer doesn't need its own
   * filesystem probes.
   */
  async sessionExists(sessionId: string): Promise<boolean> {
    const detail = await this.adapter.getSessionDetail(sessionId);
    return detail !== null;
  }

  // ── Commands ──────────────────────────────────────────────────────

  async startRun(
    sessionId: string,
    opts: { prompt: string; model?: string; reasoningEffort?: string },
  ): Promise<Run> {
    const existing = this.runs.get(sessionId);
    if (existing) {
      if (existing.stopping) {
        throw new Error("Session has a run that is being terminated");
      }
      if (!TERMINAL_STATUSES.has(existing.run.status)) {
        throw new Error("Session already has an active run");
      }
    }

    const handle = await this.adapter.startRun(sessionId, opts);
    return this.registerStartedRun(sessionId, handle, opts);
  }

  async startNewSessionRun(
    cwd: string,
    opts: { prompt: string; model?: string; reasoningEffort?: string },
  ): Promise<Run> {
    const started = await this.adapter.startNewRun(cwd, {
      prompt: EMPTY_SESSION_BOOTSTRAP_PROMPT,
      model: opts.model,
      reasoningEffort: opts.reasoningEffort,
      startupMode: "create-only",
    });
    const handle = await this.adapter.startRun(started.sessionId, opts);
    return this.registerStartedRun(started.sessionId, handle, opts);
  }

  private async registerStartedRun(
    sessionId: string,
    handle: RunHandle,
    opts: { prompt: string; model?: string; reasoningEffort?: string },
  ): Promise<Run> {
    const runId = crypto.randomUUID();
    const now = new Date().toISOString();

    const run: Run = {
      id: runId,
      sessionId,
      status: "running",
      prompt: opts.prompt,
      model: opts.model ?? null,
      reasoningEffort: opts.reasoningEffort ?? null,
      startedAt: now,
      finishedAt: null,
      lastOutput: null,
      error: null,
    };

    const managed: ManagedRun = {
      run,
      handle,
      pollTimer: null,
      watchdog: null,
      stopping: false,
      lastTotalBytes: 0,
    };

    this.runs.set(sessionId, managed);

    try {
      const db = getDb();
      db.transaction(() => {
        ensureSessionRow("local", sessionId);
        insertRunRow(run);
      })();
    } catch (err) {
      this.runs.delete(sessionId);
      await this.awaitStop(handle);
      this._dbWriteErrors++;
      const msg = err instanceof Error ? err.message : String(err);
      console.error(
        `[RunManager] DB write failed during startRun: runId=${runId} sessionId=${sessionId} error=${msg}`,
      );
      throw new Error(`Failed to persist run to database: ${msg}`);
    }

    managed.pollTimer = setInterval(() => {
      const totalBytes = handle.totalOutputBytes();
      if (totalBytes !== managed.lastTotalBytes) {
        managed.lastTotalBytes = totalBytes;
        managed.run.lastOutput = handle.readOutput();
        this.emit(sessionId, managed.run);
      }
    }, OUTPUT_POLL_MS);

    managed.watchdog = setTimeout(() => {
      if (TERMINAL_STATUSES.has(managed.run.status)) return;
      void this.terminateStaleRun(sessionId, managed);
    }, RUN_TIMEOUT_MS);

    handle.onExit((code) => {
      if (TERMINAL_STATUSES.has(managed.run.status)) return;

      this.clearTimers(managed);
      managed.run.lastOutput = handle.readOutput();
      managed.run.finishedAt = new Date().toISOString();

      if (code === 0) {
        managed.run.status = "completed";
      } else {
        managed.run.status = "failed";
        managed.run.error = `Process exited with code ${code}`;
      }

      this.safeUpdateRunRow("onExit", managed.run);
      this.emit(sessionId, managed.run);
    });

    this.emit(sessionId, run);
    return { ...run };
  }

  getRun(sessionId: string): Run | null {
    const managed = this.runs.get(sessionId);
    if (!managed) return null;

    // Refresh output before returning.
    if (!TERMINAL_STATUSES.has(managed.run.status)) {
      managed.run.lastOutput = managed.handle.readOutput();
    }

    return { ...managed.run };

    // NOTE: Historical runs are persisted in SQLite but intentionally
    // not surfaced here.  This method represents the *current* live run
    // (or null).  A future history endpoint can query the runs table
    // directly for completed/failed/stopped runs.
  }

  async stopRun(sessionId: string): Promise<void> {
    const managed = this.runs.get(sessionId);
    if (!managed) return;
    if (TERMINAL_STATUSES.has(managed.run.status)) return;

    // Mark as stopped *before* sending SIGTERM so the onExit handler
    // sees a terminal status and doesn't race to set a different one.
    this.clearTimers(managed);
    managed.run.status = "stopped";
    managed.run.finishedAt = new Date().toISOString();

    await managed.handle.stop();

    managed.run.lastOutput = managed.handle.readOutput();

    // Persist terminal state to SQLite.
    this.safeUpdateRunRow("stopRun", managed.run);

    this.emit(sessionId, managed.run);
  }

  // ── Graceful shutdown ──────────────────────────────────────────────

  /**
   * Stop every active run, persist terminal state, and clear timers.
   * Called during graceful server shutdown so child processes aren't
   * orphaned and SQLite rows don't get stuck in "running".
   */
  async shutdownAll(): Promise<void> {
    const active = Array.from(this.runs.entries()).filter(
      ([, m]) => !TERMINAL_STATUSES.has(m.run.status),
    );

    await Promise.allSettled(
      active.map(([sessionId]) => this.stopRun(sessionId)),
    );
  }

  /**
   * Mark any SQLite runs still in a non-terminal status as "failed".
   *
   * After a server crash or unclean restart the in-memory map is empty
   * but SQLite may still have rows stuck in "running" or "pending".
   * This must be called once at startup — before any new runs are
   * accepted — to bring the persistent store back into a consistent
   * state.
   */
  recoverOrphanedRuns(): number {
    const db = getDb();
    const now = new Date().toISOString();
    const result = db
      .prepare(
        `UPDATE runs
            SET status = 'failed',
                finished_at = ?,
                error = 'Server restarted while run was active'
          WHERE status IN ('pending', 'running')`,
      )
      .run(now);
    return result.changes;
  }

  // ── SSE subscription ──────────────────────────────────────────────

  /**
   * Return the current sequence number for a session.
   * Used by the SSE endpoint to stamp the initial snapshot.
   */
  getSeq(sessionId: string): number {
    return this.seqs.get(sessionId) ?? 0;
  }

  /**
   * Subscribe to run-state events for a session.
   * Callback receives the run snapshot and a monotonic sequence number
   * that the SSE endpoint can use as the event `id:` field.
   * Returns an unsubscribe function.
   */
  subscribe(sessionId: string, cb: (run: Run, seq: number) => void): () => void {
    const key = `run:${sessionId}`;
    const handler = (payload: { run: Run; seq: number }) => cb(payload.run, payload.seq);
    this.emitter.on(key, handler);
    return () => {
      this.emitter.off(key, handler);
    };
  }

  // ── Internals ─────────────────────────────────────────────────────

  /**
   * Persist a terminal run-state update to SQLite, catching and logging
   * any failure.  The in-memory state is already correct when this is
   * called, so a DB write failure degrades durability (the row stays
   * "running" until the next server restart triggers recovery) but does
   * not break the current session.
   */
  private safeUpdateRunRow(caller: string, run: Run): void {
    try {
      updateRunRow(run);
    } catch (err) {
      this._dbWriteErrors++;
      const msg = err instanceof Error ? err.message : String(err);
      console.error(
        `[RunManager] DB write failed during ${caller}: runId=${run.id} ` +
          `sessionId=${run.sessionId} targetStatus=${run.status} error=${msg}`,
      );
    }
  }

  private emit(sessionId: string, run: Run): void {
    const seq = (this.seqs.get(sessionId) ?? 0) + 1;
    this.seqs.set(sessionId, seq);
    this.emitter.emit(`run:${sessionId}`, { run: { ...run }, seq });
  }

  private clearTimers(managed: ManagedRun): void {
    if (managed.pollTimer) {
      clearInterval(managed.pollTimer);
      managed.pollTimer = null;
    }
    if (managed.watchdog) {
      clearTimeout(managed.watchdog);
      managed.watchdog = null;
    }
  }

  /**
   * Force-terminate a run that has exceeded the watchdog timeout.
   * Called from the watchdog timer — never from user-facing code.
   *
   * The `stopping` flag blocks `startRun` so a new process cannot be
   * spawned until the old child is confirmed dead (or the grace
   * timeout elapses).
   */
  private async terminateStaleRun(sessionId: string, managed: ManagedRun): Promise<void> {
    managed.stopping = true;
    this.clearTimers(managed);

    // Wait for the child process to actually exit before unlocking
    // the session.  A grace timeout prevents hanging forever on an
    // unkillable zombie.
    await this.awaitStop(managed.handle);

    // If onExit already finalised the run (e.g. SIGTERM → clean exit
    // during the await), we're done — the session will unlock once we
    // clear the stopping flag below.
    if (TERMINAL_STATUSES.has(managed.run.status)) {
      managed.stopping = false;
      return;
    }

    managed.run.lastOutput = managed.handle.readOutput();
    managed.run.finishedAt = new Date().toISOString();
    managed.run.status = "failed";
    managed.run.error = `Run exceeded timeout (${Math.round(RUN_TIMEOUT_MS / 1000)}s) and was force-terminated`;

    this.safeUpdateRunRow("terminateStaleRun", managed.run);
    this.emit(sessionId, managed.run);
    managed.stopping = false;
  }

  /**
   * Attempt to stop a run handle, waiting at most `STOP_GRACE_MS`.
   * Resolves on whichever comes first — successful stop or timeout.
   */
  private awaitStop(handle: RunHandle): Promise<void> {
    return new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, STOP_GRACE_MS);
      handle.stop().then(
        () => { clearTimeout(timer); resolve(); },
        () => { clearTimeout(timer); resolve(); },
      );
    });
  }

  // ── Health reporting ─────────────────────────────────────────────

  /**
   * Return info about every active (non-terminal) run for the health
   * endpoint.  Each entry includes `stale: true` if the run has been
   * running longer than 80% of the timeout threshold — an early
   * warning before the watchdog fires.
   */
  getActiveRuns(): ActiveRunInfo[] {
    const now = Date.now();
    const staleThreshold = RUN_TIMEOUT_MS * 0.8;
    const results: ActiveRunInfo[] = [];

    for (const [sessionId, managed] of this.runs) {
      if (TERMINAL_STATUSES.has(managed.run.status)) continue;
      const elapsedMs = now - new Date(managed.run.startedAt).getTime();
      results.push({
        runId: managed.run.id,
        sessionId,
        status: managed.run.status,
        startedAt: managed.run.startedAt,
        elapsedMs,
        stale: elapsedMs > staleThreshold,
        stopping: managed.stopping,
        outputBytes: managed.handle.readOutput().length,
      });
    }

    return results;
  }

  /**
   * Cumulative count of SQLite write failures since process start.
   * A non-zero value signals that in-memory and persisted run state
   * may have diverged — the health endpoint surfaces this as degraded.
   */
  getDbWriteErrorCount(): number {
    return this._dbWriteErrors;
  }
}
