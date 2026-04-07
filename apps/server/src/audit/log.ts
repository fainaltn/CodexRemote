/**
 * Structured audit log backed by SQLite.
 *
 * Records security-relevant events (logins, logouts, uploads) so
 * operators can investigate incidents and observe usage patterns
 * during normal remote use.
 *
 * All writes are best-effort: a failing audit INSERT must never
 * break the request that triggered it.  Failures are tracked in a
 * cumulative counter surfaced by the health endpoint so operators
 * can detect a degraded audit subsystem.
 */

import crypto from "node:crypto";
import { getDb } from "../db.js";

// ── Event types ────────────────────────────────────────────────────

export type AuditEventType =
  | "login_success"
  | "login_failure"
  | "logout"
  | "upload_success"
  | "upload_failure";

export interface AuditEntry {
  id: string;
  timestamp: string;
  eventType: AuditEventType;
  ip: string | null;
  tokenId: string | null;
  deviceLabel: string | null;
  sessionId: string | null;
  artifactId: string | null;
  detail: string | null;
}

// ── Cumulative error counters ──────────────────────────────────────

let _writeErrors = 0;
let _readErrors = 0;

/** Cumulative audit write failures since process start. */
export function getAuditWriteErrorCount(): number {
  return _writeErrors;
}

/** Cumulative audit read failures since process start. */
export function getAuditReadErrorCount(): number {
  return _readErrors;
}

/** Reset counters — intended only for test isolation. */
export function resetAuditErrorCounts(): void {
  _writeErrors = 0;
  _readErrors = 0;
}

// ── Write ──────────────────────────────────────────────────────────

export interface WriteAuditOpts {
  eventType: AuditEventType;
  ip?: string | null;
  tokenId?: string | null;
  deviceLabel?: string | null;
  sessionId?: string | null;
  artifactId?: string | null;
  detail?: string | null;
}

/**
 * Record an audit event.  Best-effort — never throws.
 *
 * Returns the generated row ID on success, or null if the write
 * could not be persisted.  Failures increment a cumulative counter
 * exposed by {@link getAuditWriteErrorCount} so the health endpoint
 * can signal degraded audit persistence.
 */
export function writeAuditLog(opts: WriteAuditOpts): string | null {
  try {
    const db = getDb();
    const id = crypto.randomUUID();
    db.prepare(
      `INSERT INTO audit_log
         (id, event_type, ip, token_id, device_label, session_id, artifact_id, detail)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      opts.eventType,
      opts.ip ?? null,
      opts.tokenId ?? null,
      opts.deviceLabel ?? null,
      opts.sessionId ?? null,
      opts.artifactId ?? null,
      opts.detail ?? null,
    );
    return id;
  } catch {
    _writeErrors++;
    return null;
  }
}

// ── Read (for health endpoint) ─────────────────────────────────────

export interface AuditCountRow {
  eventType: string;
  count: number;
}

export interface AuditCountResult {
  ok: true;
  counts: AuditCountRow[];
}

export interface AuditCountError {
  ok: false;
  error: string;
}

/**
 * Count audit events grouped by type within the given time window.
 * Defaults to the last 24 hours.  Used by the health endpoint.
 *
 * Returns an explicit success/error discriminator so the health
 * endpoint can distinguish "zero events" from "audit read failure."
 * Read failures also increment a cumulative counter.
 */
export function countRecentAuditEvents(
  sinceMs: number = 24 * 60 * 60 * 1000,
): AuditCountResult | AuditCountError {
  try {
    const db = getDb();
    const since = new Date(Date.now() - sinceMs).toISOString();
    const rows = db
      .prepare(
        `SELECT event_type, COUNT(*) AS cnt
         FROM audit_log
         WHERE timestamp >= ?
         GROUP BY event_type
         ORDER BY event_type`,
      )
      .all(since) as Array<{ event_type: string; cnt: number }>;

    return {
      ok: true,
      counts: rows.map((r) => ({
        eventType: r.event_type,
        count: r.cnt,
      })),
    };
  } catch (err) {
    _readErrors++;
    return {
      ok: false,
      error: err instanceof Error ? err.message : String(err),
    };
  }
}
