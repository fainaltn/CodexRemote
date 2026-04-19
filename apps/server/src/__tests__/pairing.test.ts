import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import { createTestApp, cleanTables } from "./helpers.js";
import { isLocalOperatorAddress } from "../routes/pairing.js";
import { getDb } from "../db.js";

let app: FastifyInstance;

describe("Pairing routes", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app } = await createTestApp());
  });

  afterEach(async () => {
    await app.close();
  });

  it("POST /api/pairing/code returns a short-lived pairing code for local operators", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body) as { code: string; expiresAt: string };
    expect(body.code).toMatch(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/);
    expect(new Date(body.expiresAt).toString()).not.toBe("Invalid Date");
  });

  it("POST /api/pairing/code rejects non-local callers", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "1.2.3.4",
    });

    expect(res.statusCode).toBe(403);
  });

  it("treats the machine's own LAN address as a local operator", () => {
    expect(
      isLocalOperatorAddress(
        "192.168.2.146",
        "192.168.2.146",
        new Set(["192.168.2.146"]),
      ),
    ).toBe(true);
    expect(
      isLocalOperatorAddress(
        "192.168.2.158",
        "192.168.2.158",
        new Set(["192.168.2.146"]),
      ),
    ).toBe(false);
  });

  it("claiming a pairing code issues a trusted auth token", async () => {
    const codeRes = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });
    const offer = JSON.parse(codeRes.body) as { code: string };

    const claimRes = await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });

    expect(claimRes.statusCode).toBe(200);
    const claim = JSON.parse(claimRes.body) as {
      token: string;
      expiresAt: string;
      trustedClient: {
        clientId: string;
        clientSecret: string;
        deviceLabel: string | null;
      };
    };

    expect(claim.token).toBeTruthy();
    expect(claim.trustedClient.clientId).toBeTruthy();
    expect(claim.trustedClient.clientSecret).toBeTruthy();
    expect(claim.trustedClient.deviceLabel).toBe("Pixel 9");
  });

  it("reconnects using trusted client credentials and rotates the auth token", async () => {
    const codeRes = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });
    const offer = JSON.parse(codeRes.body) as { code: string };

    const claimRes = await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });
    const claim = JSON.parse(claimRes.body) as {
      token: string;
      trustedClient: { clientId: string; clientSecret: string };
    };

    const reconnectRes = await app.inject({
      method: "POST",
      url: "/api/auth/reconnect",
      payload: {
        clientId: claim.trustedClient.clientId,
        clientSecret: claim.trustedClient.clientSecret,
        deviceLabel: "Pixel 9 Pro",
      },
    });

    expect(reconnectRes.statusCode).toBe(200);
    const reconnect = JSON.parse(reconnectRes.body) as {
      token: string;
      trustedClient: {
        clientId: string;
        deviceLabel: string | null;
      };
    };
    expect(reconnect.token).toBeTruthy();
    expect(reconnect.token).not.toBe(claim.token);
    expect(reconnect.trustedClient.clientId).toBe(claim.trustedClient.clientId);
    expect(reconnect.trustedClient.deviceLabel).toBe("Pixel 9 Pro");
  });

  it("rejects reconnect with wrong trusted client credentials", async () => {
    const codeRes = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });
    const offer = JSON.parse(codeRes.body) as { code: string };

    const claimRes = await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });
    const claim = JSON.parse(claimRes.body) as {
      trustedClient: { clientId: string };
    };

    const res = await app.inject({
      method: "POST",
      url: "/api/auth/reconnect",
      payload: {
        clientId: claim.trustedClient.clientId,
        clientSecret: "wrong-secret",
      },
    });

    expect(res.statusCode).toBe(401);
    expect(JSON.parse(res.body)).toEqual({
      error: "Trusted reconnect secret no longer matches this host",
      reason: "client_secret_mismatch",
      recoveryAction: "re_pair",
    });
  });

  it("returns a distinct reason when the trusted client is missing", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/reconnect",
      payload: {
        clientId: "missing-client",
        clientSecret: "wrong-secret",
      },
    });

    expect(res.statusCode).toBe(401);
    expect(JSON.parse(res.body)).toEqual({
      error: "Trusted reconnect is not registered on this host",
      reason: "client_not_found",
      recoveryAction: "re_pair",
    });
  });

  it("returns a precise error when a pairing code was already claimed", async () => {
    const codeRes = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });
    const offer = JSON.parse(codeRes.body) as { code: string };

    await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });

    const secondClaimRes = await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });

    expect(secondClaimRes.statusCode).toBe(409);
    expect(JSON.parse(secondClaimRes.body)).toEqual({
      error: "Pairing code was already claimed",
      reason: "code_already_claimed",
    });
  });

  it("returns a precise error when a pairing code is expired", async () => {
    const codeRes = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });
    const offer = JSON.parse(codeRes.body) as { code: string };

    getDb()
      .prepare(`UPDATE pairing_codes SET expires_at = ? WHERE code_display = ?`)
      .run(new Date(Date.now() - 60_000).toISOString(), offer.code);

    const claimRes = await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });

    expect(claimRes.statusCode).toBe(410);
    expect(JSON.parse(claimRes.body)).toEqual({
      error: "Pairing code has expired",
      reason: "code_expired",
    });
  });

  it("trusted reconnect still works after the previous token is revoked", async () => {
    const codeRes = await app.inject({
      method: "POST",
      url: "/api/pairing/code",
      remoteAddress: "127.0.0.1",
    });
    const offer = JSON.parse(codeRes.body) as { code: string };

    const claimRes = await app.inject({
      method: "POST",
      url: "/api/pairing/claim",
      payload: {
        code: offer.code,
        deviceLabel: "Pixel 9",
      },
    });
    const claim = JSON.parse(claimRes.body) as {
      token: string;
      trustedClient: { clientId: string; clientSecret: string };
    };

    const logoutRes = await app.inject({
      method: "POST",
      url: "/api/auth/logout",
      headers: {
        authorization: `Bearer ${claim.token}`,
      },
    });
    expect(logoutRes.statusCode).toBe(200);

    const reconnectRes = await app.inject({
      method: "POST",
      url: "/api/auth/reconnect",
      payload: {
        clientId: claim.trustedClient.clientId,
        clientSecret: claim.trustedClient.clientSecret,
        deviceLabel: "Pixel 9",
      },
    });

    expect(reconnectRes.statusCode).toBe(200);
    const reconnect = JSON.parse(reconnectRes.body) as {
      token: string;
    };
    expect(reconnect.token).toBeTruthy();
    expect(reconnect.token).not.toBe(claim.token);
  });
});
