/**
 * SSE backpressure / slow-client hardening tests.
 *
 * Validates:
 *  - A slow/stuck client that cannot drain SSE data is detected via
 *    writableLength and its stream is closed before buffer growth
 *    becomes unbounded.
 *  - A normal-speed client is not affected by the backpressure guard
 *    even with a tight limit.
 */

import { describe, it, expect, afterEach } from "vitest";
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

const SESSION_ID = "sess-bp";

// ── Helpers ──────────────────────────────────────────────────────────

async function setup(sseWriteBufferMax: number): Promise<void> {
  cleanTables();
  ({ app, adapter } = await createTestApp(undefined, {
    sseWriteBufferMax,
  }));
  adapter.addSession(SESSION_ID);
  token = await loginHelper(app);
  baseUrl = await app.listen({ port: 0, host: "127.0.0.1" });
}

async function teardown(): Promise<void> {
  if (!app) return;
  try {
    await app.inject({
      method: "POST",
      url: `/api/hosts/local/sessions/${SESSION_ID}/live/stop`,
      headers: { authorization: `Bearer ${token}` },
    });
  } catch {
    /* ignore — run may have already ended */
  }
  await app.close();
}

// ── Test suite ───────────────────────────────────────────────────────

describe("SSE backpressure handling", () => {
  afterEach(teardown);

  it("closes SSE stream when a stuck client exceeds the backpressure limit", async () => {
    // Use a small buffer limit so backpressure triggers once the TCP
    // kernel buffers fill (typically ~128–256 KB on localhost).
    await setup(1024);

    // Start a run so poll-driven output events will fire.
    await app.inject({
      method: "POST",
      url: `/api/hosts/local/sessions/${SESSION_ID}/live`,
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "backpressure test" },
    });
    const handle = adapter.lastRunHandle!;

    const closed = await new Promise<boolean>((resolve) => {
      let resolved = false;
      let pump: NodeJS.Timeout | null = null;
      let safety: NodeJS.Timeout | null = null;

      const finish = (value: boolean) => {
        if (resolved) return;
        resolved = true;
        if (pump) clearInterval(pump);
        if (safety) clearTimeout(safety);
        resolve(value);
      };

      const url = new URL(
        `/api/hosts/local/sessions/${SESSION_ID}/live/stream`,
        baseUrl,
      );

      // Use the standard http client (proven by other SSE tests) and
      // immediately pause reading.  This causes the kernel receive
      // buffer to fill, triggering TCP flow control → server-side
      // socket.writableLength growth → backpressure guard fires.
      const req = http.get(
        url,
        {
          headers: {
            authorization: `Bearer ${token}`,
            accept: "text/event-stream",
          },
        },
        (res) => {
          // Stop consuming data immediately.
          res.pause();
          res.on("end", () => finish(true));
          res.on("close", () => finish(true));
        },
      );

      req.on("error", () => finish(true));

      // Pump large output changes so each poll cycle emits a big SSE
      // frame.  500 KB appends at 150 ms intervals produce multi-MB
      // events that overwhelm kernel buffers within a few seconds.
      const bigChunk = "x".repeat(500_000);
      let counter = 0;
      pump = setInterval(() => {
        handle.appendOutput(bigChunk + String(counter++));
      }, 150);

      // Safety: give up after 15 s if backpressure never triggers.
      safety = setTimeout(() => {
        req.destroy();
        finish(false);
      }, 15_000);
    });

    expect(closed).toBe(true);
  }, 20_000);

  it("does not close a normal-speed SSE client under the same limit", async () => {
    // Same tight limit — but with an actively-reading client, the
    // writable buffer should stay near zero and the stream should
    // survive for the whole observation window.
    await setup(1024);

    // Start a run with small output (well under 1 KB per event).
    await app.inject({
      method: "POST",
      url: `/api/hosts/local/sessions/${SESSION_ID}/live`,
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "normal client" },
    });
    const handle = adapter.lastRunHandle!;

    const result = await new Promise<{
      eventCount: number;
      closedByServer: boolean;
    }>((resolve) => {
      let eventCount = 0;
      let closedByServer = false;

      const url = new URL(
        `/api/hosts/local/sessions/${SESSION_ID}/live/stream`,
        baseUrl,
      );

      const req = http.get(
        url,
        {
          headers: {
            authorization: `Bearer ${token}`,
            accept: "text/event-stream",
          },
        },
        (res) => {
          res.setEncoding("utf8");
          res.on("data", (chunk: string) => {
            const matches = chunk.match(/\n\n/g);
            if (matches) eventCount += matches.length;
          });
          res.on("end", () => {
            closedByServer = true;
            done();
          });
        },
      );

      req.on("error", () => done());

      // Generate a few small output changes over 2 seconds.
      handle.appendOutput("chunk-1");
      setTimeout(() => handle.appendOutput(" chunk-2"), 600);
      setTimeout(() => handle.appendOutput(" chunk-3"), 1200);

      let finished = false;
      function done() {
        if (finished) return;
        finished = true;
        resolve({ eventCount, closedByServer });
      }

      // After the observation window, disconnect the client ourselves.
      // If the server hasn't force-closed us, the test passes.
      setTimeout(() => {
        req.destroy();
        done();
      }, 2500);
    });

    expect(result.eventCount).toBeGreaterThanOrEqual(1);
    expect(result.closedByServer).toBe(false);
  });
});
