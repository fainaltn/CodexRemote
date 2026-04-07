/**
 * Graceful shutdown and orphaned-run recovery tests.
 *
 * Validates:
 *  - shutdownAll() stops all active runs and persists terminal state
 *  - recoverOrphanedRuns() marks stale "running"/"pending" rows as failed
 *  - recovery is idempotent on a clean database
 *  - recovery does not touch already-terminal runs
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
} from "./helpers.js";
import { buildApp } from "../app.js";
import { getDb } from "../db.js";
import { RunManager } from "../runs/manager.js";

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;

describe("Graceful shutdown", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    adapter.addSession("sess-2");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    if (!app) return;
    await app.close();
  });

  it("shutdownAll stops all active runs", async () => {
    // Start runs on two sessions.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "first" },
    });
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-2/live",
      headers: authHeader(token),
      payload: { prompt: "second" },
    });

    // Build a fresh RunManager that shares the adapter, then simulate
    // the shutdown path by getting the RunManager from a fresh buildApp.
    const { runManager } = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
    });

    // Start runs via the new manager to have them in its in-memory map.
    await runManager.startRun("sess-1", { prompt: "a" });
    await runManager.startRun("sess-2", { prompt: "b" });

    // Both should be running.
    expect(runManager.getRun("sess-1")?.status).toBe("running");
    expect(runManager.getRun("sess-2")?.status).toBe("running");

    // Shutdown.
    await runManager.shutdownAll();

    // Both should now be stopped.
    expect(runManager.getRun("sess-1")?.status).toBe("stopped");
    expect(runManager.getRun("sess-2")?.status).toBe("stopped");

    // SQLite should reflect the terminal state.
    const db = getDb();
    const rows = db
      .prepare("SELECT status FROM runs WHERE status = 'stopped'")
      .all() as { status: string }[];
    // At least the two runs from the new manager should be stopped.
    expect(rows.length).toBeGreaterThanOrEqual(2);
  });

  it("shutdownAll is safe when no runs are active", async () => {
    const { runManager } = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
    });

    // Should not throw.
    await expect(runManager.shutdownAll()).resolves.toBeUndefined();
  });
});

describe("Orphaned-run recovery", () => {
  beforeEach(() => {
    cleanTables();
  });

  function seedOrphanedRun(
    runId: string,
    sessionId: string,
    status: string,
  ): void {
    const db = getDb();
    // Ensure host + session rows exist for FK integrity.
    db.prepare(
      "INSERT OR IGNORE INTO hosts (id, label, kind, status) VALUES ('local', 'local', 'local', 'online')",
    ).run();
    db.prepare(
      "INSERT OR IGNORE INTO sessions (id, host_id, provider, title) VALUES (?, 'local', 'codex', ?)",
    ).run(sessionId, sessionId);
    db.prepare(
      `INSERT INTO runs (id, session_id, status, prompt, started_at)
       VALUES (?, ?, ?, 'test prompt', datetime('now'))`,
    ).run(runId, sessionId, status);
  }

  it("recoverOrphanedRuns marks running rows as failed", () => {
    seedOrphanedRun("run-orphan-1", "sess-1", "running");
    seedOrphanedRun("run-orphan-2", "sess-2", "pending");

    const adapter = new MockCodexAdapter();
    const manager = new RunManager(adapter);
    const recovered = manager.recoverOrphanedRuns();

    expect(recovered).toBe(2);

    const db = getDb();
    const rows = db
      .prepare("SELECT id, status, error, finished_at FROM runs ORDER BY id")
      .all() as { id: string; status: string; error: string | null; finished_at: string | null }[];

    for (const row of rows) {
      expect(row.status).toBe("failed");
      expect(row.error).toBe("Server restarted while run was active");
      expect(row.finished_at).toBeTruthy();
    }
  });

  it("recoverOrphanedRuns does not touch terminal runs", () => {
    seedOrphanedRun("run-completed", "sess-1", "completed");
    seedOrphanedRun("run-failed", "sess-1", "failed");
    seedOrphanedRun("run-stopped", "sess-1", "stopped");

    const adapter = new MockCodexAdapter();
    const manager = new RunManager(adapter);
    const recovered = manager.recoverOrphanedRuns();

    expect(recovered).toBe(0);

    const db = getDb();
    const statuses = db
      .prepare("SELECT status FROM runs ORDER BY id")
      .all() as { status: string }[];

    expect(statuses.map((r) => r.status)).toEqual([
      "completed",
      "failed",
      "stopped",
    ]);
  });

  it("recoverOrphanedRuns is idempotent on empty table", () => {
    const adapter = new MockCodexAdapter();
    const manager = new RunManager(adapter);
    expect(manager.recoverOrphanedRuns()).toBe(0);
  });

  it("buildApp with recovery enabled fixes orphaned runs at startup", async () => {
    seedOrphanedRun("run-crash-1", "sess-1", "running");

    const adapter = new MockCodexAdapter();
    adapter.addSession("sess-1");

    // buildApp with recovery enabled (the default).
    const { app: recoveryApp } = await buildApp({
      logger: false,
      adapter,
    });

    const db = getDb();
    const row = db
      .prepare("SELECT status, error FROM runs WHERE id = 'run-crash-1'")
      .get() as { status: string; error: string };

    expect(row.status).toBe("failed");
    expect(row.error).toBe("Server restarted while run was active");

    await recoveryApp.close();
  });
});
