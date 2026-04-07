import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import type { InboxItem } from "@codexremote/shared";
import { getDb } from "../db.js";
import { ensureHostRow } from "../sessions/ensure.js";
import {
  getDiskSpaceInfo,
  InsufficientDiskError,
} from "../artifacts/store.js";
import { MIN_FREE_DISK_BYTES } from "../config.js";
import {
  inferAttachmentKind,
  importSubmissionBundle,
  readImportedSubmissionManifest,
  readSubmissionSummary,
  resolveStagingDir,
  writeSubmissionBundle,
} from "./submission.js";

const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"] ?? "data";
const STAGING_ROOT = process.env["CODEXREMOTE_STAGING_DIR"] ?? path.join(DATA_ROOT, "submissions");

function hostStagingDir(hostId: string): string {
  return path.join(STAGING_ROOT, hostId);
}

function submissionStagingDir(hostId: string, submissionId: string): string {
  return path.join(hostStagingDir(hostId), submissionId);
}

function sanitiseName(raw: string): string {
  return raw.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 200);
}

function ensureUniqueName(
  used: Set<string>,
  rawName: string,
): string {
  const input = sanitiseName(rawName) || "attachment";
  const ext = path.extname(input);
  const base = ext ? input.slice(0, -ext.length) : input;
  let candidate = input;
  let index = 1;
  while (used.has(candidate)) {
    candidate = `${base}-${index}${ext}`;
    index += 1;
  }
  used.add(candidate);
  return candidate;
}

interface InboxItemRow {
  id: string;
  host_id: string;
  kind: "link" | "file";
  status: "received";
  url: string | null;
  title: string | null;
  original_name: string | null;
  note: string | null;
  source: string | null;
  stored_path: string | null;
  mime_type: string | null;
  size_bytes: number | null;
  submission_id: string | null;
  staging_dir: string | null;
  created_at: string;
}

function rowToInboxItem(row: InboxItemRow): InboxItem {
  const summary = readSubmissionSummary(row.stored_path);
  const submissionId = row.submission_id ?? summary.submissionId;
  const stagingDir = row.staging_dir ?? summary.stagingDir ?? resolveStagingDir(row.stored_path);
  return {
    id: row.id,
    hostId: row.host_id,
    kind: row.kind,
    status: row.status,
    url: row.url,
    title: row.title,
    originalName: row.original_name,
    note: row.note,
    source: row.source,
    storedPath: row.stored_path,
    mimeType: row.mime_type,
    sizeBytes: row.size_bytes,
    createdAt: row.created_at,
    submissionPath: summary.submissionPath,
    submissionId,
    stagingDir,
    contract: summary.contract,
    captureSessions: summary.captureSessions,
    retryAttempts: summary.retryAttempts,
    retryPolicy: summary.retryPolicy,
    hasReviewBundle: summary.hasReviewBundle,
    hasSkillRunbook: summary.hasSkillRunbook,
  };
}

async function assertDiskSpace(payloadBytes: number): Promise<void> {
  const needed = payloadBytes + MIN_FREE_DISK_BYTES;
  const info = await getDiskSpaceInfo();
  if (!info) return;
  if (info.freeBytes < needed) {
    throw new InsufficientDiskError(info.freeBytes, needed);
  }
}

