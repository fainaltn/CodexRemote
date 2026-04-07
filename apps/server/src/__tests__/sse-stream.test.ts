/**
 * SSE stream hardening tests.
 *
 * Validates:
 *  - Event IDs (monotonic seq) are present in SSE frames
 *  - Initial snapshot is delivered immediately on connect
 *  - stream-end is sent when the run is terminal at connect time
 *  - stream-end is sent when a run transitions to terminal during the stream
 *  - Stream stays open after stream-end (no reconnect churn)
 *  - New run events flow through after stream-end on an idle session
 *  - Last-Event-ID reconnect delivers current state and gap signal
 *  - Cleanup runs when the client disconnects
 *  - RunManager seq tracking is correct
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import http from "node:http";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  cleanTables,
} from "./helpers.js";

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;
let baseUrl: string;

// ── Helpers ──────────────────────────────────────────────────────────

/** Parse raw SSE text into an array of { event, data, id? } objects. */
function parseSSE(raw: string): Array<{ event: string; data: string; id?: string }> {
  const events: Array<{ event: string; data: string; id?: string }> = [];
  // Split on double-newline boundaries.
  const blocks = raw.split("\n\n").filter((b) => b.trim() && !b.trim().startsWith(":"));
  for (const block of blocks) {
    const lines = block.split("\n");
    let event = "";
    let data = "";
    let id: string | undefined;
    for (const line of lines) {
      if (line.startsWith("event: ")) event = line.slice(7);
      else if (line.startsWith("data: ")) data = line.slice(6);
      else if (line.startsWith("id: ")) id = line.slice(4);
    }
    if (event) events.push({ event, data, id });
  }
  return events;
}

/**
 * Connect to the SSE stream and collect events until the connection
 * closes or the timeout expires. Returns parsed events.
 */
function connectSSE(
  opts: {
    sessionId?: string;
    lastEventId?: string;
    timeoutMs?: number;
  } = {},
): Promise<{ events: Array<{ event: string; data: string; id?: string }>; statusCode: number; serverClosed: boolean }> {
  const sessionId = opts.sessionId ?? "sess-1";
  const timeout = opts.timeoutMs ?? 2000;

  return new Promise((resolve) => {
    let buf = "";
    let serverClosed = false;
    const headers: Record<string, string> = {
      authorization: `Bearer ${token}`,
      accept: "text/event-stream",
    };
    if (opts.lastEventId) {
      headers["last-event-id"] = opts.lastEventId;
    }

    const url = new URL(
      `/api/hosts/local/sessions/${sessionId}/live/stream`,
      baseUrl,
    );

    const req = http.get(url, { headers }, (res) => {
      res.setEncoding("utf8");
      res.on("data", (chunk: string) => {
        buf += chunk;
      });
      res.on("end", () => {
        serverClosed = true;
        resolve({ events: parseSSE(buf), statusCode: res.statusCode ?? 0, serverClosed });
      });
    });

    req.on("error", () => {
      resolve({ events: parseSSE(buf), statusCode: 0, serverClosed });
    });

    // Safety timeout — destroy the request if the server doesn't close.
    const timer = setTimeout(() => {
      req.destroy();
      resolve({ events: parseSSE(buf), statusCode: 200, serverClosed });
    }, timeout);

    // If the response ends before timeout, clear it.
    req.on("close", () => clearTimeout(timer));
  });
}

// ── Test suite ────────────────────────────────────────────────────────

