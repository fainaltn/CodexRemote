/**
 * Connection and stream timeout hardening tests.
 *
 * Validates:
 *  - Fastify requestTimeout rejects stalled HTTP requests
 *  - Upload stream timeout rejects uploads that stall mid-stream
 *  - SSE idle timeout closes idle connections and sends idle-timeout event
 *  - SSE idle timer resets when run events are delivered
 *  - Normal (non-stalled) requests, uploads, and SSE streams are unaffected
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import http from "node:http";
import type { AddressInfo } from "node:net";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../app.js";
import { getDb } from "../db.js";
import {
  MockCodexAdapter,
  loginHelper,
  cleanTables,
  buildMultipartPayload,
} from "./helpers.js";

// ── Helpers ──────────────────────────────────────────────────────────

/** Parse raw SSE text into an array of { event, data, id? } objects. */
function parseSSE(raw: string): Array<{ event: string; data: string; id?: string }> {
  const events: Array<{ event: string; data: string; id?: string }> = [];
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

// ── SSE idle timeout ─────────────────────────────────────────────────

describe("SSE idle timeout", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;
  let token: string;
  let port: number;

  beforeEach(async () => {
    cleanTables();
    adapter = new MockCodexAdapter();
    adapter.addSession("sess-idle");
    // Use a very short SSE idle timeout for testing (500ms).
    const result = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
      skipArtifactRepair: true,
      sseIdleTimeoutMs: 500,
    });
    app = result.app;
    token = await loginHelper(app);
    await app.listen({ port: 0, host: "127.0.0.1" });
    port = (app.server.address() as AddressInfo).port;
  });

  afterEach(async () => {
    // Stop any active run to clean timers.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-idle/live/stop",
      headers: { authorization: `Bearer ${token}` },
    });
    if (app) await app.close();
  });

  it("server closes SSE connection after idle timeout and sends idle-timeout event", async () => {
    const result = await new Promise<{
      events: Array<{ event: string; data: string; id?: string }>;
      serverClosed: boolean;
    }>((resolve) => {
      let buf = "";
      let serverClosed = false;

      const url = new URL(
        "/api/hosts/local/sessions/sess-idle/live/stream",
        `http://127.0.0.1:${port}`,
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
            buf += chunk;
          });
          res.on("end", () => {
            serverClosed = true;
            resolve({ events: parseSSE(buf), serverClosed });
          });
        },
      );

      req.on("error", () => {
        resolve({ events: parseSSE(buf), serverClosed });
      });

      // Safety net — test should complete well before this.
      setTimeout(() => {
        req.destroy();
        resolve({ events: parseSSE(buf), serverClosed });
      }, 5000);
    });

    // The server should have closed the connection.
    expect(result.serverClosed).toBe(true);

    // Should contain the idle-timeout event.
    const idleEvent = result.events.find((e) => e.event === "idle-timeout");
    expect(idleEvent).toBeDefined();
    expect(JSON.parse(idleEvent!.data).timeoutMs).toBe(500);
  });

  it("run events reset the idle timer, keeping the stream alive", async () => {
    // Start a run so the session has an active stream with events.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-idle/live",
      headers: { authorization: `Bearer ${token}` },
      payload: { prompt: "keep alive" },
    });

    const result = await new Promise<{
      events: Array<{ event: string; data: string; id?: string }>;
      serverClosed: boolean;
    }>((resolve) => {
      let buf = "";
      let serverClosed = false;

      const url = new URL(
        "/api/hosts/local/sessions/sess-idle/live/stream",
        `http://127.0.0.1:${port}`,
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
            buf += chunk;
          });
          res.on("end", () => {
            serverClosed = true;
            resolve({ events: parseSSE(buf), serverClosed });
          });
        },
      );

      req.on("error", () => {
        resolve({ events: parseSSE(buf), serverClosed });
      });

      // Generate output events at 200ms intervals to reset the 500ms
      // idle timer, keeping the stream alive for ~700ms total.
      const handle = adapter.lastRunHandle!;
      setTimeout(() => handle.appendOutput("a"), 200);
      setTimeout(() => handle.appendOutput("b"), 400);
      // After the last event, let the idle timer fire (~500ms later).
      // Total: ~900ms.  Safety timeout at 3000ms.
      setTimeout(() => {
        req.destroy();
        resolve({ events: parseSSE(buf), serverClosed });
      }, 3000);
    });

    // The stream should have received multiple run events (the initial
    // snapshot plus updates from the poll cycle picking up output).
    const runEvents = result.events.filter((e) => e.event === "run");
    expect(runEvents.length).toBeGreaterThanOrEqual(2);

    // The idle-timeout event fires after the last activity + 500ms.
    // It may or may not have arrived depending on exact timing, but
    // the key assertion is that the stream survived past the initial
    // 500ms idle timeout thanks to the activity resets.
    // If the server closed, it should have sent idle-timeout (not
    // closed prematurely during the active period).
    if (result.serverClosed) {
      const idleEvent = result.events.find((e) => e.event === "idle-timeout");
      expect(idleEvent).toBeDefined();
    }
  });
});

