/**
 * Rate-limiting tests.
 *
 * Validates:
 *  - global per-IP rate limiting returns 429 after exceeding max
 *  - auth login has a stricter per-route rate limit (preHandler)
 *  - login requests are subject to BOTH the global AND auth limits
 *  - 429 responses include Retry-After header
 *  - rate limit does not interfere with normal usage below threshold
 *  - non-login routes still use the global limiter as before
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../app.js";
import { MockCodexAdapter, cleanTables, authHeader } from "./helpers.js";

// ── Helpers ────────────────────────────────────────────────────────

async function createRateLimitedApp(overrides: {
  globalMax?: number;
  globalWindowMs?: number;
  authMax?: number;
  authWindowMs?: number;
} = {}) {
  const adapter = new MockCodexAdapter();
  adapter.addSession("s1");
  const { app } = await buildApp({
    logger: false,
    adapter,
    skipRecovery: true,
    skipArtifactRepair: true,
    globalRateLimitMax: overrides.globalMax ?? 50,
    globalRateLimitWindowMs: overrides.globalWindowMs ?? 60_000,
    authRateLimitMax: overrides.authMax ?? 3,
    authRateLimitWindowMs: overrides.authWindowMs ?? 60_000,
  });
  return { app, adapter };
}

/** Fire a login request with the given password. */
function login(app: FastifyInstance, password = "wrong") {
  return app.inject({
    method: "POST",
    url: "/api/auth/login",
    payload: { password },
  });
}

// ── Tests ──────────────────────────────────────────────────────────

