/**
 * Auth store SQLite durability tests.
 *
 * Validates that the token store behaves correctly at the data layer:
 *  - tokens persist in SQLite
 *  - tokens survive a database reconnection (simulated restart)
 *  - expired tokens are pruned on verify
 *  - revoked tokens are no longer verifiable
 */

import { describe, it, expect, beforeEach } from "vitest";
import { initDb, getDb, closeDb } from "../db.js";
import {
  createToken,
  verifyToken,
  revokeToken,
} from "../auth/store.js";
import { cleanTables } from "./helpers.js";

describe("Auth store — SQLite durability", () => {
  beforeEach(() => {
    // Ensure DB is open and tables are clean.
    initDb();
    cleanTables();
  });

  // ── Token persistence ─────────────────────────────────────────────

  it("createToken persists a row in access_sessions", () => {
    const token = createToken("test-device");
    const db = getDb();
    const row = db
      .prepare("SELECT * FROM access_sessions WHERE token_id = ?")
      .get(token.tokenId) as Record<string, unknown> | undefined;

    expect(row).toBeDefined();
    expect(row!["token_id"]).toBe(token.tokenId);
    expect(row!["device_label"]).toBe("test-device");
  });

  it("verifyToken returns the stored token for a valid tokenId", () => {
    const token = createToken();
    const result = verifyToken(token.tokenId);

    expect(result).not.toBeNull();
    expect(result!.tokenId).toBe(token.tokenId);
    expect(result!.expiresAt).toBe(token.expiresAt);
  });

  it("verifyToken returns null for an unknown tokenId", () => {
    const result = verifyToken("nonexistent-id");
    expect(result).toBeNull();
  });

  // ── Database reconnection (simulated restart) ─────────────────────

  it("tokens survive database close and re-open", () => {
    const token = createToken("durable-device");
    const tokenId = token.tokenId;

    // Simulate server restart: close and re-init the database.
    closeDb();
    initDb();

    const result = verifyToken(tokenId);
    expect(result).not.toBeNull();
    expect(result!.tokenId).toBe(tokenId);
    expect(result!.deviceLabel).toBe("durable-device");
  });

  // ── Expired token pruning ─────────────────────────────────────────

  it("expired token is pruned on verify and returns null", () => {
    const db = getDb();
    const tokenId = "expired-token-id";
    const past = new Date(Date.now() - 60_000).toISOString();

    db.prepare(
      "INSERT INTO access_sessions (token_id, created_at, expires_at) VALUES (?, ?, ?)",
    ).run(tokenId, past, past);

    // verifyToken should detect expiry and prune the row.
    const result = verifyToken(tokenId);
    expect(result).toBeNull();

    // Row should no longer exist.
    const row = db
      .prepare("SELECT * FROM access_sessions WHERE token_id = ?")
      .get(tokenId);
    expect(row).toBeUndefined();
  });

  // ── Token revocation ──────────────────────────────────────────────

  it("revokeToken removes the token from the store", () => {
    const token = createToken();
    expect(revokeToken(token.tokenId)).toBe(true);

    const result = verifyToken(token.tokenId);
    expect(result).toBeNull();
  });

  it("revokeToken returns false for unknown tokenId", () => {
    expect(revokeToken("nonexistent")).toBe(false);
  });

  // ── Multiple tokens ───────────────────────────────────────────────

  it("multiple tokens coexist independently", () => {
    const t1 = createToken("device-a");
    const t2 = createToken("device-b");

    expect(verifyToken(t1.tokenId)).not.toBeNull();
    expect(verifyToken(t2.tokenId)).not.toBeNull();

    revokeToken(t1.tokenId);

    expect(verifyToken(t1.tokenId)).toBeNull();
    expect(verifyToken(t2.tokenId)).not.toBeNull();
  });
});
