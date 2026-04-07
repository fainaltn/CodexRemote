/**
 * DB write safety tests for the run lifecycle.
 *
 * Validates:
 *  - startRun uses a transaction so ensureSessionRow + insertRunRow are atomic
 *  - startRun cleans up the adapter process when the DB write fails
 *  - startRun rollback does not hang when handle.stop() never resolves
 *  - updateRunRow failures in onExit are caught and counted
 *  - updateRunRow failures in stopRun are caught and counted
 *  - health endpoint surfaces dbWriteErrors as degraded
 *
 * DB failures are simulated with SQLite BEFORE triggers that RAISE(ABORT),
 * which is safer than renaming tables — cleanup is a simple DROP TRIGGER.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
} from "./helpers.js";
import { getDb } from "../db.js";
import { RunManager, STOP_GRACE_MS } from "../runs/manager.js";

/** Install a trigger that makes INSERT on runs fail. */
function breakRunInserts(): void {
  const db = getDb();
  db.exec(`CREATE TRIGGER IF NOT EXISTS __test_fail_run_insert
    BEFORE INSERT ON runs
    BEGIN SELECT RAISE(ABORT, 'intentional insert failure'); END`);
}

/** Install a trigger that makes UPDATE on runs fail. */
function breakRunUpdates(): void {
  const db = getDb();
  db.exec(`CREATE TRIGGER IF NOT EXISTS __test_fail_run_update
    BEFORE UPDATE ON runs
    BEGIN SELECT RAISE(ABORT, 'intentional update failure'); END`);
}

/** Remove all test sabotage triggers. Safe to call before DB init. */
function restoreTriggers(): void {
  try {
    const db = getDb();
    db.exec("DROP TRIGGER IF EXISTS __test_fail_run_insert");
    db.exec("DROP TRIGGER IF EXISTS __test_fail_run_update");
  } catch {
    // DB not initialised yet — no triggers to clean.
  }
}

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;

describe("DB write safety — startRun", () => {
  beforeEach(async () => {
    restoreTriggers();
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    restoreTriggers();
    if (!app) return;
    await app.close();
  });

  it("startRun rolls back and stops the process when DB insert fails", async () => {
    breakRunInserts();

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "should fail" },
    });

    // The route should propagate the error as 500.
    expect(res.statusCode).toBe(500);

    // The adapter process should have been stopped.
    expect(adapter.lastRunHandle).not.toBeNull();
    expect(adapter.lastRunHandle!.stopped).toBe(true);

    // No in-memory run should remain.
    restoreTriggers();
    const getRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(getRes.body)).toBeNull();
  });

  it("startRun transaction is atomic — no orphaned session row on run INSERT failure", async () => {
    const db = getDb();

    // Remove any pre-existing session rows so we can check atomicity.
    db.exec("DELETE FROM sessions");
    db.exec("DELETE FROM hosts");

    breakRunInserts();

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "atomic test" },
    });

    restoreTriggers();

    // The transaction should have rolled back entirely — no session
    // row should exist from the failed startRun.
    const sessionRow = db
      .prepare("SELECT id FROM sessions WHERE id = 'sess-1'")
      .get();
    expect(sessionRow).toBeUndefined();
  });

  it("startRun rollback completes within STOP_GRACE_MS even when handle.stop() never resolves", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    // Pre-create a handle with a deferred stop so stop() hangs forever.
    const deferredHandle = new (await import("./helpers.js")).MockRunHandle();
    deferredHandle.deferStop();

    // Wire the adapter to return our deferred handle.
    let handleUsed = false;
    const origStartRun = adapter.startRun.bind(adapter);
    adapter.startRun = async (...args) => {
      if (!handleUsed) {
        handleUsed = true;
        adapter.lastRunHandle = deferredHandle;
        return deferredHandle;
      }
      return origStartRun(...args);
    };

    breakRunInserts();

    // startRun should NOT hang despite the deferred stop — the bounded
    // awaitStop fires its grace timer and resolves.
    const responsePromise = app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "deferred stop test" },
    });

    // Advance past the grace timeout so awaitStop resolves.
    await vi.advanceTimersByTimeAsync(STOP_GRACE_MS + 100);

    const res = await responsePromise;

    expect(res.statusCode).toBe(500);
    // stop() was called even though it never resolved.
    expect(deferredHandle.stopped).toBe(true);

    // Session is clean — no in-memory run left behind.
    restoreTriggers();
    const getRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(getRes.body)).toBeNull();

    // Resolve the deferred stop so no dangling promises leak.
    deferredHandle.resolveStop();
    vi.useRealTimers();
  });
});