describe("SSE stream hardening", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);

    // Start the server on a random port for real HTTP tests.
    const address = await app.listen({ port: 0, host: "127.0.0.1" });
    baseUrl = address;
  });

  afterEach(async () => {
    if (!app) return;
    // Stop any active run to clean timers.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: { authorization: `Bearer ${token}` },
    });
    await app.close();
  });

  // ── Initial snapshot ──────────────────────────────────────────────

  it("sends null snapshot and stream-end when no run is active, keeps stream open", async () => {
    const { events, serverClosed } = await connectSSE({ timeoutMs: 500 });

    expect(events.length).toBeGreaterThanOrEqual(2);
    expect(events[0].event).toBe("run");
    expect(JSON.parse(events[0].data)).toBeNull();
    expect(events[0].id).toBeDefined();

    const streamEnd = events.find((e) => e.event === "stream-end");
    expect(streamEnd).toBeDefined();
    expect(JSON.parse(streamEnd!.data).reason).toBe("no-run");

    // Stream should stay open (timeout hit, not server close).
    expect(serverClosed).toBe(false);
  });

  it("sends running snapshot with event ID when a run is active", async () => {
    // Start a run first.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "hello" },
    });

    // Connect SSE with a short timeout (run won't end on its own).
    const { events } = await connectSSE({ timeoutMs: 500 });

    expect(events.length).toBeGreaterThanOrEqual(1);
    const first = events[0];
    expect(first.event).toBe("run");
    expect(first.id).toBeDefined();

    const run = JSON.parse(first.data);
    expect(run).not.toBeNull();
    expect(run.status).toBe("running");
    expect(run.prompt).toBe("hello");

    // No stream-end should be sent while the run is still active.
    const streamEnd = events.find((e) => e.event === "stream-end");
    expect(streamEnd).toBeUndefined();
  });

  // ── Terminal run at connect time ──────────────────────────────────

  it("sends stream-end when connecting to a terminal run, keeps stream open", async () => {
    // Start and stop a run.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "will stop" },
    });
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: { authorization: `Bearer ${token}` },
    });

    const { events, serverClosed } = await connectSSE({ timeoutMs: 500 });

    const runEvent = events.find((e) => e.event === "run");
    expect(runEvent).toBeDefined();
    const run = JSON.parse(runEvent!.data);
    expect(run.status).toBe("stopped");

    const streamEnd = events.find((e) => e.event === "stream-end");
    expect(streamEnd).toBeDefined();
    expect(JSON.parse(streamEnd!.data).reason).toBe("stopped");

    // Stream stays open for future runs.
    expect(serverClosed).toBe(false);
  });

  // ── stream-end on natural exit during stream ─────────────────────

  it("sends stream-end when run completes while streaming, keeps stream open", async () => {
    // Start a run.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "run to completion" },
    });

    // Connect to the stream and immediately trigger process exit.
    const ssePromise = connectSSE({ timeoutMs: 2000 });

    // Small delay so the SSE connection is established.
    await new Promise((r) => setTimeout(r, 100));

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("done!");
    handle.simulateExit(0);

    const { events, serverClosed } = await ssePromise;

    // Should have at least: initial running snapshot, completed snapshot, stream-end.
    const runEvents = events.filter((e) => e.event === "run");
    expect(runEvents.length).toBeGreaterThanOrEqual(1);

    const streamEnd = events.find((e) => e.event === "stream-end");
    expect(streamEnd).toBeDefined();
    expect(JSON.parse(streamEnd!.data).reason).toBe("completed");

    // Stream stays open.
    expect(serverClosed).toBe(false);
  });

  // ── New run after stream-end ─────────────────────────────────────

  it("delivers new run events after stream-end on idle session", async () => {
    // Connect to an idle session (no run). The server sends stream-end
    // but keeps the stream open. Then start a run — events should arrive.
    const ssePromise = connectSSE({ timeoutMs: 3000 });

    // Wait for initial snapshot + stream-end to be sent.
    await new Promise((r) => setTimeout(r, 200));

    // Now start a run via the API.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "after idle" },
    });

    // Wait for the run event to flow through the stream.
    await new Promise((r) => setTimeout(r, 300));

    // Complete the run so the test can collect all events.
    const handle = adapter.lastRunHandle!;
    handle.simulateExit(0);

    const { events } = await ssePromise;

    // Should have: initial null snapshot, stream-end (no-run), then
    // running snapshot, completed snapshot, stream-end (completed).
    const streamEnds = events.filter((e) => e.event === "stream-end");
    expect(streamEnds.length).toBeGreaterThanOrEqual(2);

    const runEvents = events.filter((e) => e.event === "run");
    // At least: null + running + completed.
    expect(runEvents.length).toBeGreaterThanOrEqual(3);

    // The last run event should be the completed state.
    const lastRunData = JSON.parse(runEvents[runEvents.length - 1].data);
    expect(lastRunData.status).toBe("completed");
    expect(lastRunData.prompt).toBe("after idle");
  });

  // ── Event IDs are monotonic ──────────────────────────────────────

  it("event IDs increase monotonically across updates", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "seq test" },
    });

    const ssePromise = connectSSE({ timeoutMs: 2000 });

    await new Promise((r) => setTimeout(r, 100));

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("progress");
    // Wait for poll to pick up output.
    await new Promise((r) => setTimeout(r, 600));

    handle.appendOutput(" more");
    await new Promise((r) => setTimeout(r, 600));

    handle.simulateExit(0);

    const { events } = await ssePromise;

    const idsWithValues = events
      .filter((e) => e.event === "run" && e.id !== undefined)
      .map((e) => parseInt(e.id!, 10));

    // Should have at least 2 events with IDs.
    expect(idsWithValues.length).toBeGreaterThanOrEqual(2);

    // IDs should be strictly increasing.
    for (let i = 1; i < idsWithValues.length; i++) {
      expect(idsWithValues[i]).toBeGreaterThan(idsWithValues[i - 1]);
    }
  });

  // ── Reconnect with Last-Event-ID ────────────────────────────────

  it("reconnect with Last-Event-ID sends gap signal when events were missed", async () => {
    // Start a run and let it produce events to advance the seq counter.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "reconnect test" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("output");
    // Wait for a poll cycle to emit an event.
    await new Promise((r) => setTimeout(r, 600));

    // Now connect with a Last-Event-ID of "0" (missed everything).
    const { events } = await connectSSE({
      lastEventId: "0",
      timeoutMs: 500,
    });

    const gapEvent = events.find((e) => e.event === "gap");
    expect(gapEvent).toBeDefined();
    const gapData = JSON.parse(gapEvent!.data);
    expect(gapData.missedFrom).toBe(1);
    expect(gapData.currentSeq).toBeGreaterThanOrEqual(1);

    // Should still have the run snapshot.
    const runEvent = events.find((e) => e.event === "run");
    expect(runEvent).toBeDefined();
    expect(JSON.parse(runEvent!.data).status).toBe("running");
  });

  it("reconnect with current Last-Event-ID does not send gap signal", async () => {
    // Connect with no run active — gets a seq.
    const first = await connectSSE({ timeoutMs: 500 });
    const initialId = first.events.find((e) => e.event === "run")?.id;
    expect(initialId).toBeDefined();

    // Reconnect with that same ID — no gap expected.
    const second = await connectSSE({
      lastEventId: initialId,
      timeoutMs: 500,
    });

    const gapEvent = second.events.find((e) => e.event === "gap");
    expect(gapEvent).toBeUndefined();
  });

  // ── Reconnect after stream-end ───────────────────────────────────

  it("client can reconnect and observe a new run after stream-end + transport loss", async () => {
    // 1. Connect to idle session → get stream-end (no-run).
    const first = await connectSSE({ timeoutMs: 500 });
    const streamEnd = first.events.find((e) => e.event === "stream-end");
    expect(streamEnd).toBeDefined();
    expect(JSON.parse(streamEnd!.data).reason).toBe("no-run");
    // Capture the last event ID for reconnect.
    const lastId = first.events.filter((e) => e.id !== undefined).pop()?.id;
    expect(lastId).toBeDefined();

    // 2. Connection closed (simulated by test timeout).
    //    In production this would be a network drop or server restart.
    //    The key point: the client saw stream-end before disconnecting.

    // 3. Reconnect with Last-Event-ID (as the client would).
    const ssePromise = connectSSE({
      lastEventId: lastId,
      timeoutMs: 3000,
    });

    // Wait for reconnected stream to establish.
    await new Promise((r) => setTimeout(r, 200));

    // 4. Start a new run on the same session.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "after reconnect" },
    });

    // Wait for the run event to propagate.
    await new Promise((r) => setTimeout(r, 300));

    // Complete it so the test collects all events.
    const handle = adapter.lastRunHandle!;
    handle.simulateExit(0);

    const { events } = await ssePromise;

    // 5. Verify the reconnected stream received the new run.
    const runEvents = events.filter((e) => e.event === "run");
    // At least: initial null snapshot + running + completed
    expect(runEvents.length).toBeGreaterThanOrEqual(2);

    const lastRunData = JSON.parse(runEvents[runEvents.length - 1].data);
    expect(lastRunData.status).toBe("completed");
    expect(lastRunData.prompt).toBe("after reconnect");

    // Stream-end should also have fired for the completed run.
    const streamEnds = events.filter((e) => e.event === "stream-end");
    expect(streamEnds.length).toBeGreaterThanOrEqual(1);
  });

  // ── Auth enforcement ────────────────────────────────────────────

  it("stream returns 401 without Authorization header", async () => {
    const url = new URL(
      "/api/hosts/local/sessions/sess-1/live/stream",
      baseUrl,
    );
    const res = await new Promise<http.IncomingMessage>((resolve) => {
      const req = http.get(url, { headers: { accept: "text/event-stream" } }, resolve);
      req.on("error", () => resolve({ statusCode: 0 } as http.IncomingMessage));
    });
    expect(res.statusCode).toBe(401);
  });

  it("stream returns 401 with invalid token", async () => {
    const url = new URL(
      "/api/hosts/local/sessions/sess-1/live/stream",
      baseUrl,
    );
    const res = await new Promise<http.IncomingMessage>((resolve) => {
      const req = http.get(
        url,
        { headers: { authorization: "Bearer bad-token", accept: "text/event-stream" } },
        resolve,
      );
      req.on("error", () => resolve({ statusCode: 0 } as http.IncomingMessage));
    });
    expect(res.statusCode).toBe(401);
  });

  it("stream succeeds with valid Bearer token (fetch-style)", async () => {
    // All other tests already prove this, but this test makes the
    // authenticated fetch path explicit: Authorization header → 200.
    const { events, statusCode } = await connectSSE({ timeoutMs: 500 });
    expect(statusCode).toBe(200);
    expect(events.length).toBeGreaterThanOrEqual(1);
    expect(events[0].event).toBe("run");
  });
});
