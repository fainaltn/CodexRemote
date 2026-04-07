/**
 * Run watchdog timeout tests.
 *
 * Validates:
 *  - watchdog force-terminates a run after RUN_TIMEOUT_MS
 *  - timed-out run is persisted as "failed" in SQLite
 *  - timed-out run calls handle.stop()
 *  - session is unlocked after timeout (new run can start)
 *  - watchdog is cleared on normal exit (no double-terminate)
 *  - watchdog is cleared on manual stop (no double-terminate)
 *  - session is blocked while watchdog stop is in-flight (deferred stop)
 *  - session unlocks once deferred stop completes
 *  - grace timeout finalises run even when stop never resolves
 *  - onExit during watchdog stop preserves the exit-driven status
 *  - getActiveRuns reports stale/stopping runs
 *  - health endpoint surfaces stale runs as degraded
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
import { RunManager, RUN_TIMEOUT_MS, STOP_GRACE_MS } from "../runs/manager.js";

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;

describe("Run watchdog timeout", () => {
  beforeEach(async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    // Resolve any deferred stops so cleanup doesn't hang.
    adapter.lastRunHandle?.resolveStop();
    vi.useRealTimers();
    if (!app) return;
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });
    await app.close();
  });

  // ── Basic watchdog behaviour ──────────────────────────────────────

  it("force-terminates a run after the timeout", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "hang forever" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("partial output");

    let res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(res.body).status).toBe("running");

    // Advance past watchdog; use async variant to flush microtasks
    // from the now-async terminateStaleRun.
    await vi.advanceTimersByTimeAsync(RUN_TIMEOUT_MS + 100);

    res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(res.body);
    expect(run.status).toBe("failed");
    expect(run.error).toContain("timeout");
    expect(run.error).toContain("force-terminated");
    expect(run.finishedAt).toBeTruthy();
    expect(run.lastOutput).toBe("partial output");
  });

  it("persists timeout failure to SQLite", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "persist test" },
    });

    await vi.advanceTimersByTimeAsync(RUN_TIMEOUT_MS + 100);

    const db = getDb();
    const rows = db
      .prepare("SELECT status, error FROM runs WHERE session_id = 'sess-1' ORDER BY started_at DESC LIMIT 1")
      .all() as { status: string; error: string }[];
    expect(rows.length).toBe(1);
    expect(rows[0].status).toBe("failed");
    expect(rows[0].error).toContain("timeout");
  });

  it("calls stop on the run handle when timing out", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "stop check" },
    });

    const handle = adapter.lastRunHandle!;
    expect(handle.stopped).toBe(false);

    await vi.advanceTimersByTimeAsync(RUN_TIMEOUT_MS + 100);

    expect(handle.stopped).toBe(true);
  });

  it("unlocks the session after timeout so a new run can start", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "first run" },
    });

    await vi.advanceTimersByTimeAsync(RUN_TIMEOUT_MS + 100);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "second run" },
    });
    expect(res.statusCode).toBe(201);
  });

  // ── No double-terminate ───────────────────────────────────────────

  it("does not double-terminate on normal exit before timeout", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "fast run" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("done");
    handle.simulateExit(0);

    let res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(res.body).status).toBe("completed");

    await vi.advanceTimersByTimeAsync(RUN_TIMEOUT_MS + 100);

    res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(res.body).status).toBe("completed");
  });

  it("does not double-terminate on manual stop before timeout", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "manual stop" },
    });

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });

    let res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(res.body).status).toBe("stopped");

    await vi.advanceTimersByTimeAsync(RUN_TIMEOUT_MS + 100);

    res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(JSON.parse(res.body).status).toBe("stopped");
  });

  // ── Async stop lifecycle (deferred-stop scenarios) ────────────────

  it("rejects new run while watchdog stop is in-flight", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "zombie run" },
    });

    const handle = adapter.lastRunHandle!;
    // Make handle.stop() pend indefinitely until we resolve it.
    handle.deferStop();

    // Fire the watchdog — terminateStaleRun starts but blocks on
    // the deferred stop.  Use sync advance so we can inspect the
    // intermediate state before microtasks finish.
    vi.advanceTimersByTime(RUN_TIMEOUT_MS + 100);

    // The session should be locked — stopping flag is set.
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "should be rejected" },
    });
    expect(res.statusCode).toBe(409);
    expect(JSON.parse(res.body).error).toContain("being terminated");
  });

  it("unlocks session once deferred stop completes", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "deferred run" },
    });

    const handle = adapter.lastRunHandle!;
    handle.deferStop();

    // Fire watchdog (sync) — stopping flag set, awaiting deferred stop.
    vi.advanceTimersByTime(RUN_TIMEOUT_MS + 100);

    // Still locked.
    let res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "rejected" },
    });
    expect(res.statusCode).toBe(409);

    // Resolve the deferred stop, then flush microtasks so
    // terminateStaleRun finishes.
    handle.resolveStop();
    await vi.advanceTimersByTimeAsync(0);

    // Now the session should be unlocked.
    res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "allowed" },
    });
    expect(res.statusCode).toBe(201);
  });

  it("grace timeout finalises run when stop never resolves", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "unkillable" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("zombie output");
    // stop() will never resolve.
    handle.deferStop();

    // Fire watchdog (sync) — starts terminateStaleRun, awaiting
    // the deferred stop.
    vi.advanceTimersByTime(RUN_TIMEOUT_MS + 100);

    // Still locked while waiting for stop.
    let res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "still locked" },
    });
    expect(res.statusCode).toBe(409);

    // Advance past the grace timeout — awaitStop resolves via its
    // internal timer even though handle.stop() never did.
    await vi.advanceTimersByTimeAsync(STOP_GRACE_MS + 100);

    // Run should now be failed and session unlocked.
    res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(res.body);
    expect(run.status).toBe("failed");
    expect(run.error).toContain("timeout");
    expect(run.lastOutput).toBe("zombie output");

    // Session should be unlocked for a new run.
    res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "fresh start" },
    });
    expect(res.statusCode).toBe(201);
  });

  it("onExit during watchdog stop preserves exit-driven status", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "exit during stop" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("final words");
    handle.deferStop();

    // Fire watchdog (sync) — terminateStaleRun starts, awaiting stop.
    vi.advanceTimersByTime(RUN_TIMEOUT_MS + 100);

    // Simulate the process exiting while stop is pending.
    // The onExit handler should finalise with the exit code.
    handle.simulateExit(0);

    // Resolve the deferred stop and flush microtasks.
    handle.resolveStop();
    await vi.advanceTimersByTimeAsync(0);

    // onExit set "completed" before terminateStaleRun resumed;
    // terminateStaleRun should have respected that and not overwritten.
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(res.body);
    expect(run.status).toBe("completed");
    expect(run.lastOutput).toBe("final words");

    // Session should be unlocked.
    const startRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "next run" },
    });
    expect(startRes.statusCode).toBe(201);
  });
});

describe("getActiveRuns health reporting", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    adapter.addSession("sess-2");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    if (!app) return;
    for (const sid of ["sess-1", "sess-2"]) {
      await app.inject({
        method: "POST",
        url: `/api/hosts/local/sessions/${sid}/live/stop`,
        headers: authHeader(token),
      });
    }
    await app.close();
  });

  it("getActiveRuns returns active run info via RunManager", async () => {
    const manager = new RunManager(adapter);
    await manager.startRun("sess-1", { prompt: "test" });

    const active = manager.getActiveRuns();
    expect(active).toHaveLength(1);
    expect(active[0].sessionId).toBe("sess-1");
    expect(active[0].status).toBe("running");
    expect(active[0].stale).toBe(false);
    expect(active[0].stopping).toBe(false);
    expect(active[0].elapsedMs).toBeGreaterThanOrEqual(0);

    await manager.shutdownAll();
  });

  it("health endpoint reports active runs", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "health check" },
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const body = JSON.parse(res.body);
    expect(body.checks.runs).toBeDefined();
    expect(body.checks.runs.active).toBe(1);
    expect(body.checks.runs.stale).toBe(0);
    expect(body.status).toBe("ok");
  });

  it("health endpoint reports degraded when stale runs exist", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "stale run" },
    });

    // Advance past 80% of timeout (stale threshold).
    vi.advanceTimersByTime(RUN_TIMEOUT_MS * 0.85);

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const body = JSON.parse(res.body);
    expect(body.status).toBe("degraded");
    expect(body.checks.runs.stale).toBe(1);
    expect(body.checks.staleRuns).toHaveLength(1);
    expect(body.checks.staleRuns[0].sessionId).toBe("sess-1");

    vi.useRealTimers();
  });
});