export async function createInboxLinkItem(opts: {
  hostId: string;
  url: string;
  title?: string;
  note?: string;
  source?: string;
}): Promise<InboxItem> {
  const id = crypto.randomUUID();
  const submissionId = id;
  const createdAt = new Date().toISOString();
  const itemDir = submissionStagingDir(opts.hostId, submissionId);
  const submissionPath = await writeSubmissionBundle({
    itemDir,
    submissionId,
    submittedAt: createdAt,
    client: {
      name: "CodexRemote",
      platform: opts.source ?? "unknown",
    },
    attachments: [],
    captureText: opts.note ?? "",
    payload: {
      title: opts.title ?? opts.url,
      kind: "link",
      sourceLocator: opts.url,
      itemId: id,
    },
    captureSessions: [{ session_id: id, file_count: 0 }],
    retryAttempts: [{ attempt: 1, status: "completed" }],
  });

  try {
    ensureHostRow(opts.hostId);
    const db = getDb();
    db.prepare(
      `INSERT INTO inbox_items
        (id, host_id, kind, status, url, title, note, source, stored_path, submission_id, staging_dir, created_at)
       VALUES (?, ?, 'link', 'received', ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      opts.hostId,
      opts.url,
      opts.title ?? null,
      opts.note ?? null,
      opts.source ?? null,
      submissionPath,
      submissionId,
      itemDir,
      createdAt,
    );
  } catch (err) {
    await fs.rm(itemDir, { recursive: true, force: true }).catch(() => {});
    throw new Error(
      `Failed to record inbox link in database (bundle rolled back): ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  return {
    id,
    hostId: opts.hostId,
    kind: "link",
    status: "received",
    url: opts.url,
    title: opts.title ?? null,
    originalName: null,
    note: opts.note ?? null,
    source: opts.source ?? null,
    storedPath: submissionPath,
    mimeType: null,
    sizeBytes: null,
    createdAt,
    submissionPath,
    submissionId,
    stagingDir: itemDir,
    contract: "codexremote_v1",
    captureSessions: [{ session_id: id, file_count: 0 }],
    retryAttempts: [{ attempt: 1, status: "completed" }],
    retryPolicy: {
      attempt_count: 1,
      final_status: "completed",
      last_error: "",
      recommended_action: "continue_review",
    },
    hasReviewBundle: false,
    hasSkillRunbook: false,
  };
}

export async function createInboxFileItem(opts: {
  hostId: string;
  originalName: string;
  mimeType: string;
  buffer: Buffer;
  note?: string;
  source?: string;
}): Promise<InboxItem> {
  await assertDiskSpace(opts.buffer.length);

  const id = crypto.randomUUID();
  const submissionId = id;
  const safeName = sanitiseName(opts.originalName);
  const itemDir = submissionStagingDir(opts.hostId, submissionId);
  const attachmentsDir = path.join(itemDir, "attachments");
  const storedPath = path.join(attachmentsDir, safeName);
  const createdAt = new Date().toISOString();

  await fs.mkdir(attachmentsDir, { recursive: true });
  await fs.writeFile(storedPath, opts.buffer);
  const submissionPath = await writeSubmissionBundle({
    itemDir,
    submissionId,
    submittedAt: createdAt,
    client: {
      name: "CodexRemote",
      platform: opts.source ?? "unknown",
    },
    attachments: [
      {
        relativePath: path.posix.join("attachments", safeName),
        kind: inferAttachmentKind({
          mimeType: opts.mimeType,
          fileName: opts.originalName,
        }),
      },
    ],
    captureText: opts.note ?? "",
    payload: {
      title: opts.originalName,
      kind: "file",
      sourceLocator: `codexremote://submission/${id}`,
      itemId: id,
    },
    captureSessions: [{ session_id: id, file_count: 1 }],
    retryAttempts: [{ attempt: 1, status: "completed" }],
  });

  try {
    ensureHostRow(opts.hostId);
    const db = getDb();
    db.prepare(
      `INSERT INTO inbox_items
        (id, host_id, kind, status, original_name, note, source, stored_path, mime_type, size_bytes, submission_id, staging_dir, created_at)
       VALUES (?, ?, 'file', 'received', ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      opts.hostId,
      opts.originalName,
      opts.note ?? null,
      opts.source ?? null,
      submissionPath,
      opts.mimeType,
      opts.buffer.length,
      submissionId,
      itemDir,
      createdAt,
    );
  } catch (err) {
    await fs.rm(itemDir, { recursive: true, force: true }).catch(() => {});
    throw new Error(
      `Failed to record inbox item in database (disk file rolled back): ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  return {
    id,
    hostId: opts.hostId,
    kind: "file",
    status: "received",
    url: null,
    title: null,
    originalName: opts.originalName,
    note: opts.note ?? null,
    source: opts.source ?? null,
    storedPath: submissionPath,
    mimeType: opts.mimeType,
    sizeBytes: opts.buffer.length,
    createdAt,
    submissionPath,
    submissionId,
    stagingDir: itemDir,
    contract: "codexremote_v1",
    captureSessions: [{ session_id: id, file_count: 1 }],
    retryAttempts: [{ attempt: 1, status: "completed" }],
    retryPolicy: {
      attempt_count: 1,
      final_status: "completed",
      last_error: "",
      recommended_action: "continue_review",
    },
    hasReviewBundle: false,
    hasSkillRunbook: false,
  };
}

export async function createInboxFilesItem(opts: {
  hostId: string;
  files: Array<{
    originalName: string;
    mimeType: string;
    buffer: Buffer;
  }>;
  note?: string;
  source?: string;
}): Promise<InboxItem> {
  const totalBytes = opts.files.reduce((sum, file) => sum + file.buffer.length, 0);
  await assertDiskSpace(totalBytes);
  if (opts.files.length === 0) {
    throw new Error("No files provided");
  }

  const id = crypto.randomUUID();
  const submissionId = id;
  const createdAt = new Date().toISOString();
  const itemDir = submissionStagingDir(opts.hostId, submissionId);
  const attachmentsDir = path.join(itemDir, "attachments");
  const usedNames = new Set<string>();
  const attachments: Array<{ relativePath: string; kind: string }> = [];

  await fs.mkdir(attachmentsDir, { recursive: true });
  for (const file of opts.files) {
    const safeName = ensureUniqueName(usedNames, file.originalName);
    const storedPath = path.join(attachmentsDir, safeName);
    await fs.writeFile(storedPath, file.buffer);
    attachments.push({
      relativePath: path.posix.join("attachments", safeName),
      kind: inferAttachmentKind({
        mimeType: file.mimeType,
        fileName: file.originalName,
      }),
    });
  }

  const displayName = opts.files.length === 1
    ? opts.files[0]?.originalName ?? "1 file"
    : `${opts.files.length} files`;
  const submissionPath = await writeSubmissionBundle({
    itemDir,
    submissionId,
    submittedAt: createdAt,
    client: {
      name: "CodexRemote",
      platform: opts.source ?? "unknown",
    },
    attachments,
    captureText: opts.note ?? "",
    payload: {
      title: displayName,
      kind: "file",
      sourceLocator: `codexremote://submission/${id}`,
      itemId: id,
    },
    captureSessions: [{ session_id: id, file_count: opts.files.length }],
    retryAttempts: [{ attempt: 1, status: "completed" }],
  });

  try {
    ensureHostRow(opts.hostId);
    const db = getDb();
    db.prepare(
      `INSERT INTO inbox_items
        (id, host_id, kind, status, original_name, note, source, stored_path, mime_type, size_bytes, submission_id, staging_dir, created_at)
       VALUES (?, ?, 'file', 'received', ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      opts.hostId,
      displayName,
      opts.note ?? null,
      opts.source ?? null,
      submissionPath,
      opts.files.length === 1 ? opts.files[0]?.mimeType ?? null : null,
      totalBytes,
      submissionId,
      itemDir,
      createdAt,
    );
  } catch (err) {
    await fs.rm(itemDir, { recursive: true, force: true }).catch(() => {});
    throw new Error(
      `Failed to record inbox files in database (bundle rolled back): ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  return {
    id,
    hostId: opts.hostId,
    kind: "file",
    status: "received",
    url: null,
    title: null,
    originalName: displayName,
    note: opts.note ?? null,
    source: opts.source ?? null,
    storedPath: submissionPath,
    mimeType: opts.files.length === 1 ? opts.files[0]?.mimeType ?? null : null,
    sizeBytes: totalBytes,
    createdAt,
    submissionPath,
    submissionId,
    stagingDir: itemDir,
    contract: "codexremote_v1",
    captureSessions: [{ session_id: id, file_count: opts.files.length }],
    retryAttempts: [{ attempt: 1, status: "completed" }],
    retryPolicy: {
      attempt_count: 1,
      final_status: "completed",
      last_error: "",
      recommended_action: "continue_review",
    },
    hasReviewBundle: false,
    hasSkillRunbook: false,
  };
}

export async function createInboxSubmissionItem(opts: {
  hostId: string;
  files: Array<{ relativePath: string; buffer: Buffer }>;
  source?: string;
}): Promise<InboxItem> {
  const id = crypto.randomUUID();
  const imported = readImportedSubmissionManifest(opts.files);
  const submissionId = imported.submissionId;
  const itemDir = submissionStagingDir(opts.hostId, submissionId);
  try {
    await fs.access(itemDir);
    throw new Error(`Submission staging directory already exists for submission_id '${submissionId}'`);
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== "ENOENT") {
      throw err;
    }
  }
  const submissionPath = await importSubmissionBundle({
    itemDir,
    files: imported.normalizedFiles,
  });
  const payload = imported.manifest["payload"] && typeof imported.manifest["payload"] === "object"
    ? imported.manifest["payload"] as Record<string, unknown>
    : {};
  const createdAt = typeof imported.manifest["submitted_at"] === "string"
    ? imported.manifest["submitted_at"]
    : new Date().toISOString();

  try {
    ensureHostRow(opts.hostId);
    const db = getDb();
    db.prepare(
      `INSERT INTO inbox_items
        (id, host_id, kind, status, url, title, note, source, stored_path, submission_id, staging_dir, created_at)
       VALUES (?, ?, 'file', 'received', ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      opts.hostId,
      typeof payload["source_locator"] === "string" ? payload["source_locator"] : null,
      typeof payload["title"] === "string" ? payload["title"] : null,
      typeof imported.manifest["capture_text"] === "string" ? imported.manifest["capture_text"] : null,
      opts.source ?? "bundle",
      submissionPath,
      submissionId,
      itemDir,
      createdAt,
    );
  } catch (err) {
    await fs.rm(itemDir, { recursive: true, force: true }).catch(() => {});
    throw new Error(
      `Failed to record inbox submission in database (bundle rolled back): ${err instanceof Error ? err.message : String(err)}`,
    );
  }

  return rowToInboxItem({
    id,
    host_id: opts.hostId,
    kind: "file",
    status: "received",
    url: typeof payload["source_locator"] === "string" ? payload["source_locator"] : null,
    title: typeof payload["title"] === "string" ? payload["title"] : null,
    original_name: "submission.json",
    note: typeof imported.manifest["capture_text"] === "string" ? imported.manifest["capture_text"] : null,
    source: opts.source ?? "bundle",
    stored_path: submissionPath,
    mime_type: "application/json",
    size_bytes: null,
    submission_id: submissionId,
    staging_dir: itemDir,
    created_at: createdAt,
  });
}

export function listInboxItems(hostId: string, limit = 20): InboxItem[] {
  const db = getDb();
  const rows = db.prepare(
    `SELECT id, host_id, kind, status, url, title, original_name, note, source, stored_path, mime_type, size_bytes, submission_id, staging_dir, created_at
     FROM inbox_items
     WHERE host_id = ?
     ORDER BY created_at DESC
     LIMIT ?`,
  ).all(hostId, limit) as InboxItemRow[];
  return rows.map(rowToInboxItem);
}
