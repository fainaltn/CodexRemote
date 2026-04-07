/**
 * Auth route protection tests.
 *
 * Validates:
 *  - login success / failure semantics
 *  - session check with valid / missing / revoked tokens
 *  - logout invalidation
 *  - protected routes reject unauthenticated requests
 *  - public routes remain accessible without a token
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
} from "./helpers.js";

let app: FastifyInstance;
let adapter: MockCodexAdapter;

describe("Auth routes", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("s1");
  });

  afterEach(async () => {
    await app?.close();
  });

  // ── Login ─────────────────────────────────────────────────────────

  it("POST /api/auth/login with correct password returns token", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password" },
    });
    expect(res.statusCode).toBe(200);

    const body = JSON.parse(res.body);
    expect(body).toHaveProperty("token");
    expect(body).toHaveProperty("expiresAt");
    expect(typeof body.token).toBe("string");
    expect(body.token.length).toBeGreaterThan(0);
  });

  it("POST /api/auth/login with wrong password returns 401", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "wrong-password" },
    });
    expect(res.statusCode).toBe(401);
  });

  it("POST /api/auth/login with empty body returns 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: {},
    });
    expect(res.statusCode).toBe(400);
  });

  it("POST /api/auth/login with missing password field returns 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { deviceLabel: "my-phone" },
    });
    expect(res.statusCode).toBe(400);
  });

  it("POST /api/auth/login with deviceLabel persists label in session", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password", deviceLabel: "Pixel 9" },
    });
    const { token } = JSON.parse(res.body);

    const sessionRes = await app.inject({
      method: "GET",
      url: "/api/auth/session",
      headers: authHeader(token),
    });
    const session = JSON.parse(sessionRes.body);
    expect(session.deviceLabel).toBe("Pixel 9");
  });

  // ── Session check ─────────────────────────────────────────────────

  it("GET /api/auth/session with valid token returns session info", async () => {
    const token = await loginHelper(app);
    const res = await app.inject({
      method: "GET",
      url: "/api/auth/session",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(200);

    const body = JSON.parse(res.body);
    expect(body).toHaveProperty("tokenId");
    expect(body).toHaveProperty("createdAt");
    expect(body).toHaveProperty("expiresAt");
  });

  it("GET /api/auth/session without token returns 401", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/auth/session",
    });
    expect(res.statusCode).toBe(401);
  });

  it("GET /api/auth/session with bogus token returns 401", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/auth/session",
      headers: authHeader("not-a-real-token"),
    });
    expect(res.statusCode).toBe(401);
  });

  // ── Logout ────────────────────────────────────────────────────────

  it("POST /api/auth/logout revokes the token", async () => {
    const token = await loginHelper(app);

    const logoutRes = await app.inject({
      method: "POST",
      url: "/api/auth/logout",
      headers: authHeader(token),
    });
    expect(logoutRes.statusCode).toBe(200);
    expect(JSON.parse(logoutRes.body)).toEqual({ ok: true });

    // The same token should no longer work.
    const sessionRes = await app.inject({
      method: "GET",
      url: "/api/auth/session",
      headers: authHeader(token),
    });
    expect(sessionRes.statusCode).toBe(401);
  });

  // ── Route protection boundary ─────────────────────────────────────

  it("protected route (session list) rejects requests without token", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions",
    });
    expect(res.statusCode).toBe(401);
  });

  it("protected route (session list) rejects requests with revoked token", async () => {
    const token = await loginHelper(app);
    // Revoke
    await app.inject({
      method: "POST",
      url: "/api/auth/logout",
      headers: authHeader(token),
    });
    // Try to use
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(401);
  });

  it("protected route (session list) accepts valid token", async () => {
    const token = await loginHelper(app);
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
    });
    expect(res.statusCode).toBe(200);
  });

  it("GET /api/health remains accessible without token", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.status).toMatch(/^(ok|degraded)$/);
    expect(body.checks).toBeDefined();
    expect(body.checks.database).toBe("ok");
  });

  it("POST /api/auth/login remains accessible without token", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "test-password" },
    });
    expect(res.statusCode).toBe(200);
  });

  // ── Multiple tokens ───────────────────────────────────────────────

  it("multiple logins produce independent tokens", async () => {
    const token1 = await loginHelper(app);
    const token2 = await loginHelper(app);
    expect(token1).not.toBe(token2);

    // Revoking one should not affect the other.
    await app.inject({
      method: "POST",
      url: "/api/auth/logout",
      headers: authHeader(token1),
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/auth/session",
      headers: authHeader(token2),
    });
    expect(res.statusCode).toBe(200);
  });
});