describe("Rate limiting", () => {
  let app: FastifyInstance;

  beforeEach(() => {
    cleanTables();
  });

  afterEach(async () => {
    await app?.close();
  });

  // ── Auth login brute-force protection ─────────────────────────

  describe("auth login rate limit", () => {
    it("allows requests up to the auth limit", async () => {
      ({ app } = await createRateLimitedApp({ authMax: 3 }));

      for (let i = 0; i < 3; i++) {
        const res = await login(app);
        expect(res.statusCode).toBe(401); // wrong password, not rate-limited
      }
    });

    it("returns 429 after exceeding auth login limit", async () => {
      ({ app } = await createRateLimitedApp({ authMax: 2 }));

      for (let i = 0; i < 2; i++) {
        await login(app);
      }

      const res = await login(app);
      expect(res.statusCode).toBe(429);
    });

    it("429 response includes retry-after header", async () => {
      ({ app } = await createRateLimitedApp({ authMax: 1 }));

      await login(app);

      const res = await login(app);
      expect(res.statusCode).toBe(429);
      expect(res.headers["retry-after"]).toBeDefined();
    });

    it("rate limits failed AND successful login attempts equally", async () => {
      ({ app } = await createRateLimitedApp({ authMax: 2 }));

      // One successful login
      const res1 = await login(app, "test-password");
      expect(res1.statusCode).toBe(200);

      // One failed login
      const res2 = await login(app);
      expect(res2.statusCode).toBe(401);

      // Third attempt should be rate-limited regardless
      const res3 = await login(app, "test-password");
      expect(res3.statusCode).toBe(429);
    });
  });

  // ── Global rate limit ──────────────────────────────────────────

  describe("global rate limit", () => {
    it("returns 429 after exceeding global limit on a non-login route", async () => {
      ({ app } = await createRateLimitedApp({ globalMax: 3 }));

      for (let i = 0; i < 3; i++) {
        const res = await app.inject({ method: "GET", url: "/api/health" });
        expect(res.statusCode).toBe(200);
      }

      const res = await app.inject({ method: "GET", url: "/api/health" });
      expect(res.statusCode).toBe(429);
    });

    it("protected routes share the global rate limit counter", async () => {
      // Global limit of 4: 1 login + 3 session-list calls = 4 total.
      ({ app } = await createRateLimitedApp({ globalMax: 4, authMax: 100 }));

      const loginRes = await login(app, "test-password");
      expect(loginRes.statusCode).toBe(200);
      const token = JSON.parse(loginRes.body).token;

      // 3 more requests → total 4
      for (let i = 0; i < 3; i++) {
        await app.inject({
          method: "GET",
          url: "/api/hosts/local/sessions",
          headers: authHeader(token),
        });
      }

      // 5th request should be globally blocked
      const res = await app.inject({
        method: "GET",
        url: "/api/hosts/local/sessions",
        headers: authHeader(token),
      });
      expect(res.statusCode).toBe(429);
    });

    it("rate-limited response includes standard headers", async () => {
      ({ app } = await createRateLimitedApp({ globalMax: 1 }));

      await app.inject({ method: "GET", url: "/api/health" });

      const res = await app.inject({ method: "GET", url: "/api/health" });
      expect(res.statusCode).toBe(429);
      expect(res.headers["retry-after"]).toBeDefined();
      expect(res.headers["x-ratelimit-limit"]).toBeDefined();
      expect(res.headers["x-ratelimit-remaining"]).toBeDefined();
    });

    it("successful responses include rate limit headers", async () => {
      ({ app } = await createRateLimitedApp({ globalMax: 5 }));

      const res = await app.inject({ method: "GET", url: "/api/health" });
      expect(res.statusCode).toBe(200);
      expect(res.headers["x-ratelimit-limit"]).toBe("5");
      expect(res.headers["x-ratelimit-remaining"]).toBeDefined();
    });
  });

  // ── Two-tier stacking on login ─────────────────────────────────

  describe("login is subject to both global AND auth limits", () => {
    it("login requests count toward the global bucket", async () => {
      // Global allows 3, auth allows 100 — the global limit should bite.
      ({ app } = await createRateLimitedApp({ globalMax: 3, authMax: 100 }));

      for (let i = 0; i < 3; i++) {
        const res = await login(app);
        // Still within global limit → 401 (wrong password)
        expect(res.statusCode).toBe(401);
      }

      // 4th login exceeds global limit → 429
      const res = await login(app);
      expect(res.statusCode).toBe(429);
    });

    it("global limit blocks login even when auth limit has headroom", async () => {
      // 2 health requests eat into global budget; login should be blocked
      // by the global limiter despite auth having plenty of room.
      ({ app } = await createRateLimitedApp({ globalMax: 2, authMax: 100 }));

      await app.inject({ method: "GET", url: "/api/health" });
      await app.inject({ method: "GET", url: "/api/health" });

      // Global exhausted → login blocked by global onRequest hook
      const res = await login(app, "test-password");
      expect(res.statusCode).toBe(429);
    });

    it("auth limit blocks login even when global limit has headroom", async () => {
      // Auth allows 2, global allows 100 — auth should bite first.
      ({ app } = await createRateLimitedApp({ globalMax: 100, authMax: 2 }));

      await login(app);
      await login(app);

      const res = await login(app);
      expect(res.statusCode).toBe(429);

      // But a non-login route should still work (global has headroom).
      const healthRes = await app.inject({ method: "GET", url: "/api/health" });
      expect(healthRes.statusCode).toBe(200);
    });

    it("whichever tier is stricter wins", async () => {
      // Auth=2, global=5.  After 2 logins: auth exhausted, global=2.
      // 3rd login: global onRequest runs first (global=3), then auth
      // preHandler blocks → 429.  The global slot is still consumed.
      // So after the 3 login attempts: global has 2 remaining.
      ({ app } = await createRateLimitedApp({ globalMax: 5, authMax: 2 }));

      // 2 logins (consume 2 auth, 2 global)
      await login(app);
      await login(app);

      // 3rd login: auth blocks (preHandler 429), global=3
      const loginRes = await login(app);
      expect(loginRes.statusCode).toBe(429);

      // Health still works (global has 2 remaining: slots 4 and 5)
      const h1 = await app.inject({ method: "GET", url: "/api/health" });
      expect(h1.statusCode).toBe(200);
      const h2 = await app.inject({ method: "GET", url: "/api/health" });
      expect(h2.statusCode).toBe(200);

      // 6th global request → blocked
      const h3 = await app.inject({ method: "GET", url: "/api/health" });
      expect(h3.statusCode).toBe(429);
    });
  });
});
