/**
 * SQLite-backed artifact metadata store and disk-storage helpers.
 *
 * Artifact metadata is persisted in the `artifacts` table defined by the
 * initial migration (001_initial_schema.sql).  Metadata now survives server
 * restarts alongside the files themselves, which have always been on disk.
 *
 * Foreign-key dependencies:
 *   - `session_id` → `sessions(id)`: satisfied via {@link ensureSessionRow}
 *     which upserts a minimal sessions row before the artifact INSERT.
 *   - `run_id` → `runs(id)`: satisfied because RunManager now writes
 *     through to the `runs` table.  NULL is used when no run is active.
 *
 * Storage layout:
 *
 *   data/artifacts/<hostId>/<sessionId>/<artifactId>-<originalName>
 *
 * This module never imports Fastify so it can be unit-tested in isolation.
 */

import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import type { Artifact } from "@codexremote/shared";
import { getDb } from "../db.js";
import { ensureSessionRow } from "../sessions/ensure.js";
import { MIN_FREE_DISK_BYTES } from "../config.js";

// ── Configuration ──────────────────────────────────────────────────

/** Root directory for all artifact files, relative to the server cwd. */
const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"] ?? "data";
const ARTIFACTS_DIR = path.join(DATA_ROOT, "artifacts");

/** Maximum upload size in bytes (50 MB per §11 security model). */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

// ── MIME type policy (§11 security model) ─────────────────────────

/**
 * Thrown when an upload's MIME type does not match the allowed policy.
 * The upload route translates this into a 415 Unsupported Media Type.
 */
export class DisallowedFileTypeError extends Error {
  /** The rejected MIME type. */
  readonly mimeType: string;

  constructor(mimeType: string) {
    super(`File type '${mimeType}' is not allowed by the upload policy`);
    this.name = "DisallowedFileTypeError";
    this.mimeType = mimeType;
  }
}

/**
 * Check whether a MIME type matches any of the allowed patterns.
 *
 * Patterns may be exact (`application/pdf`) or wildcard-prefixed
 * (`image/*`).  Matching is case-insensitive on the major type.
 */
export function isAllowedMimeType(
  mime: string,
  patterns: readonly string[],
): boolean {
  const lower = mime.toLowerCase().trim();
  for (const p of patterns) {
    const pattern = p.toLowerCase().trim();
    if (pattern.endsWith("/*")) {
      const prefix = pattern.slice(0, -1); // "image/"
      if (lower.startsWith(prefix)) return true;
    } else {
      if (lower === pattern) return true;
    }
  }
  return false;
}

// ── Helpers ────────────────────────────────────────────────────────

/** Determine artifact kind from MIME type. */
export function inferKind(mimeType: string): "image" | "file" {
  return mimeType.startsWith("image/") ? "image" : "file";
}

/** Sanitise a filename so it is safe for the filesystem. */
function sanitiseName(raw: string): string {
  return raw.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 200);
}

/** Build the on-disk directory for a session's artifacts. */
function sessionDir(hostId: string, sessionId: string): string {
  return path.join(ARTIFACTS_DIR, hostId, sessionId);
}

// ── Disk space safety ─────────────────────────────────────────────

/**
 * Thrown when available disk space is below the configured minimum.
 * The upload route translates this into a 507 Insufficient Storage.
 */
export class InsufficientDiskError extends Error {
  /** Bytes available on the filesystem. */
  readonly availableBytes: number;
  /** Minimum required bytes (from config). */
  readonly requiredBytes: number;

  constructor(availableBytes: number, requiredBytes: number) {
    super(
      `Insufficient disk space: ${availableBytes} bytes available, ` +
        `${requiredBytes} bytes required (minFreeDiskBytes)`,
    );
    this.name = "InsufficientDiskError";
    this.availableBytes = availableBytes;
    this.requiredBytes = requiredBytes;
  }
}

/** Filesystem space information returned by {@link getDiskSpaceInfo}. */
export interface DiskSpaceInfo {
  /** Total filesystem size in bytes. */
  totalBytes: number;
  /** Available bytes (non-privileged). */
  freeBytes: number;
  /** Whether free space is below the configured minimum. */
  low: boolean;
}

