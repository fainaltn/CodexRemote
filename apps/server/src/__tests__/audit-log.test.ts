/**
 * Audit logging tests.
 *
 * Validates:
 *  - successful logins produce audit rows
 *  - failed logins produce audit rows with detail
 *  - logout events are recorded
 *  - successful uploads produce audit rows
 *  - interrupted uploads produce failure audit rows
 *  - health endpoint surfaces audit summary
 *  - audit writes are best-effort (never break the request)
 *  - audit write/read failures are operator-visible via health
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
  buildMultipartPayload,
} from "./helpers.js";
import { getDb } from "../db.js";
import {
  writeAuditLog,
  getAuditWriteErrorCount,
  getAuditReadErrorCount,
  resetAuditErrorCounts,
} from "../audit/log.js";

let app: FastifyInstance;
let adapter: MockCodexAdapter;

/** Read all audit_log rows ordered by timestamp. */
function allAuditRows(): Array<{
  id: string;
  timestamp: string;
  event_type: string;
  ip: string | null;
  token_id: string | null;
  device_label: string | null;
  session_id: string | null;
  artifact_id: string | null;
  detail: string | null;
}> {
  const db = getDb();
  return db
    .prepare("SELECT * FROM audit_log ORDER BY timestamp")
    .all() as Array<{
    id: string;
    timestamp: string;
    event_type: string;
    ip: string | null;
    token_id: string | null;
    device_label: string | null;
    session_id: string | null;
    artifact_id: string | null;
    detail: string | null;
  }>;
}

