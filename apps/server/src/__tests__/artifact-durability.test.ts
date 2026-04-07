/**
 * Artifact durability tests.
 *
 * Validates:
 *  - createArtifact rolls back disk file when artifact INSERT fails
 *  - createArtifact rolls back disk file when ensureSessionRow fails
 *  - createArtifact propagates disk-write errors without touching DB
 *  - auditArtifactConsistency detects missing files (DB row, no file)
 *  - auditArtifactConsistency detects orphaned files (file, no DB row)
 *  - auditArtifactConsistency returns clean report when consistent
 *  - health endpoint returns real DB + artifact checks
 *  - health endpoint reports degraded status on inconsistency
 */

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import fs from "node:fs/promises";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import {
  createArtifact,
  auditArtifactConsistency,
  getArtifact,
} from "../artifacts/store.js";
import { getDb, initDb } from "../db.js";
import * as ensureMod from "../sessions/ensure.js";
import {
  createTestApp,
  cleanTables,
  MockCodexAdapter,
} from "./helpers.js";

const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"]!;
const ARTIFACTS_DIR = path.join(DATA_ROOT, "artifacts");

/** Remove all files under the artifacts directory so tests are isolated. */
async function cleanArtifactsDisk(): Promise<void> {
  await fs.rm(ARTIFACTS_DIR, { recursive: true, force: true });
}

/** List files in a directory, returning [] if the directory doesn't exist. */
async function safeReaddir(dir: string): Promise<string[]> {
  try {
    return await fs.readdir(dir);
  } catch {
    return [];
  }
}