/**
 * Query available disk space on the filesystem that hosts the artifacts
 * directory.  Uses `fs.statfs()` on the data root.
 *
 * Returns `null` if the filesystem stats cannot be read (e.g. the
 * directory does not exist yet).
 */
export async function getDiskSpaceInfo(): Promise<DiskSpaceInfo | null> {
  try {
    // Ensure the data root exists so statfs has a target.
    await fs.mkdir(DATA_ROOT, { recursive: true });
    const stats = await fs.statfs(DATA_ROOT);
    const freeBytes = stats.bavail * stats.bsize;
    const totalBytes = stats.blocks * stats.bsize;
    return {
      totalBytes,
      freeBytes,
      low: freeBytes < MIN_FREE_DISK_BYTES,
    };
  } catch {
    return null;
  }
}

/**
 * Pre-flight check: reject the write if the filesystem doesn't have
 * enough room for the incoming payload plus the configured safety
 * margin.  Throws {@link InsufficientDiskError} on failure.
 */
async function assertDiskSpace(payloadBytes: number): Promise<void> {
  const needed = payloadBytes + MIN_FREE_DISK_BYTES;
  const info = await getDiskSpaceInfo();
  if (!info) return; // Can't determine — allow the write and let I/O errors surface naturally.
  if (info.freeBytes < needed) {
    throw new InsufficientDiskError(info.freeBytes, needed);
  }
}

/** Map a SQLite row to the shared Artifact type. */
function rowToArtifact(row: ArtifactRow): Artifact {
  return {
    id: row.id,
    sessionId: row.session_id,
    runId: row.run_id,
    kind: row.kind as "image" | "file",
    originalName: row.original_name,
    storedPath: row.stored_path,
    mimeType: row.mime_type,
    sizeBytes: row.size_bytes,
    createdAt: row.created_at,
  };
}

interface ArtifactRow {
  id: string;
  session_id: string;
  run_id: string | null;
  kind: string;
  original_name: string;
  stored_path: string;
  mime_type: string;
  size_bytes: number;
  created_at: string;
}

// ── Public API ─────────────────────────────────────────────────────

export interface CreateArtifactOpts {
  hostId: string;
  sessionId: string;
  runId?: string;
  originalName: string;
  mimeType: string;
  buffer: Buffer;
}

/**
 * Persist a file to disk and record its metadata in SQLite.
 *
 * Disk write and DB insert are not natively atomic.  To prevent
 * orphaned files (written to disk but not recorded in SQLite) the
 * function rolls back the disk write when the DB insert throws.
 * Conversely, a disk-write failure is propagated without touching
 * the database at all.
 */
