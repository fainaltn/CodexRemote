/**
 * Startup artifact consistency repair tests.
 *
 * Validates:
 *  - repairArtifactConsistency removes orphaned disk files
 *  - repairArtifactConsistency reports missing-file DB rows without deleting them
 *  - repairArtifactConsistency is a no-op when everything is consistent
 *  - repairArtifactConsistency prunes empty directories after orphan removal
 *  - repairArtifactConsistency handles unlink errors gracefully
 *  - buildApp runs artifact repair at startup and logs results
 */

import { describe, it, expect, beforeEach, vi } from "vitest";
import fs from "node:fs/promises";
import path from "node:path";
import {
  createArtifact,
  repairArtifactConsistency,
} from "../artifacts/store.js";
import { getDb, initDb } from "../db.js";
import { buildApp } from "../app.js";
import { cleanTables, MockCodexAdapter } from "./helpers.js";

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

/** Check whether a path exists on disk. */
async function pathExists(p: string): Promise<boolean> {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

describe("repairArtifactConsistency", () => {
  beforeEach(async () => {
    cleanTables();
    await cleanArtifactsDisk();
  });

  // ── Orphaned file removal ──────────────────────────────────────────

  it("removes orphaned disk files that have no DB row", async () => {
    initDb();

    // Create a real artifact so the directory structure exists.
    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-repair",
      originalName: "legit.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("legit content"),
    });

    // Drop an orphan file into the same session directory.
    const orphanDir = path.join(ARTIFACTS_DIR, "local", "sess-repair");
    const orphanPath = path.join(orphanDir, "orphan-crash-leftover.bin");
    await fs.writeFile(orphanPath, Buffer.from("orphaned after crash"));

    // Verify orphan exists before repair.
    expect(await pathExists(orphanPath)).toBe(true);

    const report = await repairArtifactConsistency();

    // Orphaned file should be removed.
    expect(report.orphanedFilesRemoved).toBe(1);
    expect(await pathExists(orphanPath)).toBe(false);

    // Legitimate artifact should still exist.
    expect(await pathExists(artifact.storedPath)).toBe(true);

    // No missing-file rows or errors.
    expect(report.missingFileArtifactIds).toHaveLength(0);
    expect(report.errors).toHaveLength(0);
  });

  // ── Missing-file DB rows are reported but not deleted ──────────────

  it("reports missing-file DB rows without removing them", async () => {
    initDb();

    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-missing",
      originalName: "will-delete.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("will be deleted"),
    });

    // Delete the file from disk, leaving the DB row.
    await fs.unlink(artifact.storedPath);

    const report = await repairArtifactConsistency();

    // The missing file should be reported.
    expect(report.missingFileArtifactIds).toContain(artifact.id);
    expect(report.orphanedFilesRemoved).toBe(0);
    expect(report.errors).toHaveLength(0);

    // The DB row should still exist (not deleted).
    const db = getDb();
    const row = db
      .prepare("SELECT id FROM artifacts WHERE id = ?")
      .get(artifact.id) as { id: string } | undefined;
    expect(row).toBeDefined();
    expect(row!.id).toBe(artifact.id);
  });

  // ── Clean state produces no actions ────────────────────────────────

  it("reports clean when DB and disk are consistent", async () => {
    initDb();

    // Create a consistent artifact.
    await createArtifact({
      hostId: "local",
      sessionId: "sess-clean",
      originalName: "fine.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("all good"),
    });

    const report = await repairArtifactConsistency();

    expect(report.orphanedFilesRemoved).toBe(0);
    expect(report.missingFileArtifactIds).toHaveLength(0);
    expect(report.errors).toHaveLength(0);
  });

  it("is a no-op on an empty store", async () => {
    initDb();

    const report = await repairArtifactConsistency();

    expect(report.orphanedFilesRemoved).toBe(0);
    expect(report.missingFileArtifactIds).toHaveLength(0);
    expect(report.errors).toHaveLength(0);
  });

  // ── Empty directory pruning ────────────────────────────────────────

  it("prunes empty directories after removing all orphaned files", async () => {
    initDb();

    // Create a directory structure with only orphan files (no DB rows).
    const sessionDir = path.join(ARTIFACTS_DIR, "local", "sess-empty");
    await fs.mkdir(sessionDir, { recursive: true });
    await fs.writeFile(
      path.join(sessionDir, "orphan1.bin"),
      Buffer.from("orphan"),
    );

    expect(await pathExists(sessionDir)).toBe(true);

    const report = await repairArtifactConsistency();

    expect(report.orphanedFilesRemoved).toBe(1);

    // The session directory should be pruned (it's now empty).
    expect(await pathExists(sessionDir)).toBe(false);

    // The host directory should also be pruned if empty.
    const hostDir = path.join(ARTIFACTS_DIR, "local");
    expect(await pathExists(hostDir)).toBe(false);
  });

  it("does not prune directories that still contain files", async () => {
    initDb();

    // Create a real artifact + an orphan in the same session dir.
    await createArtifact({
      hostId: "local",
      sessionId: "sess-partial",
      originalName: "keeper.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("keep me"),
    });

    const sessionDir = path.join(ARTIFACTS_DIR, "local", "sess-partial");
    await fs.writeFile(
      path.join(sessionDir, "orphan.bin"),
      Buffer.from("remove me"),
    );

    const report = await repairArtifactConsistency();

    expect(report.orphanedFilesRemoved).toBe(1);

    // The session directory should still exist (has the legit file).
    expect(await pathExists(sessionDir)).toBe(true);
    const remaining = await safeReaddir(sessionDir);
    expect(remaining).toHaveLength(1);
    expect(remaining[0]).toContain("keeper.txt");
  });

  // ── Unlink error handling ──────────────────────────────────────────

  it("records errors when orphan deletion fails", async () => {
    initDb();

    // Create a directory with an orphan file.
    const sessionDir = path.join(ARTIFACTS_DIR, "local", "sess-err");
    await fs.mkdir(sessionDir, { recursive: true });
    const orphanPath = path.join(sessionDir, "stubborn.bin");
    await fs.writeFile(orphanPath, Buffer.from("cannot delete me"));

    // Make fs.unlink fail for the orphan file.
    const unlinkSpy = vi.spyOn(fs, "unlink").mockRejectedValueOnce(
      new Error("EACCES: permission denied"),
    );

    const report = await repairArtifactConsistency();

    expect(report.orphanedFilesRemoved).toBe(0);
    expect(report.errors).toHaveLength(1);
    expect(report.errors[0]).toContain("EACCES");
    expect(report.errors[0]).toContain("stubborn.bin");

    unlinkSpy.mockRestore();

    // Clean up the file manually.
    await fs.unlink(orphanPath).catch(() => {});
  });
});