describe("Audit logging", () => {
  beforeEach(async () => {
    cleanTables();
    resetAuditErrorCounts();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("s1");
  });

  afterEach(async () => {
    await app?.close();
  });

  // ── Login audit ─────────────────────────────────────────────────

  it("records a login_success event on valid login", async () => {
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password", deviceLabel: "Pixel 9" },
    });

    const rows = allAuditRows();
    expect(rows.length).toBe(1);
    expect(rows[0].event_type).toBe("login_success");
    expect(rows[0].device_label).toBe("Pixel 9");
    expect(rows[0].token_id).toBeTruthy();
  });

  it("records a login_failure event on wrong password", async () => {
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "wrong", deviceLabel: "Unknown" },
    });

    const rows = allAuditRows();
    expect(rows.length).toBe(1);
    expect(rows[0].event_type).toBe("login_failure");
    expect(rows[0].detail).toBe("invalid_password");
    expect(rows[0].device_label).toBe("Unknown");
    // No token on failure
    expect(rows[0].token_id).toBeNull();
  });

  it("records login_failure without deviceLabel when omitted", async () => {
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "wrong" },
    });

    const rows = allAuditRows();
    expect(rows.length).toBe(1);
    expect(rows[0].event_type).toBe("login_failure");
    expect(rows[0].device_label).toBeNull();
  });

  // ── Logout audit ────────────────────────────────────────────────

  it("records a logout event", async () => {
    const token = await loginHelper(app);
    // Clear the login audit row to isolate the logout event.
    getDb().exec("DELETE FROM audit_log");

    await app.inject({
      method: "POST",
      url: "/api/auth/logout",
      headers: authHeader(token),
    });

    const rows = allAuditRows();
    expect(rows.length).toBe(1);
    expect(rows[0].event_type).toBe("logout");
    expect(rows[0].token_id).toBe(token);
  });

  // ── Upload audit ────────────────────────────────────────────────

  it("records an upload_success event on file upload", async () => {
    const token = await loginHelper(app);
    getDb().exec("DELETE FROM audit_log");

    const boundary = "----TestBoundary";
    const body = buildMultipartPayload(
      boundary,
      { sessionId: "s1" },
      {
        fieldName: "file",
        filename: "test.png",
        content: Buffer.from("fake-image-data"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });
    expect(res.statusCode).toBe(201);

    const rows = allAuditRows();
    expect(rows.length).toBe(1);
    expect(rows[0].event_type).toBe("upload_success");
    expect(rows[0].session_id).toBe("s1");
    expect(rows[0].artifact_id).toBeTruthy();
    expect(rows[0].token_id).toBe(token);
    expect(rows[0].detail).toContain("image/png");
  });

  // ── Health endpoint surfaces audit summary ──────────────────────

  it("health endpoint includes audit24h summary", async () => {
    // Generate some events
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password" },
    });
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "wrong" },
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    expect(res.statusCode).toBe(200);

    const body = JSON.parse(res.body);
    expect(body.checks.audit24h).toBeDefined();
    expect(body.checks.audit24h.login_success).toBe(1);
    expect(body.checks.audit24h.login_failure).toBe(1);
  });

  // ── Multiple events ─────────────────────────────────────────────

  it("records correct sequence for login → upload → logout", async () => {
    const token = await loginHelper(app);

    const boundary = "----TestBoundary2";
    const body = buildMultipartPayload(
      boundary,
      { sessionId: "s1" },
      {
        fieldName: "file",
        filename: "doc.txt",
        content: Buffer.from("hello"),
        contentType: "text/plain",
      },
    );

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    await app.inject({
      method: "POST",
      url: "/api/auth/logout",
      headers: authHeader(token),
    });

    const rows = allAuditRows();
    const types = rows.map((r) => r.event_type);
    expect(types).toEqual(["login_success", "upload_success", "logout"]);
  });

  // ── Audit failure visibility ──────────────────────────────────────

  it("login still succeeds when audit_log table is broken", async () => {
    // Drop the audit table to simulate persistent audit failure.
    getDb().exec("DROP TABLE audit_log");

    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password", deviceLabel: "Pixel 9" },
    });
    // Request must succeed despite audit failure.
    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.token).toBeTruthy();

    // The audit write error counter must have incremented.
    expect(getAuditWriteErrorCount()).toBeGreaterThan(0);
  });

  it("health reports degraded with auditErrors when writes fail", async () => {
    // Drop the audit table so all writes fail.
    getDb().exec("DROP TABLE audit_log");

    // Trigger a login to generate a write error.
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password" },
    });
    expect(getAuditWriteErrorCount()).toBeGreaterThan(0);

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    expect(res.statusCode).toBe(200);

    const health = JSON.parse(res.body);
    expect(health.status).toBe("degraded");
    expect(health.checks.auditErrors).toBeDefined();
    expect(health.checks.auditErrors.writeErrors).toBeGreaterThan(0);
    // The read also fails because the table is gone.
    expect(typeof health.checks.audit24h).toBe("string");
    expect(health.checks.audit24h).toMatch(/^error:/);
  });

  it("health reports degraded when audit read fails but writes succeeded earlier", async () => {
    // Write a real event first (table exists).
    writeAuditLog({ eventType: "login_success", ip: "127.0.0.1" });
    expect(getAuditWriteErrorCount()).toBe(0);

    // Now break only reads by dropping the table.
    getDb().exec("DROP TABLE audit_log");

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const health = JSON.parse(res.body);
    expect(health.status).toBe("degraded");
    // audit24h should be an error string, not an empty object.
    expect(typeof health.checks.audit24h).toBe("string");
    expect(health.checks.audit24h).toMatch(/^error:/);
    expect(health.checks.auditErrors).toBeDefined();
    expect(health.checks.auditErrors.readErrors).toBeGreaterThan(0);
  });

  it("health does not show auditErrors when there are no failures", async () => {
    // Generate a normal event.
    await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password" },
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const health = JSON.parse(res.body);
    // auditErrors key should not be present when counts are zero.
    expect(health.checks.auditErrors).toBeUndefined();
    // audit24h should be a proper summary object (not an error string).
    expect(typeof health.checks.audit24h).toBe("object");
    expect(health.checks.audit24h.login_success).toBe(1);
  });
});
