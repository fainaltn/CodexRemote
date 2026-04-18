import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import { createTestApp, cleanTables } from "./helpers.js";
import { isLocalOperatorAddress } from "../routes/pairing.js";

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
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/reconnect",
      payload: {
        clientId: "client-id",
        clientSecret: "wrong-secret",
      },
    });

    expect(res.statusCode).toBe(401);
  });
});