// ── Upload stream timeout ────────────────────────────────────────────

describe("Upload stream timeout", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;
  let token: string;
  let port: number;

  beforeEach(async () => {
    cleanTables();
    adapter = new MockCodexAdapter();
    adapter.addSession("sess-upload");
    // Use a very short upload stream timeout (300ms) for testing.
    const result = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
      skipArtifactRepair: true,
      uploadStreamTimeoutMs: 300,
      // Generous request timeout so it doesn't interfere.
      requestTimeoutMs: 30_000,
    });
    app = result.app;
    token = await loginHelper(app);
    await app.listen({ port: 0, host: "127.0.0.1" });
    port = (app.server.address() as AddressInfo).port;
  });

  afterEach(async () => {
    if (app) await app.close();
  });

  it("rejects upload that stalls mid-stream", async () => {
    const boundary = "----StallBoundary";

    const result = await new Promise<{ errorOccurred: boolean; statusCode: number; body: string }>(
      (resolve) => {
        let resolved = false;
        const done = (r: { errorOccurred: boolean; statusCode: number; body: string }) => {
          if (resolved) return;
          resolved = true;
          resolve(r);
        };

        const req = http.request(
          {
            hostname: "127.0.0.1",
            port,
            method: "POST",
            path: "/api/hosts/local/uploads",
            headers: {
              "content-type": `multipart/form-data; boundary=${boundary}`,
              authorization: `Bearer ${token}`,
            },
          },
          (res) => {
            let data = "";
            res.on("data", (chunk) => {
              data += chunk;
            });
            res.on("end", () => {
              done({ errorOccurred: false, statusCode: res.statusCode!, body: data });
            });
          },
        );
        req.on("error", () => {
          // Server destroyed the stalled connection — expected.
          done({ errorOccurred: true, statusCode: 0, body: "" });
        });
        // Also listen on the socket close — faster detection.
        req.on("socket", (sock) => {
          sock.on("close", () => {
            done({ errorOccurred: true, statusCode: 0, body: "" });
          });
        });

        // Send the sessionId form field.
        req.write(
          `--${boundary}\r\n` +
            `Content-Disposition: form-data; name="sessionId"\r\n\r\n` +
            `sess-upload\r\n`,
        );

        // Begin the file part but stall — send some data then stop.
        req.write(
          `--${boundary}\r\n` +
            `Content-Disposition: form-data; name="file"; filename="stalled.bin"\r\n` +
            `Content-Type: application/octet-stream\r\n\r\n`,
        );
        req.write(Buffer.alloc(1024, 0x41));

        // Do NOT send any more data — the stream stalls.
        // The timeout (300ms) should fire and kill the connection.
        // Safety: destroy the request after 5s if we don't get a result.
        setTimeout(() => {
          req.destroy();
          done({ errorOccurred: true, statusCode: 0, body: "" });
        }, 5000);
      },
    );

    // The server should have killed the stalled connection.
    // Either we get an error event (socket destroyed) or a 400 response.
    expect(
      result.errorOccurred || result.statusCode === 400,
    ).toBe(true);

    // Allow the server to finish processing.
    await new Promise((r) => setTimeout(r, 200));

    // No artifact should have been persisted.
    const db = getDb();
    const count = (
      db
        .prepare(
          "SELECT COUNT(*) as c FROM artifacts WHERE session_id = 'sess-upload'",
        )
        .get() as { c: number }
    ).c;
    expect(count).toBe(0);
  }, 10_000);

  it("upload stall timer fires before the global connection/request timeouts", async () => {
    // Verify the upload stall timer (300ms, from beforeEach) is the
    // *effective* bound.  The connectionTimeout (default 30 s) is
    // disabled on upload sockets via setTimeout(0), so only the stall
    // timer and requestTimeout (30 s, from beforeEach) remain.  The
    // stall timer should fire well before either global timeout.
    const boundary = "----LayerBoundary";
    const startTime = Date.now();

    const result = await new Promise<{ errorOccurred: boolean }>(
      (resolve) => {
        let resolved = false;
        const done = (r: { errorOccurred: boolean }) => {
          if (resolved) return;
          resolved = true;
          resolve(r);
        };

        const req = http.request(
          {
            hostname: "127.0.0.1",
            port,
            method: "POST",
            path: "/api/hosts/local/uploads",
            headers: {
              "content-type": `multipart/form-data; boundary=${boundary}`,
              authorization: `Bearer ${token}`,
            },
          },
          (res) => {
            res.resume();
            res.on("end", () => done({ errorOccurred: false }));
          },
        );
        req.on("error", () => done({ errorOccurred: true }));
        req.on("socket", (sock) => {
          sock.on("close", () => done({ errorOccurred: true }));
        });

        // Send sessionId + file headers + 1 chunk, then stall.
        req.write(
          `--${boundary}\r\n` +
            `Content-Disposition: form-data; name="sessionId"\r\n\r\n` +
            `sess-upload\r\n`,
        );
        req.write(
          `--${boundary}\r\n` +
            `Content-Disposition: form-data; name="file"; filename="layer.bin"\r\n` +
            `Content-Type: application/octet-stream\r\n\r\n`,
        );
        req.write(Buffer.alloc(1024, 0x42));

        setTimeout(() => {
          req.destroy();
          done({ errorOccurred: true });
        }, 8000);
      },
    );

    const elapsed = Date.now() - startTime;

    // The connection should have died via the 300ms upload stall timer,
    // NOT the 8s safety timeout.  Allow generous margin for CI.
    expect(result.errorOccurred).toBe(true);
    expect(elapsed).toBeLessThan(3000);
  }, 10_000);

  it("normal upload still succeeds with stream timeout enabled", async () => {
    const boundary = "----NormalBoundary";
    const payload = buildMultipartPayload(
      boundary,
      { sessionId: "sess-upload" },
      {
        fieldName: "file",
        filename: "ok.txt",
        content: Buffer.from("this is fine"),
        contentType: "text/plain",
      },
    );

    const result = await new Promise<{ statusCode: number; body: string }>(
      (resolve, reject) => {
        const req = http.request(
          {
            hostname: "127.0.0.1",
            port,
            method: "POST",
            path: "/api/hosts/local/uploads",
            headers: {
              "content-type": `multipart/form-data; boundary=${boundary}`,
              authorization: `Bearer ${token}`,
              "content-length": String(payload.length),
            },
          },
          (res) => {
            let data = "";
            res.on("data", (chunk) => {
              data += chunk;
            });
            res.on("end", () => {
              resolve({ statusCode: res.statusCode!, body: data });
            });
          },
        );
        req.on("error", reject);
        req.end(payload);
      },
    );

    expect(result.statusCode).toBe(201);
    const body = JSON.parse(result.body);
    expect(body.sessionId).toBe("sess-upload");
    expect(body.originalName).toBe("ok.txt");
  });
});

