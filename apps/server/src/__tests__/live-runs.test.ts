/**
 * Live-run lifecycle tests.
 *
 * Validates:
 *  - start / get / stop lifecycle
 *  - conflict when a second run is started on an active session
 *  - unknown session → 404
 *  - unknown host → 404
 *  - ability to start a new run after the previous one stops
 *  - natural exit (adapter-side) transitions run to completed/failed
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

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;

describe("Live-run routes", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    if (!app) return;
    // Stop any active run to clean up poll timers before closing.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });
    await app.close();
  });

  // ── Basic lifecycle ───────────────────────────────────────────────

  it("GET live with no active run returns null", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(res.body)).toBeNull();
  });

  it("POST live starts a run and returns 201 with runId", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "hello world" },
    });
    expect(res.statusCode).toBe(201);

    const body = JSON.parse(res.body);
    expect(body).toHaveProperty("runId");
    expect(typeof body.runId).toBe("string");
  });

  it("GET live after start returns running state", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "hello" },
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(200);

    const run = JSON.parse(res.body);
    expect(run).not.toBeNull();
    expect(run.status).toBe("running");
    expect(run.prompt).toBe("hello");
    expect(run.sessionId).toBe("sess-1");
    expect(run.startedAt).toBeTruthy();
    expect(run.finishedAt).toBeNull();
  });

  it("POST live/stop transitions run to stopped", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "hello" },
    });

    const stopRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });
    expect(stopRes.statusCode).toBe(200);
    expect(JSON.parse(stopRes.body)).toEqual({ ok: true });

    const getRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(getRes.body);
    expect(run.status).toBe("stopped");
    expect(run.finishedAt).toBeTruthy();
  });

  // ── Conflict ──────────────────────────────────────────────────────

  it("starting a second run while one is active returns 409", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "first" },
    });

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "second" },
    });
    expect(res.statusCode).toBe(409);
  });

  it("can start a new run after the previous one is stopped", async () => {
    // Start and stop first run.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "first" },
    });
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });

    // Start second run — should succeed.
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "second" },
    });
    expect(res.statusCode).toBe(201);
  });

  // ── Unknown session / host ────────────────────────────────────────

  it("GET live for unknown session returns 404", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/no-such-session/live",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(404);
  });

  it("POST live for unknown session returns 404", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/no-such-session/live",
      headers: authHeader(token),
      payload: { prompt: "hello" },
    });
    expect(res.statusCode).toBe(404);
  });

  it("POST live/stop for unknown session returns 404", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/no-such-session/live/stop",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(404);
  });

  it("GET live for unknown host returns 404", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/remote-99/sessions/sess-1/live",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(404);
  });

  // ── Auth enforcement on live-run routes ───────────────────────────

  it("live-run routes require authentication", async () => {
    const endpoints = [
      { method: "GET" as const, url: "/api/hosts/local/sessions/sess-1/live" },
      {
        method: "POST" as const,
        url: "/api/hosts/local/sessions/sess-1/live",
      },
      {
        method: "POST" as const,
        url: "/api/hosts/local/sessions/sess-1/live/stop",
      },
      {
        method: "GET" as const,
        url: "/api/hosts/local/sessions/sess-1/live/stream",
      },
    ];

    for (const ep of endpoints) {
      const res = await app.inject({
        method: ep.method,
        url: ep.url,
        payload: ep.method === "POST" ? { prompt: "x" } : undefined,
      });
      expect(
        res.statusCode,
        `${ep.method} ${ep.url} should be 401 without token`,
      ).toBe(401);
    }
  });

  // ── Natural exit ──────────────────────────────────────────────────

  it("natural exit with code 0 transitions run to completed", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "run to completion" },
    });

    // Simulate the mock process exiting with code 0.
    const handle = adapter.lastRunHandle!;
    handle.appendOutput("done!");
    handle.simulateExit(0);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(res.body);
    expect(run.status).toBe("completed");
    expect(run.finishedAt).toBeTruthy();
    expect(run.lastOutput).toBe("done!");
  });

  it("natural exit with non-zero code transitions run to failed", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "will fail" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("error output");
    handle.simulateExit(1);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(res.body);
    expect(run.status).toBe("failed");
    expect(run.error).toContain("code 1");
  });

  // ── Invalid request body ──────────────────────────────────────────

  it("POST live with empty prompt returns 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "" },
    });
    expect(res.statusCode).toBe(400);
  });

  it("POST live with missing prompt returns 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: {},
    });
    expect(res.statusCode).toBe(400);
  });
});