describe("DB write safety — updateRunRow failures", () => {
  let manager: RunManager;

  beforeEach(async () => {
    restoreTriggers();
    cleanTables();
    adapter = new MockCodexAdapter();
    adapter.addSession("sess-1");
    manager = new RunManager(adapter);
  });

  afterEach(async () => {
    restoreTriggers();
    await manager.shutdownAll();
  });

  it("onExit catches updateRunRow failure and increments dbWriteErrors", async () => {
    await manager.startRun("sess-1", { prompt: "will break on exit" });
    const handle = adapter.lastRunHandle!;

    expect(manager.getDbWriteErrorCount()).toBe(0);

    breakRunUpdates();

    // Trigger natural exit — onExit handler calls safeUpdateRunRow.
    handle.appendOutput("final output");
    handle.simulateExit(0);

    // The in-memory state should still be correct.
    const run = manager.getRun("sess-1");
    expect(run).not.toBeNull();
    expect(run!.status).toBe("completed");
    expect(run!.lastOutput).toBe("final output");

    // DB write error should have been counted.
    expect(manager.getDbWriteErrorCount()).toBe(1);
  });

  it("stopRun catches updateRunRow failure and increments dbWriteErrors", async () => {
    await manager.startRun("sess-1", { prompt: "will break on stop" });

    expect(manager.getDbWriteErrorCount()).toBe(0);

    breakRunUpdates();

    // stopRun calls safeUpdateRunRow — should not throw.
    await manager.stopRun("sess-1");

    // In-memory state should still be correct.
    const run = manager.getRun("sess-1");
    expect(run).not.toBeNull();
    expect(run!.status).toBe("stopped");

    // DB write error should have been counted.
    expect(manager.getDbWriteErrorCount()).toBe(1);
  });

  it("dbWriteErrors accumulate across multiple failures", async () => {
    await manager.startRun("sess-1", { prompt: "multi-failure test" });

    // First failure: on stopRun.
    breakRunUpdates();
    await manager.stopRun("sess-1");
    expect(manager.getDbWriteErrorCount()).toBe(1);

    // Restore, start a new run, then break again for onExit.
    restoreTriggers();
    await manager.startRun("sess-1", { prompt: "second run" });

    breakRunUpdates();
    adapter.lastRunHandle!.simulateExit(1);
    expect(manager.getDbWriteErrorCount()).toBe(2);
  });
});

describe("DB write safety — health endpoint integration", () => {
  beforeEach(async () => {
    restoreTriggers();
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    restoreTriggers();
    if (!app) return;
    await app.close();
  });

  it("health endpoint reports dbWriteErrors: 0 when no failures", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const body = JSON.parse(res.body);
    expect(body.status).toBe("ok");
    expect(body.checks.dbWriteErrors).toBe(0);
  });

  it("health endpoint reports degraded when dbWriteErrors > 0", async () => {
    // Start a run, then break the DB, then stop it to trigger a write error.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "health test" },
    });

    breakRunUpdates();

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });

    restoreTriggers();

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const body = JSON.parse(res.body);
    expect(body.status).toBe("degraded");
    expect(body.checks.dbWriteErrors).toBeGreaterThan(0);
  });
});