export async function createArtifact(
  opts: CreateArtifactOpts,
): Promise<Artifact> {
  const id = crypto.randomUUID();
  const safeName = sanitiseName(opts.originalName);
  const dir = sessionDir(opts.hostId, opts.sessionId);
  const fileName = `${id}-${safeName}`;
  const storedPath = path.join(dir, fileName);

  // ── Step 0: pre-flight disk space check ───────────────────────
  // Reject early with a clear error before touching disk or DB.
  await assertDiskSpace(opts.buffer.length);

  // ── Step 1: write file to disk ────────────────────────────────
  try {
    await fs.mkdir(dir, { recursive: true });
  } catch (err) {
    throw new Error(
      `Failed to create artifact directory ${dir}: ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  try {
    await fs.writeFile(storedPath, opts.buffer);
  } catch (err) {
    throw new Error(
      `Failed to write artifact file ${storedPath}: ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  // ── Step 2: record metadata in SQLite ─────────────────────────
  // The entire DB phase (FK setup + artifact INSERT) is wrapped in a
  // single try/catch so that *any* DB-side failure after the file has
  // been written results in disk rollback — not just the final INSERT.
  const createdAt = new Date().toISOString();
  const kind = inferKind(opts.mimeType);
  const runId = opts.runId ?? null;

  try {
    ensureSessionRow(opts.hostId, opts.sessionId);

    const db = getDb();
    db.prepare(
      `INSERT INTO artifacts (id, session_id, run_id, kind, original_name, stored_path, mime_type, size_bytes, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(id, opts.sessionId, runId, kind, opts.originalName, storedPath, opts.mimeType, opts.buffer.length, createdAt);
  } catch (err) {
    // Roll back the disk write so we don't leave an orphaned file.
    await fs.unlink(storedPath).catch(() => {});
    throw new Error(
      `Failed to record artifact in database (disk file rolled back): ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  return {
    id,
    sessionId: opts.sessionId,
    runId,
    kind,
    originalName: opts.originalName,
    storedPath,
    mimeType: opts.mimeType,
    sizeBytes: opts.buffer.length,
    createdAt,
  };
}

/** Retrieve metadata for a single artifact. */
export function getArtifact(id: string): Artifact | null {
  const db = getDb();
  const row = db
    .prepare(
      `SELECT id, session_id, run_id, kind, original_name, stored_path, mime_type, size_bytes, created_at
       FROM artifacts WHERE id = ?`,
    )
    .get(id) as ArtifactRow | undefined;

  return row ? rowToArtifact(row) : null;
}

/** List all artifacts belonging to a session. */
export function listArtifactsBySession(sessionId: string): Artifact[] {
  const db = getDb();
  const rows = db
    .prepare(
      `SELECT id, session_id, run_id, kind, original_name, stored_path, mime_type, size_bytes, created_at
       FROM artifacts WHERE session_id = ? ORDER BY created_at`,
    )
    .all(sessionId) as ArtifactRow[];

  return rows.map(rowToArtifact);
}

/**
 * Attach (or re-attach) an artifact to a session, optionally linking a run.
 * Returns the updated artifact or null if it doesn't exist.
 */
export function attachArtifact(
  artifactId: string,
  sessionId: string,
  runId?: string,
): Artifact | null {
  const db = getDb();

  // Ensure the target session row exists for FK integrity.
  ensureSessionRow("local", sessionId);

  const setClauses = ["session_id = ?"];
  const params: (string | null)[] = [sessionId];

  if (runId !== undefined) {
    setClauses.push("run_id = ?");
    params.push(runId);
  }

  params.push(artifactId);

  const result = db
    .prepare(
      `UPDATE artifacts SET ${setClauses.join(", ")} WHERE id = ?`,
    )
    .run(...params);

  if (result.changes === 0) return null;

  return getArtifact(artifactId);
}

// ── Consistency audit & repair ──────────────────────────────────────

export interface ArtifactConsistencyReport {
  /** Total artifact rows in the database. */
  totalDbRows: number;
  /** DB rows whose stored_path does not exist on disk. */
  missingFiles: string[];
  /** Files on disk under the artifacts root with no matching DB row. */
  orphanedFiles: string[];
}

export interface ArtifactRepairReport {
  /** Number of orphaned disk files successfully removed. */
  orphanedFilesRemoved: number;
  /** Artifact IDs whose stored_path no longer exists on disk. */
  missingFileArtifactIds: string[];
  /** Non-fatal errors encountered during cleanup. */
  errors: string[];
}

/**
 * Compare SQLite artifact metadata against the on-disk artifact tree.
 *
 * Returns a report identifying:
 *   - DB rows pointing to files that don't exist (data loss)
 *   - Disk files that have no DB row (space leak / prior crash)
 *
 * Designed to run at startup or periodically from the health endpoint.
 * Read-only — does not mutate the database or filesystem.
 */
export async function auditArtifactConsistency(): Promise<ArtifactConsistencyReport> {
  const db = getDb();

  // ── 1. Check DB rows against disk ──────────────────────────────
  const rows = db
    .prepare("SELECT id, stored_path FROM artifacts")
    .all() as Array<{ id: string; stored_path: string }>;

  const missingFiles: string[] = [];
  const dbPaths = new Set<string>();

  for (const row of rows) {
    dbPaths.add(path.resolve(row.stored_path));
    try {
      await fs.access(row.stored_path);
    } catch {
      missingFiles.push(row.id);
    }
  }

  // ── 2. Check disk files against DB ─────────────────────────────
  const orphanedFiles: string[] = [];

  try {
    await fs.access(ARTIFACTS_DIR);
  } catch {
    // Artifacts directory doesn't exist yet — nothing on disk to audit.
    return { totalDbRows: rows.length, missingFiles, orphanedFiles };
  }

  // Walk: artifacts/<hostId>/<sessionId>/<file>
  const hostDirs = await fs.readdir(ARTIFACTS_DIR).catch(() => [] as string[]);
  for (const host of hostDirs) {
    const hostPath = path.join(ARTIFACTS_DIR, host);
    const stat = await fs.stat(hostPath).catch(() => null);
    if (!stat?.isDirectory()) continue;

    const sessionDirs = await fs.readdir(hostPath).catch(() => [] as string[]);
    for (const session of sessionDirs) {
      const sessPath = path.join(hostPath, session);
      const sessStat = await fs.stat(sessPath).catch(() => null);
      if (!sessStat?.isDirectory()) continue;

      const files = await fs.readdir(sessPath).catch(() => [] as string[]);
      for (const file of files) {
        const filePath = path.resolve(sessPath, file);
        if (!dbPaths.has(filePath)) {
          orphanedFiles.push(path.join(sessPath, file));
        }
      }
    }
  }

  return { totalDbRows: rows.length, missingFiles, orphanedFiles };
}

/**
 * Run a consistency audit and repair what can be fixed automatically.
 *
 * - Orphaned disk files (file exists, no DB row) are **deleted**.
 * - Empty artifact directories left behind are pruned.
 * - DB rows pointing to missing files are **reported but not removed**
 *   so operators can investigate potential data loss.
 *
 * Designed to be called once at startup, mirroring the
 * `recoverOrphanedRuns()` pattern for the run lifecycle.
 */
export async function repairArtifactConsistency(): Promise<ArtifactRepairReport> {
  const audit = await auditArtifactConsistency();

  const report: ArtifactRepairReport = {
    orphanedFilesRemoved: 0,
    missingFileArtifactIds: audit.missingFiles,
    errors: [],
  };

  // Remove orphaned disk files that have no matching DB row.
  for (const filePath of audit.orphanedFiles) {
    try {
      await fs.unlink(filePath);
      report.orphanedFilesRemoved++;
    } catch (err) {
      report.errors.push(
        `Failed to remove orphaned file ${filePath}: ${err instanceof Error ? err.message : String(err)}`,
      );
    }
  }

  // Clean up empty session and host directories left behind.
  await pruneEmptyArtifactDirs();

  return report;
}

/**
 * Walk the artifact directory tree and remove empty leaf directories.
 * Session dirs are checked first, then host dirs, bottom-up so a host
 * dir becomes empty only after its children are removed.
 */
async function pruneEmptyArtifactDirs(): Promise<void> {
  try {
    await fs.access(ARTIFACTS_DIR);
  } catch {
    return; // No artifacts dir — nothing to prune.
  }

  const hostDirs = await fs.readdir(ARTIFACTS_DIR).catch(() => [] as string[]);
  for (const host of hostDirs) {
    const hostPath = path.join(ARTIFACTS_DIR, host);
    const hostStat = await fs.stat(hostPath).catch(() => null);
    if (!hostStat?.isDirectory()) continue;

    const sessionDirs = await fs.readdir(hostPath).catch(() => [] as string[]);
    for (const session of sessionDirs) {
      const sessPath = path.join(hostPath, session);
      const sessStat = await fs.stat(sessPath).catch(() => null);
      if (!sessStat?.isDirectory()) continue;

      const files = await fs.readdir(sessPath).catch(() => ["placeholder"]);
      if (files.length === 0) {
        await fs.rmdir(sessPath).catch(() => {});
      }
    }

    // Re-read after potential session dir removals.
    const remaining = await fs.readdir(hostPath).catch(() => ["placeholder"]);
    if (remaining.length === 0) {
      await fs.rmdir(hostPath).catch(() => {});
    }
  }
}
