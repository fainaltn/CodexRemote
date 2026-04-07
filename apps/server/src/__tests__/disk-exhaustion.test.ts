/**
 * Disk exhaustion safety tests.
 *
 * Validates:
 *  - createArtifact rejects with InsufficientDiskError when disk space
 *    is below the configured minimum
 *  - Upload route returns 507 when disk space is insufficient
 *  - Upload route audits the disk-space rejection
 *  - Health endpoint reports disk space metrics including the `low` flag
 *  - Health endpoint includes artifact storage totals
 *  - Normal uploads still succeed when disk has enough space (regression)
 */

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import fs from "node:fs/promises";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
  buildMultipartPayload,
} from "./helpers.js";
import { getDb } from "../db.js";

import * as storeModule from "../artifacts/store.js";

const BOUNDARY = "----DiskExhaustionBoundary";

/**
 * Helper: mock fs.statfs to simulate a filesystem with the given free
 * and total bytes.  Returns numbers consistent with a 4096-byte block size.
 */
function mockStatfs(freeBytes: number, totalBytes: number) {
  const bsize = 4096;
  return vi.spyOn(fs, "statfs").mockResolvedValue({
    bavail: Math.floor(freeBytes / bsize),
    blocks: Math.floor(totalBytes / bsize),
    bsize,
    bfree: Math.floor(freeBytes / bsize),
    ffree: 1_000_000,
    files: 10_000_000,
    type: 0,
  } as unknown as Awaited<ReturnType<typeof fs.statfs>>);
}

describe("Disk exhaustion safety", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;
  let token: string;

  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-disk");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    vi.restoreAllMocks();
    if (app) await app.close();
  });

  // ── Unit: InsufficientDiskError is a proper error ─────────────────

  it("InsufficientDiskError carries available and required bytes", () => {
    const err = new storeModule.InsufficientDiskError(1000, 2000);
    expect(err).toBeInstanceOf(Error);
    expect(err.name).toBe("InsufficientDiskError");
    expect(err.availableBytes).toBe(1000);
    expect(err.requiredBytes).toBe(2000);
    expect(err.message).toContain("1000");
    expect(err.message).toContain("2000");
  });

  // ── Unit: getDiskSpaceInfo returns real data ──────────────────────

  it("getDiskSpaceInfo returns totalBytes, freeBytes, and low flag", async () => {
    const info = await storeModule.getDiskSpaceInfo();
    expect(info).not.toBeNull();
    expect(info!.totalBytes).toBeGreaterThan(0);
    expect(info!.freeBytes).toBeGreaterThan(0);
    expect(typeof info!.low).toBe("boolean");
  });

  // ── Upload route: 507 on insufficient disk space ──────────────────

  it("returns 507 when disk space is below minimum", async () => {
    // Simulate a filesystem with only 50 bytes free.
    mockStatfs(50, 500 * 1024 * 1024 * 1024);

    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-disk" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("fake-png-bytes"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(507);
    const body = JSON.parse(res.body);
    expect(body.error).toContain("Insufficient disk space");
    expect(body).toHaveProperty("availableBytes");
    expect(body).toHaveProperty("requiredBytes");
  });

  // ── Upload route: 507 is audit-logged ─────────────────────────────

  it("audits disk-space rejection as upload_failure", async () => {
    mockStatfs(50, 500 * 1024 * 1024 * 1024);

    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-disk" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("fake-png-bytes"),
        contentType: "image/png",
      },
    );

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });

    const db = getDb();
    const row = db
      .prepare(
        "SELECT * FROM audit_log WHERE event_type = 'upload_failure' AND detail = 'insufficient_disk_space'",
      )
      .get() as { session_id: string; detail: string } | undefined;
    expect(row).toBeDefined();
    expect(row!.session_id).toBe("sess-disk");
  });

  // ── Upload route: no artifact persisted on disk rejection ─────────

  it("does not persist artifact on disk-space rejection", async () => {
    mockStatfs(50, 500 * 1024 * 1024 * 1024);

    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-disk" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("fake-png-bytes"),
        contentType: "image/png",
      },
    );

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });

    const db = getDb();
    const count = (
      db
        .prepare("SELECT COUNT(*) as c FROM artifacts WHERE session_id = 'sess-disk'")
        .get() as { c: number }
    ).c;
    expect(count).toBe(0);
  });

  // ── Regression: normal upload still succeeds ──────────────────────

  it("still creates artifact when disk has enough space", async () => {
    // No mock — real disk space should be ample in test environment.
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-disk" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("fake-png-bytes"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body).toHaveProperty("id");
    expect(body.sessionId).toBe("sess-disk");
  });

  // ── Health endpoint: disk space metrics ────────────────────────────

  it("health endpoint reports disk space info", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.checks).toHaveProperty("disk");
    expect(body.checks.disk).toHaveProperty("totalBytes");
    expect(body.checks.disk).toHaveProperty("freeBytes");
    expect(body.checks.disk).toHaveProperty("low");
    expect(body.checks.disk.totalBytes).toBeGreaterThan(0);
    expect(body.checks.disk.freeBytes).toBeGreaterThan(0);
    expect(body.checks.disk.low).toBe(false);
  });

  // ── Health endpoint: degraded when disk is low ────────────────────

  it("health reports degraded when disk space is low", async () => {
    mockStatfs(50, 500 * 1024 * 1024 * 1024);

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.status).toBe("degraded");
    expect(body.checks.disk.low).toBe(true);
  });

  // ── Health endpoint: storage metrics ──────────────────────────────

  it("health endpoint reports artifact storage totals", async () => {
    // Upload a file first so storage metrics are non-zero.
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-disk" },
      {
        fieldName: "file",
        filename: "size-check.bin",
        content: Buffer.alloc(1024, 0x42),
        contentType: "application/octet-stream",
      },
    );

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.checks.storage).toBeDefined();
    expect(body.checks.storage.artifactCount).toBe(1);
    expect(body.checks.storage.artifactTotalBytes).toBe(1024);
  });
});