// ── buildApp startup integration ──────────────────────────────────────

describe("buildApp artifact repair integration", () => {
  beforeEach(async () => {
    cleanTables();
    await cleanArtifactsDisk();
  });

  it("removes orphaned files during startup when repair is enabled", async () => {
    initDb();

    // Create a consistent artifact.
    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-startup",
      originalName: "good.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("good"),
    });

    // Create an orphan file.
    const sessionDir = path.join(ARTIFACTS_DIR, "local", "sess-startup");
    const orphanPath = path.join(sessionDir, "crash-leftover.bin");
    await fs.writeFile(orphanPath, Buffer.from("leftover"));

    expect(await pathExists(orphanPath)).toBe(true);

    // Build app with artifact repair enabled (default).
    const adapter = new MockCodexAdapter();
    const { app } = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
      // skipArtifactRepair is NOT set → repair runs.
    });

    // Orphan should have been cleaned up at startup.
    expect(await pathExists(orphanPath)).toBe(false);

    // Legit artifact untouched.
    expect(await pathExists(artifact.storedPath)).toBe(true);

    await app.close();
  });

  it("skips repair when skipArtifactRepair is set", async () => {
    initDb();

    // Create an orphan file.
    const sessionDir = path.join(ARTIFACTS_DIR, "local", "sess-skip");
    await fs.mkdir(sessionDir, { recursive: true });
    const orphanPath = path.join(sessionDir, "should-remain.bin");
    await fs.writeFile(orphanPath, Buffer.from("not cleaned"));

    const adapter = new MockCodexAdapter();
    const { app } = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
      skipArtifactRepair: true,
    });

    // Orphan should still exist because repair was skipped.
    expect(await pathExists(orphanPath)).toBe(true);

    await app.close();

    // Clean up manually.
    await fs.unlink(orphanPath).catch(() => {});
  });

  it("startup repair logs clean state without errors", async () => {
    initDb();

    // No artifacts at all — cleanest possible state.
    const adapter = new MockCodexAdapter();
    const { app } = await buildApp({
      logger: false,
      adapter,
      skipRecovery: true,
      // Repair enabled — should be a no-op and not throw.
    });

    // Health endpoint should report clean state.
    const res = await app.inject({ method: "GET", url: "/api/health" });
    const body = JSON.parse(res.body);
    expect(body.status).toBe("ok");
    expect(body.checks.artifacts.orphanedFiles).toBe(0);
    expect(body.checks.artifacts.missingFiles).toBe(0);

    await app.close();
  });
});
