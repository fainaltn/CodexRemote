/**
 * Upload abort protection tests.
 *
 * Validates:
 *  - Client disconnect mid-upload does not create a partial artifact
 *  - No orphaned file is left on disk after an aborted upload
 *  - Normal (complete) uploads still succeed as a regression check
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import http from "node:http";
import type { AddressInfo } from "node:net";
import type { FastifyInstance } from "fastify";
import path from "node:path";
import fs from "node:fs/promises";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  cleanTables,
  buildMultipartPayload,
  authHeader,
} from "./helpers.js";
import { getDb } from "../db.js";

const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"]!;
const ARTIFACTS_DIR = path.join(DATA_ROOT, "artifacts");

/** List files in a directory, returning [] if it doesn't exist. */
async function safeReaddir(dir: string): Promise<string[]> {
  try {
    return await fs.readdir(dir);
  } catch {
    return [];
  }
}

describe("Upload abort protection", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;
  let token: string;
  let port: number;

  beforeEach(async () => {
    cleanTables();
    await fs.rm(ARTIFACTS_DIR, { recursive: true, force: true });
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-abort");
    token = await loginHelper(app);
    // Start a real HTTP server for connection-level abort tests.
    await app.listen({ port: 0, host: "127.0.0.1" });
    port = (app.server.address() as AddressInfo).port;
  });

  afterEach(async () => {
    if (app) await app.close();
  });

  // ── Client disconnect mid-upload → no artifact ───────────────────

  it("discards upload when client disconnects mid-stream", async () => {
    const boundary = "----AbortBoundary";

    // Send a partial multipart upload and destroy the connection
    // before the closing boundary, simulating a mobile client that
    // drops its Tailscale connection mid-transfer.
    await new Promise<void>((resolve) => {
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
        () => resolve(),
      );
      req.on("error", () => resolve());

      // Send the sessionId form field.
      req.write(
        `--${boundary}\r\n` +
          `Content-Disposition: form-data; name="sessionId"\r\n\r\n` +
          `sess-abort\r\n`,
      );

      // Begin the file part but do NOT send the closing boundary.
      req.write(
        `--${boundary}\r\n` +
          `Content-Disposition: form-data; name="file"; filename="big.bin"\r\n` +
          `Content-Type: application/octet-stream\r\n\r\n`,
      );

      // Send some partial data.
      req.write(Buffer.alloc(4096, 0x42));

      // Destroy the connection immediately — simulates network drop.
      setImmediate(() => {
        req.destroy();
        // Give the server time to process the destroyed connection.
        setTimeout(resolve, 500);
      });
    });

    // Allow the server to finish processing the aborted request.
    await new Promise((r) => setTimeout(r, 500));

    // No artifact should have been created in SQLite.
    const db = getDb();
    const count = (
      db
        .prepare(
          "SELECT COUNT(*) as c FROM artifacts WHERE session_id = 'sess-abort'",
        )
        .get() as { c: number }
    ).c;
    expect(count).toBe(0);

    // No file should have been left on disk.
    const dir = path.join(ARTIFACTS_DIR, "local", "sess-abort");
    const files = await safeReaddir(dir);
    expect(files).toHaveLength(0);
  });

  // ── Regression: complete upload still succeeds ────────────────────

  it("still creates artifact for a complete upload", async () => {
    const boundary = "----CompleteBoundary";

    // Build a complete multipart payload.
    const payload = buildMultipartPayload(
      boundary,
      { sessionId: "sess-abort" },
      {
        fieldName: "file",
        filename: "complete.txt",
        content: Buffer.from("full content here"),
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
    expect(body.sessionId).toBe("sess-abort");
    expect(body.originalName).toBe("complete.txt");
    expect(body.sizeBytes).toBe(Buffer.from("full content here").length);
  });
});