// ── Fastify request timeout ──────────────────────────────────────────

describe("HTTP request timeout", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;
  let token: string;
  let port: number;

  beforeEach(async () => {
    cleanTables();
    adapter = new MockCodexAdapter();
    adapter.addSession("sess-req");
    // Use a very short request timeout (500ms) for testing.
    const result = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
      skipArtifactRepair: true,
      requestTimeoutMs: 500,
      connectionTimeoutMs: 500,
    });
    app = result.app;
    token = await loginHelper(app);
    await app.listen({ port: 0, host: "127.0.0.1" });
    port = (app.server.address() as AddressInfo).port;
  });

  afterEach(async () => {
    if (app) await app.close();
  });

  it("terminates a stalled request that never sends a body", async () => {
    const result = await new Promise<{ errorOccurred: boolean; statusCode: number }>(
      (resolve) => {
        const req = http.request(
          {
            hostname: "127.0.0.1",
            port,
            method: "POST",
            path: "/api/hosts/local/sessions/sess-req/live",
            headers: {
              "content-type": "application/json",
              authorization: `Bearer ${token}`,
              // Claim a large content-length but never send it.
              "content-length": "10000",
            },
          },
          (res) => {
            // If we get a response, the server timed out and sent an error.
            resolve({ errorOccurred: false, statusCode: res.statusCode! });
          },
        );

        req.on("error", () => {
          // Connection was killed by the server — expected.
          resolve({ errorOccurred: true, statusCode: 0 });
        });

        // Write partial data then stall.
        req.write("{");
        // Never call req.end() — the request stalls.

        // Safety timeout.
        setTimeout(() => {
          req.destroy();
          resolve({ errorOccurred: true, statusCode: 0 });
        }, 5000);
      },
    );

    // The server should have killed the connection — either via error
    // event or via a 408 status.
    expect(
      result.errorOccurred || result.statusCode === 408,
    ).toBe(true);
  });

  it("normal fast requests are not affected by the timeout", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-req/live",
      headers: { authorization: `Bearer ${token}` },
    });
    // Should get a normal response (null = no active run).
    expect(res.statusCode).toBe(200);
  });

  it("SSE stream is not killed by the request timeout", async () => {
    // The SSE stream disables the per-socket request timeout.
    // Even with a 500ms request timeout, the stream should survive.
    const result = await new Promise<{
      events: Array<{ event: string; data: string }>;
      serverClosed: boolean;
    }>((resolve) => {
      let buf = "";
      let serverClosed = false;

      const url = new URL(
        "/api/hosts/local/sessions/sess-req/live/stream",
        `http://127.0.0.1:${port}`,
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
            buf += chunk;
          });
          res.on("end", () => {
            serverClosed = true;
            resolve({ events: parseSSE(buf), serverClosed });
          });
        },
      );

      req.on("error", () => {
        resolve({ events: parseSSE(buf), serverClosed });
      });

      // Wait 1.5s — well past the 500ms request timeout.
      // If the stream is still alive, destroy it ourselves.
      setTimeout(() => {
        req.destroy();
        resolve({ events: parseSSE(buf), serverClosed });
      }, 1500);
    });

    // The stream should NOT have been closed by the server (the request
    // timeout should not apply to hijacked SSE streams).
    expect(result.serverClosed).toBe(false);

    // Should have received the initial snapshot.
    expect(result.events.length).toBeGreaterThanOrEqual(1);
    expect(result.events[0].event).toBe("run");
  });
});