describe("Artifact durability", () => {
  beforeEach(async () => {
    cleanTables();
    await cleanArtifactsDisk();
  });

  // ── Disk rollback on artifact INSERT failure ─────────────────────

  it("rolls back disk file when DB insert fails", async () => {
    initDb();
    const db = getDb();

    // Ensure session row exists so the only failure point is the INSERT.
    db.prepare(
      "INSERT OR IGNORE INTO hosts (id, label, kind, status) VALUES ('local', 'Local', 'local', 'online')",
    ).run();
    db.prepare(
      "INSERT OR IGNORE INTO sessions (id, host_id, provider, codex_session_id, title, cwd, created_at, updated_at) VALUES ('sess-db-fail', 'local', 'codex', 'sess-db-fail', 'Test', '/tmp', datetime('now'), datetime('now'))",
    ).run();

    // Insert an artifact normally first so we have the ID.
    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-db-fail",
      originalName: "first.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("hello"),
    });

    // Verify file exists on disk.
    const firstStat = await fs.stat(artifact.storedPath);
    expect(firstStat.isFile()).toBe(true);

    // Sabotage db.prepare so the artifact INSERT throws.
    const origPrepare = db.prepare.bind(db);
    let callCount = 0;
    const prepareSpy = vi.spyOn(db, "prepare").mockImplementation((sql: string) => {
      if (sql.includes("INSERT INTO artifacts")) {
        callCount++;
        if (callCount === 1) {
          throw new Error("Simulated DB insert failure");
        }
      }
      return origPrepare(sql);
    });

    // Attempt  should throw and clean up disk file.createArtifact 
    await expect(
      createArtifact({
        hostId: "local",
        sessionId: "sess-db-fail",
        originalName: "second.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("should-be-cleaned-up"),
      }),
    ).rejects.toThrow("Failed to record artifact in database (disk file rolled back)");

    // Only the first artifact's file should remain on disk.
    const dir = path.join(ARTIFACTS_DIR, "local", "sess-db-fail");
    const files = await fs.readdir(dir);
    expect(files).toHaveLength(1);
    expect(files[0]).toContain("first.txt");

    // The DB should still only have the first artifact row.
    const dbRows = db.prepare("SELECT id FROM artifacts").all() as Array<{ id: string }>;
    expect(dbRows).toHaveLength(1);
    expect(dbRows[0].id).toBe(artifact.id);

    prepareSpy.mockRestore();
  });

  // ── Disk rollback on ensureSessionRow failure ──────────────────── 

  it("rolls back disk file when ensureSessionRow fails before INSERT", async () => {
    initDb();
    const db = getDb();

    // Sabotage ensureSessionRow to throw, simulating a DB lock / corruption.
    const ensureSpy = vi.spyOn(ensureMod, "ensureSessionRow").mockImplementation(() => {
      throw new Error("Simulated ensureSessionRow failure");
    });

    await expect(
      createArtifact({
        hostId: "local",
        sessionId: "sess-ensure-fail",
        originalName: "orphan-candidate.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("should-not-persist"),
      }),
    ).rejects.toThrow("Failed to record artifact in database (disk file rolled back)");

    // The file must not remain on disk.
    const dir = path.join(ARTIFACTS_DIR, "local", "sess-ensure-fail");
    const files = await safeReaddir(dir);
    expect(files).toHaveLength(0);

    // No artifact row should exist in the DB.
    const count = (
      db.prepare("SELECT COUNT(*) AS c FROM artifacts WHERE session_id = 'sess-ensure-fail'").get() as { c: number }
    ).c;
    expect(count).toBe(0);

    ensureSpy.mockRestore();
  });

  // ── Disk write failure doesn't leave partial DB state ─────────────

  it("propagates disk-write error without inserting DB row", async () => {
    initDb();
    const db = getDb();

    // Sabotage fs.writeFile to simulate a disk error.
    const writeSpy = vi.spyOn(fs, "writeFile").mockRejectedValueOnce(
      new Error("ENOSPC: no space left on device"),
    );

    const countBefore = (
      db.prepare("SELECT COUNT(*) AS c FROM artifacts").get() as { c: number }
    ).c;

    await expect(
      createArtifact({
        hostId: "local",
        sessionId: "sess-disk-fail",
        originalName: "big-file.bin",
        mimeType: "application/octet-stream",
        buffer: Buffer.from("data"),
      }),
    ).rejects.toThrow("Failed to write artifact file");

    // No row should have been inserted.
    const countAfter = (
      db.prepare("SELECT COUNT(*) AS c FROM artifacts").get() as { c: number }
    ).c;
    expect(countAfter).toBe(countBefore);

    writeSpy.mockRestore();
  });

  // ── Consistency audit ────────────────────────────────────────────

  it("reports clean when DB and disk are consistent", async () => {
    initDb();

    await createArtifact({
      hostId: "local",
      sessionId: "sess-audit",
      originalName: "doc.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("pdf-bytes"),
    });

    const report = await auditArtifactConsistency();
    expect(report.totalDbRows).toBe(1);
    expect(report.missingFiles).toHaveLength(0);
    expect(report.orphanedFiles).toHaveLength(0);
  });

  it("detects missing files (DB row without disk file)", async () => {
    initDb();

    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-missing",
      originalName: "gone.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("will-be-deleted"),
    });

    // Delete the file from disk, leaving the DB row.
    await fs.unlink(artifact.storedPath);

    const report = await auditArtifactConsistency();
    expect(report.missingFiles).toContain(artifact.id);
  });

  it("detects orphaned files (disk file without DB row)", async () => {
    initDb();

    // Create a real artifact so the directory structure exists.
    await createArtifact({
      hostId: "local",
      sessionId: "sess-orphan",
      originalName: "legit.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("legit"),
    });

    // Drop an orphan file into the same directory.
    const orphanDir = path.join(ARTIFACTS_DIR, "local", "sess-orphan");
    const orphanPath = path.join(orphanDir, "orphan-file.bin");
    await fs.writeFile(orphanPath, Buffer.from("orphan"));

    const report = await auditArtifactConsistency();
    expect(report.orphanedFiles).toHaveLength(1);
    expect(report.orphanedFiles[0]).toContain("orphan-file.bin");

    // Clean up.
    await fs.unlink(orphanPath);
  });
});

// ── Health endpoint integration ─────────────────────────────────────── 

describe("Health endpoint", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;

  beforeEach(async () => {
    cleanTables();
    await cleanArtifactsDisk();
    ({ app, adapter } = await createTestApp());
  });

  afterEach(async () => {
    if (app) await app.close();
  });

  it("returns status ok with checks when everything is consistent", async () => {
    const res = await app.inject({ method: "GET", url: "/api/health" });
    expect(res.statusCode).toBe(200);

    const body = JSON.parse(res.body);
    expect(body.status).toBe("ok");
    expect(body.checks).toBeDefined();
    expect(body.checks.database).toBe("ok");
    expect(body.checks.artifacts).toEqual({
      totalRows: 0,
      missingFiles: 0,
      orphanedFiles: 0,
    });
  });

  it("returns degraded when artifact file is missing", async () => {
    // Create an artifact through the store directly.
    adapter.addSession("sess-health");
    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-health",
      originalName: "temp.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("data"),
    });

    // Delete the file to create inconsistency.
    await fs.unlink(artifact.storedPath);

    const res = await app.inject({ method: "GET", url: "/api/health" });
    const body = JSON.parse(res.body);

    expect(body.status).toBe("degraded");
    expect(body.checks.artifacts.missingFiles).toBe(1);
  });
});
