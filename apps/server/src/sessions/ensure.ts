/**
 * Ensures minimal host and session rows exist in SQLite.
 *
 * Codex sessions are managed externally by the Codex CLI.  The adapter
 * queries them from disk on demand, so they may not have a corresponding
 * row in our SQLite store.  Other tables (`artifacts`, `runs`) hold
 * foreign keys to `sessions(id)`, and `sessions` holds a FK to
 * `hosts(id)`, so we need to guarantee both rows exist before any
 * INSERT that references them.
 *
 * The INSERT OR IGNORE approach is idempotent — if the row already
 * exists it's a no-op, keeping the operation cheap on the hot path.
 */

import { getDb } from "../db.js";

/**
 * Insert a minimal host row if one doesn't already exist.
 * Called internally by {@link ensureSessionRow}.
 */
export function ensureHostRow(hostId: string): void {
  const db = getDb();
  db.prepare(
    `INSERT OR IGNORE INTO hosts (id, label, kind, status)
     VALUES (?, ?, 'local', 'online')`,
  ).run(hostId, hostId);
}

/**
 * Insert a minimal sessions row if one doesn't already exist.
 *
 * This satisfies the FK constraint for tables that reference
 * `sessions(id)` without requiring the full session-discovery
 * flow from the Codex adapter.  Also ensures the parent host
 * row exists.
 */
export function ensureSessionRow(hostId: string, sessionId: string): void {
  ensureHostRow(hostId);
  const db = getDb();
  db.prepare(
    `INSERT OR IGNORE INTO sessions (id, host_id, provider, title)
     VALUES (?, ?, 'codex', ?)`,
  ).run(sessionId, hostId, sessionId);
}
