/**
 * SQLite-backed auth token store.
 *
 * Persists access sessions in the `access_sessions` table defined by the
 * initial migration (001_initial_schema.sql).  Tokens now survive server
 * restarts, matching the Phase 1 durability requirements from §6.2.
 *
 * CODEXREMOTE_PASSWORD must be set as an environment variable —
 * the server refuses to start without it so there is never a
 * predictable default credential.
 */

import crypto from "node:crypto";
import { getDb } from "../db.js";

export interface StoredToken {
  tokenId: string;
  createdAt: string;
  expiresAt: string;
  deviceLabel: string | null;
}

const TOKEN_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

/**
 * Return the configured app password or throw if it was never set.
 * Called at startup (via {@link assertPasswordConfigured}) and on
 * every login attempt.
 */
export function getAppPassword(): string {
  const pw = process.env["CODEXREMOTE_PASSWORD"];
  if (!pw) {
    throw new Error(
      "CODEXREMOTE_PASSWORD is not set. " +
        "Export it before starting the server: " +
        "export CODEXREMOTE_PASSWORD=<your-password>",
    );
  }
  return pw;
}

/**
 * Fail-fast check intended to be called once during server startup
 * so the process exits immediately with a clear message rather than
 * silently accepting requests it cannot authenticate.
 */
export function assertPasswordConfigured(): void {
  getAppPassword(); // throws if missing
}

export function createToken(deviceLabel?: string): StoredToken {
  const db = getDb();
  const tokenId = crypto.randomUUID();
  const now = new Date();
  const createdAt = now.toISOString();
  const expiresAt = new Date(now.getTime() + TOKEN_TTL_MS).toISOString();
  const label = deviceLabel ?? null;

  db.prepare(
    `INSERT INTO access_sessions (token_id, created_at, expires_at, device_label)
     VALUES (?, ?, ?, ?)`,
  ).run(tokenId, createdAt, expiresAt, label);

  return { tokenId, createdAt, expiresAt, deviceLabel: label };
}

export function verifyToken(tokenId: string): StoredToken | null {
  const db = getDb();
  const row = db
    .prepare(
      `SELECT token_id, created_at, expires_at, device_label
       FROM access_sessions
       WHERE token_id = ?`,
    )
    .get(tokenId) as
    | {
        token_id: string;
        created_at: string;
        expires_at: string;
        device_label: string | null;
      }
    | undefined;

  if (!row) return null;

  // Expired tokens are pruned eagerly so the table stays tidy.
  if (new Date(row.expires_at) < new Date()) {
    db.prepare(`DELETE FROM access_sessions WHERE token_id = ?`).run(tokenId);
    return null;
  }

  return {
    tokenId: row.token_id,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    deviceLabel: row.device_label,
  };
}

export function revokeToken(tokenId: string): boolean {
  const db = getDb();
  const result = db
    .prepare(`DELETE FROM access_sessions WHERE token_id = ?`)
    .run(tokenId);
  return result.changes > 0;
}
